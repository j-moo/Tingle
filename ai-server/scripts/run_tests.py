"""Run the Python suite, Node regressions and parity from any working directory."""

import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    node = shutil.which("node")
    if node is None:
        print("Node.js is required for AI regressions and JS/Python parity", file=sys.stderr)
        return 1
    node_tests = sorted((root / "tests/node").glob("*.test.mjs"))
    if not node_tests:
        print("No Node regression tests found in tests/node", file=sys.stderr)
        return 1

    commands = [
        [sys.executable, "-m", "pytest", "-q"],
        [node, "--test", *map(str, node_tests)],
        [sys.executable, "-m", "scripts.check_personal_parity"],
    ]
    for command in commands:
        print(f"Running in {root}: {' '.join(command)}", flush=True)
        completed = subprocess.run(command, cwd=root, check=False)
        if completed.returncode:
            return completed.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
