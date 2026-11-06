"""Convert phoneoperator's MIT-licensed registry snapshot to an offline binary index.

Download its npm tarball into Tools/phone-number-data first. No JavaScript is executed.
The input is strictly JSON literals plus base-36 range deltas.
"""
import array
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Tools/phone-number-data/mirror/package"
TARGET = ROOT / "TMessagesProj_Ghost/src/main/assets/phone/ru-ranges.pack"


def literal(text, name):
    match = re.search(r"export const " + name + r" = (.*);", text)
    if not match:
        raise ValueError("Missing literal " + name)
    return json.loads(match.group(1))


def build():
    tables, versions = [], set()
    payload = bytearray()
    for kind in ("mobile", "landline"):
        path = SOURCE / ("dist/data/" + kind + ".js")
        text = path.read_text("utf8")
        version = re.search(r"UPDATED = '([\d-]+)'", text).group(1)
        versions.add(version)
        operators, places = literal(text, "OPERATORS"), literal(text, "PLACES")
        starts, ends, op_ids, place_ids = array.array("Q"), array.array("Q"), array.array("H"), array.array("H")
        previous = -1
        for entry in literal(text, "RANGES").split(";"):
            gap, length, op, place = (int(v, 36) for v in entry.split(","))
            start = previous + 1 + gap
            end = start + length
            assert 0 <= start <= end < 10_000_000_000 and start > previous
            assert 0 <= op < len(operators) and 0 <= place < len(places)
            starts.append(start); ends.append(end); op_ids.append(op); place_ids.append(place)
            previous = end
        assert len(starts) > (10000 if kind == "mobile" else 300000)
        tables.append(dict(kind=kind, count=len(starts), operators=operators, places=places,
                           sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        for values in (starts, ends, op_ids, place_ids):
            if sys.byteorder != "little": values.byteswap()
            payload.extend(values.tobytes())
    assert len(versions) == 1
    header = dict(version=1, date=versions.pop(), source="phoneoperator 0.1.0",
                  source_url="https://github.com/MrProLopstar/phoneoperator",
                  registry_url="https://opendata.digital.gov.ru/registry/numeric/downloads/", tables=tables)
    encoded = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf8")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_bytes(gzip.compress(b"GPR1" + struct.pack("<I", len(encoded)) + encoded + payload, mtime=0))
    (TARGET.parent / "phoneoperator-LICENSE.txt").write_bytes((SOURCE / "LICENSE").read_bytes())
    print("Snapshot", header["date"], "ranges", sum(t["count"] for t in tables), "bytes", TARGET.stat().st_size)


if __name__ == "__main__": build()
