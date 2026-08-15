#!/usr/bin/env python3
"""Print the EPIC 21 automated-test inventory without external dependencies."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
FRONTEND = ROOT / "frontend"


def stems(path: Path, pattern: str):
    return {p.stem for p in path.glob(pattern)} if path.exists() else set()


services = stems(BACKEND / "src/main/java/org/odk/platform/service", "*.java")
service_tests = {
    p.stem.removesuffix("Test")
    for p in (BACKEND / "src/test/java/org/odk/platform/service").glob("*Test.java")
}
controllers = {p.stem for p in (BACKEND / "src/main/java/org/odk/platform").rglob("*Controller.java")}
controller_test_files = list((BACKEND / "src/test/java/org/odk/platform").rglob("*Test.java"))
repositories = stems(BACKEND / "src/main/java/org/odk/platform/repository", "*.java")
repository_tests = list((BACKEND / "src/test/java/org/odk/platform/repository").glob("*Test.java"))
frontend_tests = list((FRONTEND / "src").rglob("*.test.ts")) + list((FRONTEND / "src").rglob("*.spec.ts"))
e2e_tests = list((FRONTEND / "e2e").rglob("*.spec.ts")) if (FRONTEND / "e2e").exists() else []

print("EPIC 21 automated test inventory")
print("=" * 34)
print(f"Service classes: {len(services)}")
print(f"Service test files: {len(service_tests)}")
print(f"Controller classes: {len(controllers)}")
print(f"Controller-related test files: {sum(1 for p in controller_test_files if 'Controller' in p.stem or 'OpenRosa' in p.stem)}")
print(f"Repository interfaces: {len(repositories)}")
print(f"Repository test files: {len(repository_tests)}")
print(f"Frontend unit/component/route tests: {len(frontend_tests)}")
print(f"Playwright e2e specs: {len(e2e_tests)}")

missing_direct = sorted(services - service_tests)
print("\nService classes without a same-name unit test:")
for name in missing_direct:
    print(f"  - {name}")

print("\nControllers without an obvious controller/OpenRosa test-name match:")
test_names = {p.stem for p in controller_test_files}
for name in sorted(controllers):
    base = name.removesuffix("Controller")
    if not any(name in test or (base and base in test) for test in test_names):
        print(f"  - {name}")

if not e2e_tests:
    print("\nE2E gap: no Playwright specs are present yet.")
