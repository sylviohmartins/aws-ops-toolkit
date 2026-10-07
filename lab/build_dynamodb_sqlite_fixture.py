"""Offline builder/validator for the DynamoDB Local performance fixture.

The builder reproduces DynamoDB Local's SQLite row representation for the
synthetic performance table. Verification is read-only by default.
"""
import argparse
import hashlib
import json
import sqlite3
import time
from decimal import Decimal
from pathlib import Path

from seed_dynamodb import ITEM_SHAPE, PROFILES, item, tenant

SALT = b"LocalDdb"
PRIMARY = PROFILES[0]
TABLE = PRIMARY[0]


def number_encoded_length(raw):
    value = Decimal(raw)
    if value == 0:
        return 1
    normalized = abs(value).normalize()
    sign, digits, exponent = normalized.as_tuple()
    del sign
    precision = len(digits)
    adjusted_exponent = precision + exponent
    pad = 1 if abs(adjusted_exponent) % 2 == 1 else 0
    digit_count = precision + pad
    pairs = (digit_count + 1) // 2
    negative_extra = 1 if value < 0 and pairs < 20 else 0
    return pairs + 1 + negative_extra


def attribute_size(value):
    if "S" in value:
        return len(value["S"].encode("utf-8"))
    if "N" in value:
        return number_encoded_length(value["N"])
    if "B" in value:
        return len(value["B"])
    if "SS" in value:
        return sum(len(element.encode("utf-8")) for element in value["SS"])
    if "NS" in value:
        return sum(number_encoded_length(element) for element in value["NS"])
    if "BS" in value:
        return sum(len(element) for element in value["BS"])
    if "BOOL" in value or "NULL" in value:
        return 1
    if "L" in value:
        values = value["L"]
        return sum(attribute_size(element) for element in values) + len(values) + 3
    if "M" in value:
        mapping = value["M"]
        return item_size(mapping, nested=True) + 3
    raise ValueError(f"unsupported DynamoDB value: {value!r}")


def item_size(value, nested=False):
    total = sum(
        len(name.encode("utf-8")) + attribute_size(attribute)
        for name, attribute in value.items()
    )
    if nested:
        total += len(value)
    return total


def key_hash(value):
    return hashlib.sha1(SALT + value.encode("utf-8")).digest()


