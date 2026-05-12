#!/usr/bin/env bash
set -euo pipefail
cd /home/dataease/uigpt/backend
set -a && source .env.db && set +a
exec mvn spring-boot:run "$@"
