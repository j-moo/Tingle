"""Verify preserved AI documents without requiring the original root docs."""

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-originals", action="store_true",
                        help="Also verify that the original source files are unchanged")
    parser.add_argument("--source-root", type=Path, help="Captured original repository, when different from the current checkout")
    args = parser.parse_args()
    ai_root = Path(__file__).resolve().parents[1]
    repository = ai_root.parent
    source_root = args.source_root.resolve() if args.source_root else repository
    manifest = json.loads((ai_root / "docs/PRESERVATION.json").read_text(encoding="utf-8"))
    errors = []
    seen = set()
    links = 0
    convention_links = 0
    for entry in manifest["files"]:
        relative = entry["preserved"]
        path = (ai_root / relative).resolve()
        if relative in seen or not path.is_relative_to(ai_root):
            errors.append(f"Invalid or duplicate preserved path: {relative}")
            continue
        seen.add(relative)
        if not path.is_file():
            errors.append(f"Missing preserved file: {relative}")
            continue
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            errors.append(f"Preserved content changed: {relative}")
        if args.check_originals:
            source = (source_root / entry["source"]).resolve()
            if not source.is_relative_to(source_root) or not source.is_file():
                errors.append(f"Missing original: {entry['source']}")
            elif hashlib.sha256(source.read_bytes()).hexdigest() != entry["sourceSha256"]:
                errors.append(f"Original content changed: {entry['source']}")
        if path.suffix != ".md":
            continue
        for raw in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8-sig")):
            url = unquote(raw.strip().strip("<>"))
            if url.startswith("#") or urlsplit(url).scheme:
                continue
            target = (path.parent / url.split("#")[0]).resolve()
            if target.is_relative_to(repository / "docs/convention"):
                convention_links += 1
                continue  # Convention documents remain in the repository root.
            links += 1
            if not target.is_relative_to(ai_root) or not target.exists():
                errors.append(f"Missing or external local link in {relative}: {raw}")
    print(json.dumps({
        "status": "FAIL" if errors else "PASS",
        "preservedFiles": len(seen),
        "localLinks": links,
        "retainedConventionLinks": convention_links,
        "originalsChecked": args.check_originals,
        "errors": errors,
    }, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
