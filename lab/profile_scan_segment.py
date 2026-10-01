"""Profile page latency for one complete DynamoDB scan segment."""
import argparse
import json
import statistics
import time

import boto3
from botocore.config import Config

PROJECTION = "pk,sk,#status,version,transactionId,paymentId,accountId,document"
PROJECTION_NAMES = {"#status": "status"}


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://aws-perf-persistent:4566")
    parser.add_argument("--access-key", default="123456789012")
    parser.add_argument("--table", default="lab-perf-v3-01-rich-uniform")
    parser.add_argument("--segment", type=int, default=0)
    parser.add_argument("--segments", type=int, default=32)
    parser.add_argument("--page-size", type=int, default=1000)
    parser.add_argument("--read-timeout", type=int, default=120)
    parser.add_argument("--max-attempts", type=int, default=3)
    args = parser.parse_args()

    ddb = boto3.client(
        "dynamodb",
        endpoint_url=args.endpoint,
        region_name="us-east-1",
        aws_access_key_id=args.access_key,
        aws_secret_access_key="test",
        config=Config(
            connect_timeout=10,
            read_timeout=args.read_timeout,
            max_pool_connections=4,
            retries={"mode": "standard", "max_attempts": args.max_attempts},
        ),
    )

    cursor = None
    pages = scanned = 0
    latencies = []
    started = time.perf_counter()
    while True:
        request = {
            "TableName": args.table,
            "Limit": args.page_size,
            "Segment": args.segment,
            "TotalSegments": args.segments,
            "ProjectionExpression": PROJECTION,
            "ExpressionAttributeNames": PROJECTION_NAMES,
        }
        if cursor:
            request["ExclusiveStartKey"] = cursor
        page_started = time.perf_counter()
        response = ddb.scan(**request)
        latency = time.perf_counter() - page_started
        latencies.append(latency)
        pages += 1
        scanned += response["ScannedCount"]
        cursor = response.get("LastEvaluatedKey")
        if pages % 25 == 0 or latency >= 5 or not cursor:
            print(json.dumps({
                "event": "SEGMENT_PROGRESS",
                "pages": pages,
                "scanned": scanned,
                "lastSeconds": round(latency, 3),
                "maxSeconds": round(max(latencies), 3),
                "p95Seconds": round(percentile(latencies, 0.95), 3),
                "elapsedSeconds": round(time.perf_counter() - started, 3),
            }), flush=True)
        if not cursor:
            break

    elapsed = max(1e-6, time.perf_counter() - started)
    print(json.dumps({
        "status": "OK",
        "segment": args.segment,
        "segments": args.segments,
        "pages": pages,
        "scanned": scanned,
        "elapsedSeconds": elapsed,
        "scannedPerSecond": scanned / elapsed,
        "meanSeconds": statistics.fmean(latencies),
        "p50Seconds": percentile(latencies, 0.50),
        "p95Seconds": percentile(latencies, 0.95),
        "p99Seconds": percentile(latencies, 0.99),
        "maxSeconds": max(latencies),
    }), flush=True)


if __name__ == "__main__":
    main()
