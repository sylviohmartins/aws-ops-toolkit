"""Prepare persisted DynamoDB performance fixtures outside the Java benchmark."""
import concurrent.futures
import json
import os
import random
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

ENDPOINT = os.getenv("LAB_PERF_ENDPOINT", "http://aws:4566")
REGION = "us-east-1"
ACCOUNT = "123456789012"
DDB_ACCESS_KEY = os.getenv("LAB_PERF_DDB_ACCESS_KEY", ACCOUNT)
DIRECT_DDB = os.getenv("LAB_PERF_DIRECT_DDB", "0") == "1"
ALLOW_RESHARD = os.getenv("LAB_PERF_ALLOW_RESHARD", "0") == "1"
BASELINE = int(os.getenv("LAB_PERF_RECORDS", "1000"))
PRIMARY = int(os.getenv("LAB_PERF_PRIMARY_RECORDS", str(BASELINE)))
WORKERS = int(os.getenv("LAB_PERF_SEED_WORKERS", "8"))
CHECKPOINT_EVERY = int(os.getenv("LAB_PERF_CHECKPOINT_EVERY_BATCHES", "100"))
MODE = os.getenv("LAB_PERF_MODE", "PERSISTENT").upper()
PRIMARY_GSI = os.getenv("LAB_PERF_PRIMARY_GSI", "1") == "1"
STATE = Path(os.getenv("LAB_PERF_STATE_DIR", "/state"))
CHECKPOINTS = STATE / "seed-checkpoints"
MANIFEST = STATE / "seed-manifest.json"
INDEX = "status-sk-index"
MAX_RETRIES = 8
LOCAL_HOSTS = {"aws", "aws-perf-memory", "aws-perf-persistent", "127.0.0.1", "localhost", "::1"}
VALID_MODES = {"PERSISTENT", "IN_MEMORY"}

ITEM_SHAPE = "enterprise-incident-v1"
PROFILES = [
    ("lab-perf-v3-01-rich-uniform", 512, False, 1000),
    ("lab-perf-v3-02-rich-skewed", 512, True, 1000),
    ("lab-perf-v3-03-medium-uniform", 4096, False, 1000),
    ("lab-perf-v3-04-medium-skewed", 4096, True, 1000),
    ("lab-perf-v3-05-large-uniform", 16384, False, 1000),
    ("lab-perf-v3-06-low-selectivity", 2048, False, 500),
    ("lab-perf-v3-07-high-cardinality", 2048, False, 10000),
    ("lab-perf-v3-08-hot-key", 2048, True, 100),
]

def client():
    return boto3.client(
        "dynamodb",
        endpoint_url=ENDPOINT,
        region_name=REGION,
        aws_access_key_id=DDB_ACCESS_KEY,
        aws_secret_access_key="test",
        config=Config(max_pool_connections=max(16, WORKERS * 2), retries={"mode": "standard", "max_attempts": 3}),
    )

def ensure_table(ddb, table, include_index=True):
    try:
        ddb.describe_table(TableName=table)
        return False
    except ddb.exceptions.ResourceNotFoundException:
        pass
    request = {
        "TableName": table,
        "BillingMode": "PAY_PER_REQUEST",
        "AttributeDefinitions": [
            {"AttributeName": "pk", "AttributeType": "S"},
            {"AttributeName": "sk", "AttributeType": "S"},
        ],
        "KeySchema": [
            {"AttributeName": "pk", "KeyType": "HASH"},
            {"AttributeName": "sk", "KeyType": "RANGE"},
        ],
    }
    if include_index:
        request["AttributeDefinitions"].append(
            {"AttributeName": "status", "AttributeType": "S"}
        )
        request["GlobalSecondaryIndexes"] = [{
            "IndexName": INDEX,
            "KeySchema": [
                {"AttributeName": "status", "KeyType": "HASH"},
                {"AttributeName": "sk", "KeyType": "RANGE"},
            ],
            "Projection": {"ProjectionType": "KEYS_ONLY"},
        }]
    ddb.create_table(**request)
    ddb.get_waiter("table_exists").wait(TableName=table)
    return True

