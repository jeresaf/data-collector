#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${PYXFORM_VENV:-${ROOT_DIR}/.pyxform-venv}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

"${PYTHON_BIN}" -m venv "${VENV_DIR}"
"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${VENV_DIR}/bin/python" -m pip install "pyxform==4.5.0"
"${VENV_DIR}/bin/xls2xform" --help >/dev/null

cat <<MSG
pyxform 4.5.0 is installed at:
  ${VENV_DIR}

Before starting the backend locally, run:
  export PYXFORM_COMMAND="${VENV_DIR}/bin/xls2xform"

Then verify the optional Maven smoke test with:
  cd "${ROOT_DIR}/backend"
  mvn -Dtest=PyxformConverterIntegrationTest test
MSG
