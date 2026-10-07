"""Estimate logical and heuristic physical capacity for all performance profiles.

The logical projection is derived from the deterministic fixture generator.
The physical projection is only a heuristic calibrated from the current
primary-table SQLite density; it is not a storage guarantee.
"""

import argparse
import json
import statistics

from build_dynamodb_sqlite_fixture import item_size
from seed_dynamodb import PROFILES, item


def profile_average_size(profile, samples):
    payload = "x" * profile[1]
    return statistics.fmean(
        item_size(item(profile, sequence, payload))
        for sequence in range(samples)
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-per-table", type=int, default=100_000_000)
    parser.add_argument("--samples", type=int, default=1_000)
    parser.add_argument("--primary-db-bytes", type=int, required=True)
    parser.add_argument("--primary-records", type=int, required=True)
    args = parser.parse_args()

    if (
        args.target_per_table < 1
        or args.samples < 1
        or args.primary_db_bytes < 1
        or args.primary_records < 1
    ):
        raise SystemExit("all numeric arguments must be positive")

    profiles = []
    for index, profile in enumerate(PROFILES):
        average = profile_average_size(profile, args.samples)
        profiles.append(
            {
                "table": profile[0],
                "payloadBytes": profile[1],
                "skewed": profile[2],
                "tenants": profile[3],
                "globalSecondaryIndex": index != 0,
                "averageLogicalItemBytes": average,
                "targetRecords": args.target_per_table,
                "projectedLogicalBytes": average * args.target_per_table,
            }
        )

    primary_average = profiles[0]["averageLogicalItemBytes"]
    primary_physical_per_record = args.primary_db_bytes / args.primary_records
    physical_to_logical_ratio = primary_physical_per_record / primary_average

    total_logical = sum(profile["projectedLogicalBytes"] for profile in profiles)
    calibrated_physical = total_logical * physical_to_logical_ratio

    report = {
        "kind": "MULTITABLE_FIXTURE_CAPACITY_ESTIMATE",
        "targetPerTable": args.target_per_table,
        "tableCount": len(profiles),
        "totalTargetRecords": args.target_per_table * len(profiles),
        "samplesPerProfile": args.samples,
        "totalProjectedLogicalBytes": total_logical,
        "totalProjectedLogicalTiB": total_logical / (1024**4),
        "primaryObservedPhysicalBytesPerRecord": primary_physical_per_record,
        "primaryAverageLogicalItemBytes": primary_average,
        "primaryPhysicalToLogicalRatio": physical_to_logical_ratio,
        "calibratedPhysicalHeuristicBytes": calibrated_physical,
        "calibratedPhysicalHeuristicTiB": calibrated_physical / (1024**4),
        "physicalHeuristicBinding": False,
        "warning": (
            "Logical bytes exclude SQLite page/index overhead. The calibrated physical "
            "projection reuses the primary table's observed physical/logical ratio and "
            "does not model larger-item page behavior or the seven GSIs; treat it only "
            "as an order-of-magnitude heuristic, not a preflight gate."
        ),
        "profiles": profiles,
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()