def tenant(skewed, tenants, sequence):
    bucket = 0 if skewed and sequence % 10 < 8 else sequence % tenants
    return f"tenant-{bucket:05d}"

def item(profile, sequence, payload):
    table, _, skewed, tenants = profile
    del table
    status = "MATCH" if sequence % 100 == 0 else ("PENDING" if sequence % 2 == 0 else "SETTLED")
    correlation = "incident-needle" if sequence % 10000 == 0 else f"corr-{sequence:016x}"
    account = f"{sequence % 100000000:08d}"
    document = f"{sequence % 99999999999:011d}"
    return {
        "pk": {"S": tenant(skewed, tenants, sequence)},
        "sk": {"S": f"record-{sequence:012d}"},
        "status": {"S": status},
        "category": {"S": f"category-{sequence % 50}"},
        "version": {"N": "1"},
        "transactionId": {"S": f"txn-{sequence:016x}"},
        "paymentId": {"S": f"pay-{sequence:014d}"},
        "customerId": {"S": f"customer-{sequence % 10000000:07d}"},
        "accountId": {"S": f"acct-{account}"},
        "document": {"S": document},
        "amount": {"N": str((sequence % 5000000) + 100)},
        "currency": {"S": "BRL"},
        "channel": {"S": ("API", "BATCH", "MOBILE", "BRANCH")[sequence % 4]},
        "productCode": {"S": f"product-{sequence % 24:02d}"},
        "createdAt": {"S": f"2026-09-{(sequence % 28) + 1:02d}T{sequence % 24:02d}:00:00Z"},
        "updatedAt": {"S": f"2026-09-{(sequence % 28) + 1:02d}T{sequence % 24:02d}:30:00Z"},
        "active": {"BOOL": sequence % 7 != 0},
        "retryCount": {"N": str(sequence % 4)},
        "tags": {"SS": [f"tag-{sequence % 8}", f"flow-{sequence % 5}"]},
        "incident": {"M": {
            "correlationId": {"S": correlation},
            "traceId": {"S": f"trace-{sequence:016x}"},
            "sourceSystem": {"S": f"system-{sequence % 12:02d}"},
            "operation": {"S": ("PAYMENT", "REVERSAL", "RECONCILIATION")[sequence % 3]},
            "priority": {"N": str((sequence % 5) + 1)},
        }},
        "routing": {"M": {
            "bankCode": {"S": f"{sequence % 999:03d}"},
            "branch": {"S": f"{sequence % 10000:04d}"},
            "account": {"S": account},
            "region": {"S": ("SP", "RJ", "MG", "PR")[sequence % 4]},
        }},
        "counterparty": {"M": {
            "name": {"S": f"counterparty-{sequence % 100000:05d}"},
            "document": {"S": f"{(sequence * 17) % 99999999999:011d}"},
            "type": {"S": "LEGAL" if sequence % 3 == 0 else "PERSON"},
        }},
        "history": {"L": [
            {"M": {"state": {"S": "CREATED"}, "at": {"N": str(1700000000 + sequence)}}},
            {"M": {"state": {"S": status}, "at": {"N": str(1700000060 + sequence)}}},
        ]},
        "metadata": {"M": {
            "schemaVersion": {"N": "3"},
            "origin": {"S": "performance-lab"},
            "flags": {"L": [{"S": "audited"}, {"S": "replayable"}]},
        }},
        "payload": {"S": payload},
    }

def checkpoint_path(table, worker):
    return CHECKPOINTS / f"{table}-worker-{worker}.checkpoint"

def read_checkpoint(table, worker):
    path = checkpoint_path(table, worker)
    return int(path.read_text().strip()) if path.exists() else worker

