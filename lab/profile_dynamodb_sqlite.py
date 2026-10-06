"""Read-only profiler for the DynamoDB Local SQLite backing store.

This is an emulator diagnostic. It is not an alternative access path for
managed DynamoDB and must never be used against production data.
"""

import argparse
import json
import math
import sqlite3
import statistics
import time
from pathlib import Path


def quote_identifier(value):
    return '"' + value.replace('"', '""') + '"'


def percentile(values, quantile):
    ordered = sorted(values)
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def tenant(skewed, tenants, sequence):
    bucket = 0 if skewed and sequence % 10 < 8 else sequence % tenants
    return f"tenant-{bucket:05d}"


def explain(cursor, sql, parameters=()):
    return [
        {
            "id": row[0],
            "parent": row[1],
            "notUsed": row[2],
            "detail": row[3],
        }
        for row in cursor.execute("EXPLAIN QUERY PLAN " + sql, parameters).fetchall()
    ]


def timed_count(cursor, table_sql):
    started = time.perf_counter()
    rows = cursor.execute(f"SELECT COUNT(*) FROM {table_sql}").fetchone()[0]
    return {
        "rows": rows,
        "elapsedSeconds": time.perf_counter() - started,
    }


def point_lookup_profile(cursor, table_sql, target, samples, skewed, tenants):
    latencies_ms = []
    hits = 0
    if samples == 1:
        sequences = [0]
    else:
        sequences = [
            round(index * (target - 1) / (samples - 1))
            for index in range(samples)
        ]
    sql = (
        f"SELECT itemSize FROM {table_sql} "
        "WHERE hashKey=? AND rangeKey=?"
    )
    for sequence in sequences:
        pk = tenant(skewed, tenants, sequence).encode("utf-8")
        sk = f"record-{sequence:012d}".encode("utf-8")
        started = time.perf_counter_ns()
        row = cursor.execute(
            sql,
            (sqlite3.Binary(pk), sqlite3.Binary(sk)),
        ).fetchone()
        latencies_ms.append((time.perf_counter_ns() - started) / 1_000_000.0)
        hits += row is not None
    return {
        "samples": samples,
        "hits": hits,
        "averageMillis": statistics.fmean(latencies_ms),
        "p50Millis": percentile(latencies_ms, 0.50),
        "p95Millis": percentile(latencies_ms, 0.95),
        "p99Millis": percentile(latencies_ms, 0.99),
        "maxMillis": max(latencies_ms),
    }


def sequential_profile(cursor, table_sql, limit):
    rows = 0
    payload_bytes = 0
    started = time.perf_counter()
    for (blob,) in cursor.execute(
        f"SELECT ObjectJSON FROM {table_sql} LIMIT ?",
        (limit,),
    ):
        rows += 1
        payload_bytes += len(blob)
    elapsed = max(time.perf_counter() - started, 1e-9)
    return {
        "limit": limit,
        "rows": rows,
        "payloadBytes": payload_bytes,
        "elapsedSeconds": elapsed,
        "rowsPerSecond": rows / elapsed,
        "mebibytesPerSecond": payload_bytes / elapsed / 1024 / 1024,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--table", required=True)
    parser.add_argument("--target", required=True, type=int)
    parser.add_argument("--point-samples", type=int, default=1000)
    parser.add_argument("--sequential-limit", type=int, default=100000)
    parser.add_argument("--tenants", type=int, default=1000)
    parser.add_argument("--skewed", action="store_true")
    parser.add_argument("--git-commit", default="UNKNOWN")
    parser.add_argument("--git-dirty", action="store_true")
    args = parser.parse_args()

    if (
        args.target < 1
        or args.point_samples < 1
        or args.sequential_limit < 1
        or args.tenants < 1
    ):
        raise SystemExit("numeric arguments must be positive")

    database = Path(args.db)
    if not database.is_file():
        raise SystemExit(f"database not found: {database}")

    uri = f"file:{database.as_posix()}?mode=ro"
    with sqlite3.connect(uri, uri=True, timeout=5) as connection:
        connection.execute("PRAGMA query_only=ON")
        cursor = connection.cursor()
        schema = cursor.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            (args.table,),
        ).fetchone()
        if schema is None:
            raise SystemExit(f"table not found: {args.table}")

        table_sql = quote_identifier(args.table)
        indexes = [
            {"name": row[0], "sql": row[1]}
            for row in cursor.execute(
                "SELECT name,sql FROM sqlite_master "
                "WHERE type='index' AND tbl_name=? ORDER BY name",
                (args.table,),
            ).fetchall()
        ]

        key_pk = sqlite3.Binary(tenant(args.skewed, args.tenants, 0).encode("utf-8"))
        key_sk = sqlite3.Binary(b"record-000000000000")
        count_sql = f"SELECT COUNT(*) FROM {table_sql}"
        key_sql = (
            f"SELECT ObjectJSON FROM {table_sql} "
            "WHERE hashKey=? AND rangeKey=?"
        )
        sequential_sql = f"SELECT ObjectJSON FROM {table_sql} LIMIT 1000"

        count = timed_count(cursor, table_sql)
        if count["rows"] != args.target:
            raise RuntimeError(
                f"unexpected cardinality: rows={count['rows']} target={args.target}"
            )

        report = {
            "kind": "LOCALSTACK_SQLITE_BACKING_STORE_DIAGNOSTIC",
            "readOnly": True,
            "managedDynamoDbEquivalent": False,
            "warning": (
                "SQLite results diagnose DynamoDB Local's backing store only; "
                "they are not an access strategy or performance projection for AWS DynamoDB."
            ),
            "database": str(database),
            "databaseBytes": database.stat().st_size,
            "table": args.table,
            "targetRecords": args.target,
            "gitCommit": args.git_commit,
            "gitDirty": args.git_dirty,
            "schema": schema[0],
            "indexes": indexes,
            "plans": {
                "count": explain(cursor, count_sql),
                "pointLookup": explain(cursor, key_sql, (key_pk, key_sk)),
                "sequentialLimit1000": explain(cursor, sequential_sql),
            },
            "count": count,
            "pointLookup": point_lookup_profile(
                cursor,
                table_sql,
                args.target,
                args.point_samples,
                args.skewed,
                args.tenants,
            ),
            "sequentialObjectJson": sequential_profile(
                cursor,
                table_sql,
                args.sequential_limit,
            ),
        }
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()