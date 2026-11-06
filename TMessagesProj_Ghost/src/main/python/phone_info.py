"""Offline numbering metadata. Never resolves subscribers or current locations."""
import array
from bisect import bisect_right
import gzip
import json
import re
import struct
import sys
from functools import lru_cache

import phonenumbers as pn
from phonenumbers import carrier, geocoder, timezone


@lru_cache(maxsize=1)
def database(path):
    with gzip.open(path, "rb") as stream:
        if stream.read(4) != b"GPR1": raise ValueError("Invalid phone database")
        size = struct.unpack("<I", stream.read(4))[0]
        if size > 4_000_000: raise ValueError("Invalid phone database header")
        header = json.loads(stream.read(size))
        if header["version"] != 1: raise ValueError("Unsupported phone database")
        tables = []
        for info in header["tables"]:
            values = []
            for typecode, width in (("Q", 8), ("Q", 8), ("H", 2), ("H", 2)):
                block = stream.read(info["count"] * width)
                if len(block) != info["count"] * width: raise ValueError("Truncated phone database")
                data = array.array(typecode)
                data.frombytes(block)
                if sys.byteorder != "little": data.byteswap()
                values.append(data)
            tables.append((info, values))
        if stream.read(1): raise ValueError("Unexpected phone database data")
    return header, tables


def russian_range(national, path):
    header, tables = database(path)
    value = int(national)
    for info, (starts, ends, operators, places) in tables:
        index = bisect_right(starts, value) - 1
        if index < 0 or value > ends[index]: continue
        place = info["places"][places[index]].split("|", 1)
        region, locality = (place[1], place[0]) if len(place) == 2 else (place[0], "")
        return dict(operator=info["operators"][operators[index]][1], region=region,
                    locality=locality, date=header["date"], source=header["source"],
                    range=f"+7{starts[index]:010d} – +7{ends[index]:010d}")
    return None


def lookup(raw, own_phone="", db_path=""):
    raw = str(raw).strip()
    if raw.lower().startswith("tel:"): raw = raw[4:]
    compact = re.sub(r"[\s().\-\u00a0]", "", raw)
    if re.fullmatch(r"\+?888\d{8}", compact):
        return {"number": "+" + compact.lstrip("+"), "anonymous": True}
    if len(raw) > 128 or not re.fullmatch(r"\+?\d{5,15}", compact):
        return {"error": "invalid"}
    default_region = "RU"
    try:
        if own_phone: default_region = pn.region_code_for_number(pn.parse("+" + str(own_phone).lstrip("+"))) or "RU"
    except pn.NumberParseException: pass
    if not compact.startswith("+") and len(compact) == 11 and compact.startswith("7"):
        compact = "+" + compact
    try:
        number = pn.parse(compact, default_region)
    except pn.NumberParseException:
        return {"error": "invalid"}
    if not pn.is_possible_number(number): return {"error": "invalid"}
    result = dict(number=pn.format_number(number, pn.PhoneNumberFormat.E164),
                  formatted=pn.format_number(number, pn.PhoneNumberFormat.INTERNATIONAL),
                  country=geocoder.country_name_for_number(number, "ru"),
                  region=geocoder.description_for_number(number, "ru"),
                  operator=carrier.name_for_number(number, "ru"),
                  valid=pn.is_valid_number(number),
                  timezones=[z for z in timezone.time_zones_for_number(number) if z != "Etc/Unknown"],
                  source="phonenumbers " + pn.__version__)
    # Kazakhstan shares +7: only an exact match in the Russian registry is Russian.
    if number.country_code == 7 and db_path:
        found = russian_range(number.national_number, db_path)
        if found:
            result.update(found)
            result.update(country="Россия", valid=True, registry=True)
        elif pn.region_code_for_number(number) == "RU":
            result["range_missing"] = True
    return result


def lookup_json(raw, own_phone, path):
    return json.dumps(lookup(raw, own_phone, path), ensure_ascii=False)
