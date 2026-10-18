#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENV_DIR="${ROOT_DIR}/.venv"

# Ensure virtualenv exists and dependencies are installed
if [ ! -d "${VENV_DIR}" ]; then
    echo "Creating virtual environment at ${VENV_DIR}..."
    python3 -m venv "${VENV_DIR}"
    "${VENV_DIR}/bin/pip" install -r "${SCRIPT_DIR}/requirements.txt"
elif [ ! -f "${VENV_DIR}/bin/uvicorn" ]; then
    echo "Installing requirements into ${VENV_DIR}..."
    "${VENV_DIR}/bin/pip" install -r "${SCRIPT_DIR}/requirements.txt"
fi

HOST="${AGENTPULSE_HOST:-${HOST:-127.0.0.1}}"
PORT="${AGENTPULSE_PORT:-${PORT:-7890}}"

export PYTHONPATH="${SCRIPT_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

EXTRA_ARGS=()
FORWARD_ARGS=()
HAS_HOST=0
HAS_PORT=0

while [ $# -gt 0 ]; do
    case "$1" in
        --home)
            export AGENTPULSE_HOME="$2"
            shift 2
            ;;
        --home=*)
            export AGENTPULSE_HOME="${1#*=}"
            shift
            ;;
        --host)
            HAS_HOST=1
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --host=*)
            HAS_HOST=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --port)
            HAS_PORT=1
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --port=*)
            HAS_PORT=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        *)
            FORWARD_ARGS+=("$1")
            shift
            ;;
    esac
done

if [ "$HAS_HOST" -eq 0 ]; then
    EXTRA_ARGS+=(--host "${HOST}")
fi
if [ "$HAS_PORT" -eq 0 ]; then
    EXTRA_ARGS+=(--port "${PORT}")
fi

exec "${VENV_DIR}/bin/uvicorn" agentpulse_service:app --factory "${EXTRA_ARGS[@]}" "${FORWARD_ARGS[@]}"
