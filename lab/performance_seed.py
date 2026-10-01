"""Idempotent LocalStack DynamoDB dataset seeder for read-only performance tests."""
import concurrent.futures
import json
import os
import random
import time
from pathlib import Path

import boto3
from botocore.config import Config

ENDPOINT = "http://localhost:4566"
ACCOUNT = "123456789012"
REGION = "us-east-1"
INDEX = "status-sk-index"
STATE_DIR = Path("/lab-state")
CHECKPOINT_DIR = STATE_DIR / "seed-checkpoints"
MANIFEST = STATE_DIR / "seed-manifest.json"

BASELINE = int(os.getenv("LAB_PERF_RECORDS", "10000"))
PRIMARY = int(os.getenv("LAB_PERF_PRIMARY_RECORDS", str(BASELINE)))
WORKERS = int(os.getenv("LAB_PERF_SEED_WORKERS", "8"))
AUTO_SEED = os.getenv("LAB_PERF_AUTO_SEED", "1") == "1"
MAX_RETRIES = int(os.getenv("LAB_PERF_SEED_RETRIES", "8"))

PROFILES = [
    ("lab-perf-01-small-uniform", 512, False, 1000),
    ("lab-perf-02-small-skewed", 512, True, 1000),
    ("lab-perf-03-medium-uniform", 2048, False, 1000),
    ("lab-perf-04-medium-skewed", 2048, True, 1000),
    ("lab-perf-05-large-uniform", 8192, False, 1000),
    ("lab-perf-06-low-selectivity", 1024, False, 500),
    ("lab-perf-07-high-cardinality", 1024, False, 10000),
    ("lab-perf-08-hot-key", 1024, True, 100),
]

if BASELINE < 1 or PRIMARY < BASELINE or WORKERS < 1 or WORKERS > 64:
    raise SystemExit("Invalid LAB_PERF_RECORDS/LAB_PERF_PRIMARY_RECORDS/LAB_PERF_SEED_WORKERS")

STATE_DIR.mkdir(parents=True, exist_ok=True)
CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)

def client():
    return boto3.client(
        "dynamodb",
        endpoint_url=ENDPOINT,
        region_name=REGION,
        aws_access_key_id=ACCOUNT,
        aws_secret_access_key="test",
        config=Config(
            max_pool_connections=max(32, WORKERS * 4),
            retries={"max_attempts": 0},
            connect_timeout=3,
            read_timeout=60,
        ),
    )

DDB = client()

def ensure_table(name):
    if name in DDB.list_tables()["TableNames"]:
        return False
    DDB.create_table(
        TableName=name,
        BillingMode="PAY_PER_REQUEST",
        AttributeDefinitions=[
            {"AttributeName": "pk", "AttributeType": "S"},
            {"AttributeName": "sk", "AttributeType": "S"},
            {"AttributeName": "status", "AttributeType": "S"},
        ],
        KeySchema=[
            {"AttributeName": "pk", "KeyType": "HASH"},
            {"AttributeName": "sk", "KeyType": "RANGE"},
        ],
        GlobalSecondaryIndexes=[{
            "IndexName": INDEX,
            "KeySchema": [
                {"AttributeName": "status", "KeyType": "HASH"},
                {"AttributeName": "sk", "KeyType": "RANGE"},
            ],
            "Projection": {"ProjectionType": "ALL"},
        }],
    )
    waiter = DDB.get_waiter("table_exists")
    waiter.wait(TableName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 60})
    return True

def tenant(skewed, tenants, sequence):
    if skewed and sequence % 10 < 8:
        bucket = 0
    else:
        bucket = sequence % tenants
    return f"tenant-{bucket:05d}"

def key(profile, sequence):
    name, _, skewed, tenants = profile
    return {
        "pk": {"S": tenant(skewed, tenants, sequence)},
        "sk": {"S": f"record-{sequence:012d}"},
    }

