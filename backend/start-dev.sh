#!/usr/bin/env bash
# 从本目录加载 .env 后启动 Spring Boot（.env 勿提交仓库）
cd "$(dirname "$0")"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "[start-dev] 已加载 $(pwd)/.env"
else
  echo "[start-dev] 未找到 .env：请执行 cp .env.example .env 并填写密钥（可选仍继续启动）" >&2
fi
exec mvn spring-boot:run "$@"
