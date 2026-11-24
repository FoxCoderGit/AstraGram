"""Resolve declared plugin requirements against the bundled runtime and PyPI wheels."""
import importlib
import importlib.metadata
import io
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import ssl
import stat
import sys
import tempfile
import zipfile
from hashlib import sha256

from packaging.markers import default_environment
from packaging.requirements import Requirement
from packaging.specifiers import InvalidSpecifier, SpecifierSet
from packaging.tags import sys_tags
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import InvalidVersion, Version

MAX_DOWNLOAD = 24 * 1024 * 1024
MAX_UNPACKED = 64 * 1024 * 1024
MAX_CACHE = 256 * 1024 * 1024
MAX_PACKAGES = 64


def requirements(value):
    if value is None or value == "":
        return []
    if isinstance(value, str):
        try:
            value = [Requirement(value)]
        except ValueError:
            value = re.split(r"\s*[,\n]\s*(?=[A-Za-z0-9])", value)
    if not isinstance(value, (list, tuple)):
        raise ValueError("Plugin requirements must be text or a list")
    result = [item if isinstance(item, Requirement) else Requirement(item) for item in value]
    if any(item.url for item in result):
        raise ValueError("Plugin dependencies must be named PyPI packages")
    return result


def _download(url, maximum):
    from urllib.parse import urlsplit
    from urllib.request import HTTPSHandler, HTTPRedirectHandler, build_opener
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in ("pypi.org", "files.pythonhosted.org"):
        raise ValueError("Unexpected dependency download URL")
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, message, headers, new_url):
            return None

    try:
        import certifi
        context = ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        context = ssl.create_default_context()
    opener = build_opener(NoRedirect(), HTTPSHandler(context=context))
    with opener.open(url, timeout=12) as response:
        if response.status != 200:
            raise OSError("PyPI returned HTTP " + str(response.status))
        data = bytearray()
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > maximum:
                raise OSError("Dependency download too large")
        return bytes(data)


def _json(url):
    import json
    return json.loads(_download(url, MAX_DOWNLOAD))


def _candidate(requirement):
    project = canonicalize_name(requirement.name)
    index = _json(f"https://pypi.org/pypi/{project}/json")
    tags = {tag: rank for rank, tag in enumerate(sys_tags())
            if tag.platform == "any" or tag.platform.startswith("android_")}
    python = Version(".".join(map(str, sys.version_info[:3])))
    choices = []
    for label, files in index.get("releases", {}).items():
        try:
            version = Version(label)
        except InvalidVersion:
            continue
        if not requirement.specifier.contains(version):
            continue
        for file in files:
            if file.get("packagetype") != "bdist_wheel" or file.get("yanked"):
                continue
            try:
                name, wheel_version, _, wheel_tags = parse_wheel_filename(file["filename"])
                if not SpecifierSet(file.get("requires_python") or "").contains(python, prereleases=True):
                    continue
            except (KeyError, ValueError, InvalidSpecifier):
                continue
            if name != project or wheel_version != version:
                continue
            matching = [tags[tag] for tag in wheel_tags if tag in tags]
            if matching:
                choices.append((version, -min(matching), file))
    if not choices:
        raise ValueError("No compatible Python wheel for " + str(requirement))
    return max(choices, key=lambda item: item[:2])[2]


def _extract(content, target):
    with zipfile.ZipFile(io.BytesIO(content)) as wheel:
        items = wheel.infolist()
        if len(items) > 8192 or sum(item.file_size for item in items) > MAX_UNPACKED:
            raise ValueError("Dependency archive too large")
        written = set()
        for item in items:
            name = PurePosixPath(item.filename)
            if (not name.parts or name.is_absolute() or ".." in name.parts or "\\" in item.filename
                    or ":" in item.filename or stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError("Invalid dependency archive path")
            if item.is_dir():
                continue
            if name.parts[0].endswith(".data"):
                if len(name.parts) < 3 or name.parts[1] not in ("purelib", "platlib"):
                    continue
                name = PurePosixPath(*name.parts[2:])
            if name in written:
                raise ValueError("Duplicate dependency archive path")
            written.add(name)
            output = target.joinpath(*name.parts)
            output.parent.mkdir(parents=True, exist_ok=True)
            with wheel.open(item) as source, output.open("xb") as destination:
                shutil.copyfileobj(source, destination, 65536)


def _distribution(requirement, cache):
    name = canonicalize_name(requirement.name)
    try:
        installed = importlib.metadata.distribution(name)
    except importlib.metadata.PackageNotFoundError:
        installed = None
    if installed is not None:
        if not requirement.specifier.contains(installed.version, prereleases=True):
            raise ValueError(f"{name} {installed.version} is already loaded and conflicts with {requirement}")
        return installed
    for candidate in sorted(cache.glob(name + "--*")):
        for cached in importlib.metadata.distributions(path=[str(candidate)]):
            if canonicalize_name(cached.metadata["Name"]) == name and requirement.specifier.contains(cached.version, prereleases=True):
                sys.path.append(str(candidate))
                return cached

    wheel = _candidate(requirement)
    expected = wheel.get("digests", {}).get("sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or not 0 < wheel.get("size", 0) <= MAX_DOWNLOAD:
        raise ValueError("Invalid PyPI wheel metadata")
    content = _download(wheel["url"], MAX_DOWNLOAD)
    if len(content) != wheel["size"] or sha256(content).hexdigest() != expected:
        raise ValueError("Dependency checksum mismatch")
    cache_size = sum(item.stat().st_size for item in cache.rglob("*") if item.is_file())
    with tempfile.TemporaryDirectory(prefix="wheel-", dir=cache) as stage:
        unpacked = Path(stage) / "package"
        unpacked.mkdir()
        _extract(content, unpacked)
        if cache_size + sum(item.stat().st_size for item in unpacked.rglob("*") if item.is_file()) > MAX_CACHE:
            raise ValueError("Plugin dependency cache is full")
        distributions = list(importlib.metadata.distributions(path=[str(unpacked)]))
        if (len(distributions) != 1 or canonicalize_name(distributions[0].metadata["Name"]) != name
                or not requirement.specifier.contains(distributions[0].version, prereleases=True)):
            raise ValueError("Wheel metadata does not match the dependency")
        target = cache / (name + "--" + expected)
        os.replace(unpacked, target)
    sys.path.append(str(target))
    importlib.invalidate_caches()
    return importlib.metadata.distribution(name)


def install(value, plugins_dir):
    pending = requirements(value)
    if not pending:
        return
    cache = Path(plugins_dir).parent / "plugin-dependencies"
    cache.mkdir(exist_ok=True)
    environment = default_environment()
    seen = set()
    original_paths = list(sys.path)
    try:
        while pending:
            requirement = pending.pop(0)
            if requirement.marker and not requirement.marker.evaluate(environment | {"extra": ""}):
                continue
            key = str(requirement)
            if key in seen:
                continue
            seen.add(key)
            if len(seen) > MAX_PACKAGES:
                raise ValueError("Too many plugin dependencies")
            distribution = _distribution(requirement, cache)
            extras = requirement.extras or {""}
            for value in distribution.requires or ():
                child = Requirement(value)
                if child.marker and not any(child.marker.evaluate(environment | {"extra": extra}) for extra in extras):
                    continue
                child.marker = None
                if child.url:
                    raise ValueError("Plugin dependencies must be named PyPI packages")
                pending.append(child)
    except Exception:
        sys.path[:] = original_paths
        raise
