"""Exercise the production offline parser and every registry range boundary."""
from pathlib import Path
import importlib.util
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "TMessagesProj_Ghost/src/main/python"))
import phone_info

PATH = str(ROOT / "TMessagesProj_Ghost/src/main/assets/phone/ru-ranges.pack")


def run():
    ru = phone_info.lookup("+79161234567", "", PATH)
    assert ru["registry"] and ru["country"] == "Россия" and "ТелеСистемы" in ru["operator"]
    assert "Москва" in ru["region"] and ru["date"] == "2026-10-01"
    for spelling in ("8 (916) 123-45-67", "79161234567", "9161234567", "tel:+7 916 123.45.67"):
        assert phone_info.lookup(spelling, "", PATH)["number"] == ru["number"], spelling
    assert "Ростелеком" in phone_info.lookup("+74951234567", "", PATH)["operator"]
    kz = phone_info.lookup("+77011234567", "", PATH)
    assert kz["country"] == "Казахстан" and not kz.get("registry")
    gb = phone_info.lookup("020 8366 1177", "447911123456", PATH)
    assert gb["number"] == "+442083661177" and gb["region"] == "London"
    assert phone_info.lookup("+12025550123", "", PATH)["country"] == "Соединенные Штаты"
    assert phone_info.lookup("+390236618300", "", PATH)["number"] == "+390236618300"
    for anonymous in ("+88812345678", "88812345678"):
        result = phone_info.lookup(anonymous, "", PATH)
        assert result["anonymous"] and "operator" not in result
    for invalid in ("123", "call me", "+7", "+1234567890123456", "999<script>"):
        assert phone_info.lookup(invalid, "", PATH) == {"error": "invalid"}
    # Wrong/empty own-account phone must not crash or alter international numbers.
    assert phone_info.lookup("+442083661177", "1", PATH)["number"] == "+442083661177"
    assert json.loads(phone_info.lookup_json("+79161234567", "", PATH)) == ru
    header, tables = phone_info.database(PATH)
    count = 0
    for info, (starts, ends, operators, places) in tables:
        for i in range(len(starts)):
            expected_op = info["operators"][operators[i]][1]
            for n in {starts[i], (starts[i] + ends[i]) // 2, ends[i]}:
                found = phone_info.russian_range(n, PATH)
                assert found and found["operator"] == expected_op, (info["kind"], i, n)
                assert found["range"] == f"+7{starts[i]:010d} – +7{ends[i]:010d}"
            if i and starts[i] > ends[i - 1] + 1:
                assert phone_info.russian_range(starts[i] - 1, PATH) is None
            count += 1
    assert count == 418152
    print(f"PASS: normalization, Russia/Kazakhstan, GB/US/Italy, anonymous numbers, invalid input; {count} ranges checked at start/middle/end and gaps")


if __name__ == "__main__": run()
