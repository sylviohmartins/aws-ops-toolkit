from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "src" / "main" / "java"
METADATA = ROOT / "src" / "main" / "resources" / "META-INF" / "additional-spring-configuration-metadata.json"
EXAMPLE = ROOT / "examples" / "application-example.yml"

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def java_files() -> list[Path]:
    return list(MAIN.rglob("*.java"))


def yaml_has_path(document: object, dotted: str) -> bool:
    current = document
    for part in dotted.split("."):
        if not isinstance(current, dict) or part not in current:
            return False
        current = current[part]
    return True

metadata = json.loads(METADATA.read_text(encoding="utf-8"))
example = yaml.safe_load(EXAMPLE.read_text(encoding="utf-8"))
catalog = (ROOT / "docs/34-configuration-catalog.md").read_text(encoding="utf-8")
for prop in metadata["properties"]:
    name = prop["name"]
    if not yaml_has_path(example, name):
        fail(f"application-example.yml missing property: {name}")
    if f"| {name} |" not in catalog:
        fail(f"configuration catalog missing property: {name}")

for source in java_files():
    text = source.read_text(encoding="utf-8")
    if re.search(r"@Value\s*\(", text):
        fail(f"@Value is forbidden in main code: {source.relative_to(ROOT)}")
    if re.search(r"\barn:aws(?:-[a-z]+)*:", text):
        fail(f"hardcoded AWS ARN in main code: {source.relative_to(ROOT)}")
    if "amazonaws.com" in text:
        fail(f"hardcoded AWS endpoint in main code: {source.relative_to(ROOT)}")
    if re.search(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b", text):
        fail(f"credential-like access key in main code: {source.relative_to(ROOT)}")

for forbidden in (
    "Utils",
    "CommonUtils",
    "GeneralUtils",
    "Helper",
    "ApplicationUtils",
    "GlobalUtils",
    "Constants",
    "GlobalConstants",
    "ApplicationConstants",
):
    matches = list(MAIN.rglob(f"{forbidden}.java"))
    for match in matches:
        fail(f"generic utility/constants class is forbidden: {match.relative_to(ROOT)}")

required_docs = [
    "docs/33-reuse-extensibility.md",
    "docs/34-configuration-catalog.md",
    "docs/35-complement-requirement-map.md",
    "docs/development/adding-operation.md",
    "docs/development/adding-dynamodb-table.md",
    "docs/development/adding-integration.md",
    "docs/development/adding-report.md",
]
for relative in required_docs:
    if not (ROOT / relative).is_file():
        fail(f"required complement documentation missing: {relative}")

reuse_doc = (ROOT / "docs/33-reuse-extensibility.md").read_text(encoding="utf-8")
for letter, title in (
    ("A", "Reuse Strategy"),
    ("B", "Utility Inventory"),
    ("C", "Collection & Batch Processing"),
    ("D", "Report Infrastructure"),
    ("E", "Configuration Standards"),
    ("F", "Configuration Catalog"),
    ("G", "Constants & Magic Values Policy"),
    ("H", "Model Separation Strategy"),
    ("I", "Mapping Strategy"),
    ("J", "DynamoDB Table Extension Model"),
    ("K", "Maven Dependency Management Strategy"),
    ("L", "Naming Conventions"),
    ("M", "Change Impact Matrix"),
    ("N", "Extension Recipes"),
    ("O", "Anti-patterns"),
    ("P", "Final Simplification Review"),
):
    if f"## {letter}. {title}" not in reuse_doc:
        fail(f"missing complement section {letter}: {title}")

required_types = [
    "batch/Batching.java",
    "batch/BatchProcessor.java",
    "report/CsvReportWriter.java",
    "dynamodb/DynamoTableGateway.java",
    "dynamodb/DynamoTableDescriptor.java",
    "security/Masking.java",
    "security/Hashing.java",
]
base = MAIN / "io" / "github" / "awsopstoolkit"
for relative in required_types:
    if not (base / relative).is_file():
        fail(f"required reusable component missing: {relative}")

pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
for marker in (
    "<dependencyManagement>",
    "<pluginManagement>",
    "software.amazon.awssdk",
    "spring-boot-configuration-processor",
    "maven-dependency-plugin",
):
    if marker not in pom:
        fail(f"pom.xml missing governance marker: {marker}")

if errors:
    print("COMPLEMENT CHECK FAILED")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print(
    "COMPLEMENT CHECK PASS: "
    f"{len(metadata['properties'])} properties covered; "
    f"{len(required_docs)} required docs present; "
    f"{len(required_types)} reusable components present; "
    "no generic utils/@Value/hardcoded AWS credentials/resources detected."
)
