#!/usr/bin/env bash
# 在仓库根目录执行：./docker/build-push.sh
#
# 加速建议（大陆网络）：
#   export DOCKER_BUILDKIT=1   # 默认已开启；启用 Maven/npm 缓存挂载
#   export NPM_REGISTRY=https://registry.npmmirror.com
#   # 后端 Maven 默认使用 backend/docker/mvn-settings.xml（腾讯云 Maven）；海外可加：
#   export USE_MAVEN_MIRROR=false
#
# 可选：TAG=v2.0.0 ./docker/build-push.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export DOCKER_BUILDKIT=1

REGISTRY="${REGISTRY:-krccr.ccs.tencentyun.com}"
FRONTEND_REPO="${FRONTEND_REPO:-uigpt/frontend}"
BACKEND_REPO="${BACKEND_REPO:-uigpt/backend}"
TAG="${TAG:-latest}"

FRONTEND_IMG="${REGISTRY}/${FRONTEND_REPO}:${TAG}"
BACKEND_IMG="${REGISTRY}/${BACKEND_REPO}:${TAG}"

FE_BUILD_ARGS=()
if [[ -n "${NPM_REGISTRY:-}" ]]; then
  FE_BUILD_ARGS+=(--build-arg "NPM_REGISTRY=${NPM_REGISTRY}")
fi

BE_BUILD_ARGS=()
if [[ "${USE_MAVEN_MIRROR:-true}" == "false" ]]; then
  BE_BUILD_ARGS+=(--build-arg "USE_MAVEN_MIRROR=false")
fi

echo ">>> 构建镜像: ${FRONTEND_IMG}"
docker build "${FE_BUILD_ARGS[@]}" -t "${FRONTEND_IMG}" ./frontend

echo ">>> 构建镜像: ${BACKEND_IMG}"
docker build "${BE_BUILD_ARGS[@]}" -t "${BACKEND_IMG}" ./backend

echo ">>> 推送（需已 docker login ${REGISTRY}）"
docker push "${FRONTEND_IMG}"
docker push "${BACKEND_IMG}"

echo "完成: ${FRONTEND_IMG} , ${BACKEND_IMG}"