def aligned_checkpoint(start, worker):
    return start + ((worker - start) % WORKERS)

def previous_table_target(table):
    if not MANIFEST.exists():
        raise RuntimeError("cannot reshard without an existing seed manifest")
    previous = json.loads(MANIFEST.read_text())
    if (
        previous.get("scope") != "LOCALSTACK_DYNAMODB_FIXTURE"
        or previous.get("mode") != MODE
        or previous.get("account") != ACCOUNT
        or previous.get("itemShape") != ITEM_SHAPE
    ):
        raise RuntimeError("cannot reshard checkpoints from an incompatible seed manifest")
    for entry in previous.get("tables", []):
        if entry.get("table") == table:
            return int(entry["targetRecords"])
    raise RuntimeError(f"cannot reshard {table}: table is missing from seed manifest")

def validate_completed_range(ddb, profile, completed):
    if completed < 1:
        raise RuntimeError("cannot reshard an empty fixture")
    table = profile[0]
    described = ddb.describe_table(TableName=table)["Table"]
    item_count = int(described.get("ItemCount", -1))
    if item_count != completed:
        raise RuntimeError(
            f"cannot reshard {table}: DynamoDB ItemCount={item_count} "
            f"but manifest target={completed}"
        )
    for sequence in (0, completed - 1):
        key = {
            "pk": {"S": tenant(profile[2], profile[3], sequence)},
            "sk": {"S": f"record-{sequence:012d}"},
        }
        if "Item" not in ddb.get_item(
            TableName=table, Key=key, ProjectionExpression="pk,sk"
        ):
            raise RuntimeError(
                f"cannot reshard {table}: sentinel sequence {sequence} is missing"
            )

def validate_checkpoint_topology(ddb, profile):
    table = profile[0]
    paths = list(CHECKPOINTS.glob(f"{table}-worker-*.checkpoint"))
    if not paths:
        return
    worker_ids = sorted(
        int(path.stem.rsplit("-worker-", 1)[1])
        for path in paths
    )
    expected = list(range(WORKERS))
    if worker_ids == expected:
        return
    if not ALLOW_RESHARD:
        raise RuntimeError(
            f"checkpoint worker topology mismatch for {table}: "
            f"found={worker_ids} expected={expected}; "
            "explicitly enable validated resharding or rebuild the fixture"
        )
    completed = previous_table_target(table)
    validate_completed_range(ddb, profile, completed)
    for path in paths:
        path.unlink()
    for worker in range(WORKERS):
        save_checkpoint(table, worker, aligned_checkpoint(completed, worker))
    print(
        json.dumps({
            "event": "CHECKPOINT_RESHARD",
            "table": table,
            "completedRecords": completed,
            "workers": WORKERS,
        }),
        flush=True,
    )

def save_checkpoint(table, worker, value):
    path = checkpoint_path(table, worker)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(str(value))
    os.replace(temp, path)

def write_batch(ddb, table, requests):
    pending = requests
    retries = 0
    for attempt in range(MAX_RETRIES + 1):
        response = ddb.batch_write_item(RequestItems={table: pending})
        pending = response.get("UnprocessedItems", {}).get(table, [])
        if not pending:
            return retries
        retries += 1
        if attempt >= MAX_RETRIES:
            raise RuntimeError(f"retry budget exhausted for {table}: {len(pending)} items")
        time.sleep(min(1.0, (0.01 * (2 ** attempt))) + random.random() * 0.01)
    return retries

def seed_worker(profile, target, worker, payload):
    table = profile[0]
    ddb = client()
    current = max(worker, read_checkpoint(table, worker))
    written = retries = batches = 0
    while current < target:
        requests = []
        next_value = current
        for _ in range(25):
            if next_value >= target:
                break
            requests.append({"PutRequest": {"Item": item(profile, next_value, payload)}})
            next_value += WORKERS
        retries += write_batch(ddb, table, requests)
        written += len(requests)
        batches += 1
        current = next_value
        if batches % CHECKPOINT_EVERY == 0:
            save_checkpoint(table, worker, current)
    save_checkpoint(table, worker, current)
    return written, retries

