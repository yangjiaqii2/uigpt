#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "[run-dev] 已加载 $(pwd)/.env"
else
  echo "[run-dev] 未找到 .env：请在 $(pwd)/.env 填写（可选仍继续启动）" >&2
fi
exec mvn spring-boot:run "$@"
