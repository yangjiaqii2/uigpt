# syntax=docker/dockerfile:1
FROM node:22-alpine AS builder
WORKDIR /app

# 可选：npm 国内镜像，例如 https://registry.npmmirror.com（build-push.sh 里可 export NPM_REGISTRY）
ARG NPM_REGISTRY=""
COPY package.json package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    sh -c 'set -e; \
      if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi; \
      npm install --no-audit --no-fund'

COPY . .
# 与镜像 tag 对齐时：docker build --build-arg VITE_APP_VERSION=v4.1 ...
ARG VITE_APP_VERSION=""
ENV VITE_APP_VERSION=$VITE_APP_VERSION
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV BACKEND_HOST=uigpt-backend
ENV BACKEND_PORT=8088
EXPOSE 8080
