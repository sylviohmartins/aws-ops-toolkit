"""Validate an offline-built DynamoDB Local fixture through its public API."""

import argparse
import concurrent.futures
import json
import threading
import time
from pathlib import Path

import boto3
from botocore.config import Config


def client(endpoint, access_key, read_timeout, max_attempts):
    return boto3.client(
        "dynamodb",
        endpoint_url=endpoint,
        region_name="us-east-1",
        aws_access_key_id=access_key,
        aws_secret_access_key="test",
        config=Config(
            connect_timeout=10,
            read_timeout=read_timeout,
            max_pool_connections=8,
            retries={"mode": "standard", "max_attempts": max_attempts},
        ),
    )


def load_checkpoint(path):
    if not path.is_file():
        return {
            "pages": 0,
            "scanned": 0,
            "matches": 0,
            "cursor": None,
            "completed": False,
        }
    return json.loads(path.read_text(encoding="utf-8"))


def save_checkpoint(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(
        json.dumps(state, separators=(",", ":"), ensure_ascii=False),
        encoding="utf-8",
    )
    temp.replace(path)


def verify_incident_needles(ddb, table, expected):
    sequences = list(range(0, expected, 10_000))
    verified = 0
    for offset in range(0, len(sequences), 100):
        keys = [
            {
                "pk": {"S": f"tenant-{sequence % 1000:05d}"},
                "sk": {"S": f"record-{sequence:012d}"},
            }
            for sequence in sequences[offset : offset + 100]
        ]
        pending = {
            table: {
                "Keys": keys,
                "ProjectionExpression": "pk,sk,#i",
                "ExpressionAttributeNames": {"#i": "incident"},
            }
        }
        while pending:
            response = ddb.batch_get_item(RequestItems=pending)
            items = response.get("Responses", {}).get(table, [])
            for item in items:
                correlation = (
                    item.get("incident", {})
                    .get("M", {})
                    .get("correlationId", {})
                    .get("S")
                )
                if correlation != "incident-needle":
                    raise AssertionError(
                        f"incident needle probe mismatch: {item!r}"
                    )
            verified += len(items)
            pending = response.get("UnprocessedKeys", {})
            if pending:
                time.sleep(0.1)
    if verified != len(sequences):
        raise AssertionError(
            {
                "needleProbesVerified": verified,
                "expectedNeedles": len(sequences),
            }
        )
    return verified


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:8000")
    parser.add_argument("--access-key", default="123456789012useast1")
    parser.add_argument("--table", default="lab-perf-v3-01-rich-uniform")
    parser.add_argument("--expected", type=int, required=True)
    parser.add_argument("--full-scan", action="store_true")
    parser.add_argument("--probe-only", action="store_true")
    parser.add_argument("--segments", type=int, default=64)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--read-timeout", type=int, default=300)
    parser.add_argument("--max-attempts", type=int, default=5)
    parser.add_argument(
        "--checkpoint-dir",
        default="/data/offline-validation",
    )
    args = parser.parse_args()
    if (
        args.expected < 1
        or args.segments < 1
        or args.workers < 1
        or args.read_timeout < 1
        or args.max_attempts < 1
    ):
        raise SystemExit(
            "expected, segments, workers, read-timeout and max-attempts must be positive"
        )

    if args.probe_only and args.full_scan:
        raise SystemExit("--probe-only cannot be combined with --full-scan")

    ddb = client(
        args.endpoint,
        args.access_key,
        args.read_timeout,
        args.max_attempts,
    )
    result = {
        "expected": args.expected,
        "validationMode": (
            "public-api-probes-query"
            if args.probe_only
            else "describe-plus-probes-query"
        ),
    }
    if not args.probe_only:
        described = ddb.describe_table(TableName=args.table)["Table"]
        result["itemCount"] = described.get("ItemCount")
        result["tableSizeBytes"] = described.get("TableSizeBytes")
        if result["itemCount"] != args.expected:
            raise AssertionError(result)

    probes = (0, args.expected - 1)
    for sequence in probes:
        key = {
            "pk": {"S": f"tenant-{sequence % 1000:05d}"},
            "sk": {"S": f"record-{sequence:012d}"},
        }
        response = ddb.get_item(
            TableName=args.table,
            Key=key,
            ProjectionExpression="pk,sk,transactionId",
        )
        if "Item" not in response:
            raise AssertionError(f"missing probe {sequence}")

    last = args.expected - 1
    query = ddb.query(
        TableName=args.table,
        KeyConditionExpression="pk = :pk AND sk = :sk",
        ExpressionAttributeValues={
            ":pk": {"S": f"tenant-{last % 1000:05d}"},
            ":sk": {"S": f"record-{last:012d}"},
        },
    )
    if query["Count"] != 1:
        raise AssertionError(f"exact query returned {query['Count']}")
    result["probesVerified"] = list(probes)
    result["exactQueryCount"] = query["Count"]

    if args.full_scan:
        started = time.perf_counter()
        checkpoint_root = (
            Path(args.checkpoint_dir)
            / f"{args.table}-{args.expected}-segments-{args.segments}"
        )
        progress_lock = threading.Lock()
        progress = {
            "segmentsCompleted": 0,
            "scanned": 0,
            "matches": 0,
            "pages": 0,
            "resumedSegments": 0,
        }

        def scan_segment(segment):
            path = checkpoint_root / f"segment-{segment:04d}.json"
            state = load_checkpoint(path)
            if state["completed"]:
                with progress_lock:
                    progress["resumedSegments"] += 1
                return (
                    state["pages"],
                    state["scanned"],
                    state["matches"],
                    True,
                )

            segment_client = client(
                args.endpoint,
                args.access_key,
                args.read_timeout,
                args.max_attempts,
            )
            cursor = state.get("cursor")
            pages = int(state.get("pages", 0))
            scanned = int(state.get("scanned", 0))
            matches = int(state.get("matches", 0))

            while True:
                request = {
                    "TableName": args.table,
                    "Select": "COUNT",
                    "Limit": 5000,
                    "Segment": segment,
                    "TotalSegments": args.segments,
                }
                if cursor:
                    request["ExclusiveStartKey"] = cursor
                response = segment_client.scan(**request)
                scanned += response["ScannedCount"]
                matches += 0
                pages += 1
                cursor = response.get("LastEvaluatedKey")
                save_checkpoint(
                    path,
                    {
                        "pages": pages,
                        "scanned": scanned,
                        "matches": matches,
                        "cursor": cursor,
                        "completed": not bool(cursor),
                    },
                )
                if not cursor:
                    return pages, scanned, matches, False

        workers = min(args.workers, args.segments)
        segment_results = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            future_to_segment = {
                pool.submit(scan_segment, segment): segment
                for segment in range(args.segments)
            }
            for future in concurrent.futures.as_completed(future_to_segment):
                segment = future_to_segment[future]
                pages, scanned, matches, resumed = future.result()
                segment_results.append((pages, scanned, matches, resumed))
                with progress_lock:
                    progress["segmentsCompleted"] += 1
                    progress["scanned"] += scanned
                    progress["matches"] += matches
                    progress["pages"] += pages
                    if progress["segmentsCompleted"] % 8 == 0 or progress[
                        "segmentsCompleted"
                    ] == args.segments:
                        elapsed = max(
                            0.000001,
                            time.perf_counter() - started,
                        )
                        print(
                            json.dumps(
                                {
                                    "event": "FULL_SCAN_PROGRESS",
                                    "segment": segment,
                                    "segmentsCompleted": progress[
                                        "segmentsCompleted"
                                    ],
                                    "segments": args.segments,
                                    "scannedCompletedSegments": progress[
                                        "scanned"
                                    ],
                                    "matchesCompletedSegments": progress[
                                        "matches"
                                    ],
                                    "pagesCompletedSegments": progress["pages"],
                                    "elapsedSeconds": elapsed,
                                    "completedSegmentItemsPerSecond": progress[
                                        "scanned"
                                    ]
                                    / elapsed,
                                }
                            ),
                            flush=True,
                        )

        pages = sum(value[0] for value in segment_results)
        scanned = sum(value[1] for value in segment_results)
        resumed_segments = sum(1 for value in segment_results if value[3])
        elapsed = max(0.000001, time.perf_counter() - started)
        if scanned != args.expected:
            raise AssertionError(
                {
                    "scanned": scanned,
                    "expected": args.expected,
                }
            )
        needle_probes = verify_incident_needles(
            ddb,
            args.table,
            args.expected,
        )
        result["fullScan"] = {
            "pages": pages,
            "scanned": scanned,
            "elapsedSeconds": elapsed,
            "scannedPerSecond": scanned / elapsed,
            "segments": args.segments,
            "workers": workers,
            "readTimeoutSeconds": args.read_timeout,
            "maxAttempts": args.max_attempts,
            "checkpointDir": str(checkpoint_root),
            "resumedSegments": resumed_segments,
            "needleProbesVerified": needle_probes,
            "expectedNeedles": ((args.expected - 1) // 10_000) + 1,
        }

    result["status"] = "OK"
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
