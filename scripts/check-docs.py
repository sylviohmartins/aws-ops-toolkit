"""Check local Markdown links and fenced blocks without network access or dependencies."""
from pathlib import Path
import re
import sys
from urllib.parse import unquote

root = Path(__file__).resolve().parent.parent
files = list((root / "docs").rglob("*.md")) + list(root.glob("*.md"))
errors = []
for path in files:
    text = path.read_text(encoding="utf-8-sig")
    if len(re.findall(r"^```", text, flags=re.MULTILINE)) % 2:
        errors.append(f"{path.relative_to(root)}: unbalanced fenced blocks")
    for target in re.findall(r"\]\(([^)]+)\)", text):
        target = target.strip().strip("<>").split("#", 1)[0]
        if not target or re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target):
            continue
        if not (path.parent / unquote(target)).exists():
            errors.append(f"{path.relative_to(root)}: missing {target}")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"DOCS PASS: {len(files)} Markdown files, local links and fenced blocks.")