def seed_table(profile, target):
    table, payload_bytes, _, _ = profile
    ddb = client()
    include_index = PRIMARY_GSI or table != PROFILES[0][0]
    created = ensure_table(ddb, table, include_index)
    if created:
        for worker in range(WORKERS):
            checkpoint_path(table, worker).unlink(missing_ok=True)
    else:
        validate_checkpoint_topology(ddb, profile)
    payload = "x" * payload_bytes
    started = time.perf_counter()
    written = retries = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS, thread_name_prefix="seed") as pool:
        futures = [
            pool.submit(seed_worker, profile, target, worker, payload)
            for worker in range(WORKERS)
        ]
        for future in concurrent.futures.as_completed(futures):
            worker_written, worker_retries = future.result()
            written += worker_written
            retries += worker_retries
    elapsed = max(0.000001, time.perf_counter() - started)
    result = {
        "table": table,
        "targetRecords": target,
        "recordsWrittenThisRun": written,
        "retryRounds": retries,
        "elapsedSeconds": elapsed,
        "recordsPerSecond": written / elapsed,
        "payloadBytes": payload_bytes,
        "globalSecondaryIndex": include_index,
    }
    print(json.dumps(result), flush=True)
    return result

def main():
    parsed = urlparse(ENDPOINT)
    if parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS or parsed.username or parsed.password:
        raise SystemExit(f"refusing non-local performance endpoint: {ENDPOINT}")
    if MODE not in VALID_MODES:
        raise SystemExit(f"invalid LAB_PERF_MODE: {MODE}")
    if (
        BASELINE < 1
        or PRIMARY < BASELINE
        or not 1 <= WORKERS <= 64
        or CHECKPOINT_EVERY < 1
    ):
        raise SystemExit(
            "invalid LAB_PERF_RECORDS / LAB_PERF_PRIMARY_RECORDS / "
            "LAB_PERF_SEED_WORKERS / checkpoint configuration"
        )
    CHECKPOINTS.mkdir(parents=True, exist_ok=True)
    if DIRECT_DDB:
        expected_direct_key = ACCOUNT + REGION.replace("-", "")
        if DDB_ACCESS_KEY != expected_direct_key:
            raise SystemExit(
                f"direct DynamoDB Local mode requires access key {expected_direct_key}"
            )
        identity_account = ACCOUNT
    else:
        probe = boto3.client(
            "sts", endpoint_url=ENDPOINT, region_name=REGION,
            aws_access_key_id=ACCOUNT, aws_secret_access_key="test")
        identity = probe.get_caller_identity()
        if identity["Account"] != ACCOUNT or not identity["Arn"].endswith(":root"):
            raise SystemExit("refusing to seed unexpected LocalStack identity")
        identity_account = identity["Account"]
    started = time.perf_counter()
    tables = [
        seed_table(profile, PRIMARY if index == 0 else BASELINE)
        for index, profile in enumerate(PROFILES)
    ]
    manifest = {
        "scope": "LOCALSTACK_DYNAMODB_FIXTURE",
        "mode": MODE,
        "endpoint": ENDPOINT,
        "account": identity_account,
        "baselineRecordsPerTable": BASELINE,
        "primaryRecords": PRIMARY,
        "seedWorkers": WORKERS,
        "primaryGsi": PRIMARY_GSI,
        "itemShape": ITEM_SHAPE,
        "checkpointEveryBatches": CHECKPOINT_EVERY,
        "elapsedSeconds": time.perf_counter() - started,
        "tables": tables,
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2))
    print(f"SEED COMPLETE manifest={MANIFEST}", flush=True)

if __name__ == "__main__":
    main()