def compact_json(value):
    return json.dumps(
        value,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def normalize_semantics(value):
    if isinstance(value, dict):
        normalized = {
            key: normalize_semantics(element)
            for key, element in value.items()
        }
        for set_key in ("SS", "NS", "BS"):
            if set_key in normalized:
                normalized[set_key] = sorted(normalized[set_key])
        return normalized
    if isinstance(value, list):
        return [normalize_semantics(element) for element in value]
    return value


def db_row(connection, sequence):
    pk = tenant(PRIMARY[2], PRIMARY[3], sequence).encode("utf-8")
    sk = f"record-{sequence:012d}".encode("utf-8")
    return connection.execute(
        f'SELECT hashKey,rangeKey,hashValue,rangeValue,itemSize,ObjectJSON '
        f'FROM "{TABLE}" WHERE hashKey=? AND rangeKey=?',
        (sqlite3.Binary(pk), sqlite3.Binary(sk)),
    ).fetchone()


def verify_sequence(connection, sequence, payload):
    generated = item(PRIMARY, sequence, payload)
    row = db_row(connection, sequence)
    if row is None:
        raise AssertionError(f"missing SQLite row for sequence={sequence}")

    expected_pk = generated["pk"]["S"].encode("utf-8")
    expected_sk = generated["sk"]["S"].encode("utf-8")
    expected_hash = key_hash(generated["pk"]["S"])
    expected_range = key_hash(generated["sk"]["S"])
    expected_size = item_size(generated)
    stored_json = json.loads(bytes(row[5]).decode("utf-8"))
    generated_semantics = normalize_semantics(generated)
    stored_semantics = normalize_semantics(stored_json)
    if sequence == 0:
        generated_semantics.pop("version", None)
        stored_semantics.pop("version", None)

    checks = {
        "hashKey": bytes(row[0]) == expected_pk,
        "rangeKey": bytes(row[1]) == expected_sk,
        "hashValue": bytes(row[2]) == expected_hash,
        "rangeValue": bytes(row[3]) == expected_range,
        "itemSize": int(row[4]) == expected_size,
        "ObjectJSON": stored_semantics == generated_semantics,
    }
    failures = [name for name, ok in checks.items() if not ok]
    if failures:
        raise AssertionError(
            f"sequence={sequence} mismatches={failures} "
            f"storedSize={row[4]} generatedSize={expected_size}"
        )
    return expected_size


def sqlite_row(sequence, payload):
    generated = item(PRIMARY, sequence, payload)
    pk = generated["pk"]["S"]
    sk = generated["sk"]["S"]
    return (
        sqlite3.Binary(pk.encode("utf-8")),
        sqlite3.Binary(sk.encode("utf-8")),
        sqlite3.Binary(key_hash(pk)),
        sqlite3.Binary(key_hash(sk)),
        item_size(generated),
        sqlite3.Binary(compact_json(generated)),
    )


def append_rows(connection, start, target, batch_size):
    if target <= start:
        raise ValueError("target must be greater than append start")
    existing = connection.execute(
        f'SELECT COUNT(*) FROM "{TABLE}"'
    ).fetchone()[0]
    if existing != start:
        raise RuntimeError(
            f"append requires exact contiguous start: rows={existing} start={start}"
        )
    payload = "x" * PRIMARY[1]
    sql = (
        f'INSERT INTO "{TABLE}" '
        "(hashKey,rangeKey,hashValue,rangeValue,itemSize,ObjectJSON) "
        "VALUES (?,?,?,?,?,?)"
    )
    started = time.perf_counter()
    written = 0
    for batch_start in range(start, target, batch_size):
        batch_end = min(target, batch_start + batch_size)
        rows = [sqlite_row(sequence, payload) for sequence in range(batch_start, batch_end)]
        with connection:
            connection.executemany(sql, rows)
        written += len(rows)
        if written % max(batch_size, 100_000) == 0 or batch_end == target:
            elapsed = max(0.000001, time.perf_counter() - started)
            print(
                json.dumps({
                    "event": "OFFLINE_APPEND_PROGRESS",
                    "written": written,
                    "target": target,
                    "recordsPerSecond": written / elapsed,
                }),
                flush=True,
            )
    elapsed = max(0.000001, time.perf_counter() - started)
    return written, elapsed


def verify(connection, target, samples):
    payload = "x" * PRIMARY[1]
    candidates = {
        0,
        1,
        11,
        99,
        100,
        101,
        999,
        1000,
        9999,
        10000,
        max(0, target - 1),
    }
    if samples > len(candidates):
        step = max(1, target // (samples - len(candidates)))
        candidates.update(range(0, target, step))
    selected = sorted(value for value in candidates if value < target)[:samples]
    sizes = [verify_sequence(connection, value, payload) for value in selected]
    count = connection.execute(
        f'SELECT COUNT(*) FROM "{TABLE}"'
    ).fetchone()[0]
    summary = {
        "mode": "VERIFY_ONLY",
        "itemShape": ITEM_SHAPE,
        "table": TABLE,
        "databaseRows": count,
        "target": target,
        "samplesVerified": len(selected),
        "minItemSize": min(sizes),
        "maxItemSize": max(sizes),
        "sequences": selected,
    }
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--target", type=int, required=True)
    parser.add_argument("--samples", type=int, default=64)
    parser.add_argument("--append-from", type=int)
    parser.add_argument("--batch-size", type=int, default=10_000)
    args = parser.parse_args()

    if args.target < 1 or args.samples < 1 or args.batch_size < 1:
        raise SystemExit("target, samples and batch-size must be positive")
    database = Path(args.db)
    if not database.is_file():
        raise SystemExit(f"database not found: {database}")

    if args.append_from is None:
        uri = f"file:{database.as_posix()}?mode=ro"
        with sqlite3.connect(uri, uri=True) as connection:
            verify(connection, args.target, args.samples)
        return

    with sqlite3.connect(database) as connection:
        connection.execute("PRAGMA synchronous=OFF")
        written, elapsed = append_rows(
            connection, args.append_from, args.target, args.batch_size
        )
        connection.execute(f'ANALYZE "{TABLE}"')
        connection.execute("PRAGMA optimize")
        final_count = connection.execute(
            f'SELECT COUNT(*) FROM "{TABLE}"'
        ).fetchone()[0]
        print(json.dumps({
            "mode": "OFFLINE_APPEND",
            "itemShape": ITEM_SHAPE,
            "table": TABLE,
            "start": args.append_from,
            "target": args.target,
            "written": written,
            "elapsedSeconds": elapsed,
            "recordsPerSecond": written / elapsed,
            "databaseRows": final_count,
        }, indent=2))


if __name__ == "__main__":
    main()