def item(profile, sequence, payload):
    value = key(profile, sequence)
    value.update({
        "status": {"S": "MATCH" if sequence % 100 == 0 else ("PENDING" if sequence % 2 == 0 else "SETTLED")},
        "category": {"S": f"category-{sequence % 50}"},
        "version": {"N": "1"},
        "payload": {"S": payload},
    })
    return value

def checkpoint_path(table, worker):
    return CHECKPOINT_DIR / f"{table}-worker-{worker}.checkpoint"

def load_checkpoint(profile, worker):
    path = checkpoint_path(profile[0], worker)
    if not path.exists():
        return worker
    current = int(path.read_text(encoding="utf-8").strip())
    if current <= worker:
        return worker
    previous = current - WORKERS
    response = DDB.get_item(TableName=profile[0], Key=key(profile, previous), ConsistentRead=True)
    return current if "Item" in response else worker

def save_checkpoint(table, worker, next_value):
    target = checkpoint_path(table, worker)
    temp = target.with_suffix(target.suffix + ".tmp")
    temp.write_text(str(next_value), encoding="utf-8")
    os.replace(temp, target)

def write_batch(table, requests):
    pending = list(requests)
    retries = 0
    while pending:
        response = DDB.batch_write_item(RequestItems={table: pending})
        pending = response.get("UnprocessedItems", {}).get(table, [])
        if not pending:
            return retries
        if retries >= MAX_RETRIES:
            raise RuntimeError(f"UnprocessedItems retry budget exhausted for {table}")
        retries += 1
        time.sleep(min(1.0, (0.01 * (2 ** retries)) + random.uniform(0, 0.01)))

def seed_worker(profile, target, worker, payload):
    current = load_checkpoint(profile, worker)
    written = 0
    retry_rounds = 0
    while current < target:
        requests = []
        next_value = current
        for _ in range(25):
            if next_value >= target:
                break
            requests.append({"PutRequest": {"Item": item(profile, next_value, payload)}})
            next_value += WORKERS
        retry_rounds += write_batch(profile[0], requests)
        written += len(requests)
        current = next_value
        save_checkpoint(profile[0], worker, current)
    return written, retry_rounds, current

def seed_profile(profile, target):
    created = ensure_table(profile[0])
    if created:
        for worker in range(WORKERS):
            checkpoint_path(profile[0], worker).unlink(missing_ok=True)
    payload = "x" * profile[1]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS, thread_name_prefix="seed") as pool:
        results = list(pool.map(lambda w: seed_worker(profile, target, w, payload), range(WORKERS)))
    elapsed = max(0.000001, time.perf_counter() - started)
    written = sum(r[0] for r in results)
    retries = sum(r[1] for r in results)
    return {
        "table": profile[0],
        "targetRecords": target,
        "payloadBytes": profile[1],
        "skewed": profile[2],
        "tenants": profile[3],
        "recordsWrittenThisRun": written,
        "retryRounds": retries,
        "elapsedSeconds": elapsed,
        "recordsPerSecond": written / elapsed,
        "workerNext": [r[2] for r in results],
    }

if not AUTO_SEED:
    print(json.dumps({"performanceSeed": "disabled"}))
    raise SystemExit(0)

started_all = time.perf_counter()
results = []
for index, profile in enumerate(PROFILES):
    target = PRIMARY if index == 0 else BASELINE
    result = seed_profile(profile, target)
    results.append(result)
    print(json.dumps({"seeded": result}, separators=(",", ":")), flush=True)

manifest = {
    "schemaVersion": 1,
    "scope": "LOCALSTACK_DYNAMODB_SEED",
    "baselineRecordsPerTable": BASELINE,
    "primaryRecords": PRIMARY,
    "seedWorkers": WORKERS,
    "elapsedSeconds": time.perf_counter() - started_all,
    "tables": results,
}
temp_manifest = MANIFEST.with_suffix(".json.tmp")
temp_manifest.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
os.replace(temp_manifest, MANIFEST)
print(json.dumps({"seedManifest": str(MANIFEST), "elapsedSeconds": manifest["elapsedSeconds"]}), flush=True)
