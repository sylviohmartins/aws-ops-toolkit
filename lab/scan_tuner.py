"""Short parallel-scan tuner for a prepared DynamoDB Local/LocalStack fixture."""
import argparse
import concurrent.futures
import json
import math
import time

import boto3
from botocore.config import Config

PROJECTION = "pk,sk,#status,version,transactionId,paymentId,accountId,document"
PROJECTION_NAMES = {"#status": "status"}


def client(endpoint, access_key, timeout, attempts):
    return boto3.client(
        "dynamodb",
        endpoint_url=endpoint,
        region_name="us-east-1",
        aws_access_key_id=access_key,
        aws_secret_access_key="test",
        config=Config(
            connect_timeout=10,
            read_timeout=timeout,
            max_pool_connections=8,
            retries={"mode": "standard", "max_attempts": attempts},
        ),
    )


def run_config(args, workers, segments):
    pages_per_segment = max(1, math.ceil(args.sample_pages / segments))

    def scan_segment(segment):
        ddb = client(args.endpoint, args.access_key, args.read_timeout, args.max_attempts)
        cursor = None
        pages = scanned = returned = 0
        for _ in range(pages_per_segment):
            request = {
                "TableName": args.table,
                "Limit": args.page_size,
                "Segment": segment,
                "TotalSegments": segments,
                "ProjectionExpression": PROJECTION,
                "ExpressionAttributeNames": PROJECTION_NAMES,
            }
            if cursor:
                request["ExclusiveStartKey"] = cursor
            response = ddb.scan(**request)
            pages += 1
            scanned += response["ScannedCount"]
            returned += response["Count"]
            cursor = response.get("LastEvaluatedKey")
            if not cursor:
                break
        return pages, scanned, returned

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        values = list(pool.map(scan_segment, range(segments)))
    elapsed = max(1e-6, time.perf_counter() - started)
    pages = sum(v[0] for v in values)
    scanned = sum(v[1] for v in values)
    returned = sum(v[2] for v in values)
    return {
        "workers": workers,
        "segments": segments,
        "pages": pages,
        "scanned": scanned,
        "returned": returned,
        "elapsedSeconds": elapsed,
        "scannedPerSecond": scanned / elapsed,
        "pagesPerSecond": pages / elapsed,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://aws-perf-persistent:4566")
    parser.add_argument("--access-key", default="123456789012")
    parser.add_argument("--table", default="lab-perf-v3-01-rich-uniform")
    parser.add_argument("--sample-pages", type=int, default=32)
    parser.add_argument("--page-size", type=int, default=5000)
    parser.add_argument("--read-timeout", type=int, default=120)
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument(
        "--configs",
        default="1x1,2x2,4x4,8x8,16x16,4x64,8x64,4x128,8x128,16x128",
        help="Comma-separated worker x segment pairs, for example 8x128,16x128",
    )
    args = parser.parse_args()
    configs = []
    for raw in args.configs.split(","):
        workers_text, segments_text = raw.strip().lower().split("x", maxsplit=1)
        workers = int(workers_text)
        segments = int(segments_text)
        if workers < 1 or segments < 1 or workers > segments:
            raise SystemExit(f"invalid config {raw!r}: require 1 <= workers <= segments")
        configs.append((workers, segments))
    for workers, segments in configs:
        try:
            result = run_config(args, workers, segments)
            result["status"] = "OK"
        except Exception as exc:
            result = {
                "workers": workers,
                "segments": segments,
                "status": "ERROR",
                "error": f"{type(exc).__name__}: {exc}",
            }
        print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
