# syntax=docker/dockerfile:1
# 构建阶段：官方 Maven + Temurin 17（避免 apk install maven 再拉一整套 OpenJDK）
FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# 默认使用 docker/mvn-settings.xml 中的国内镜像；海外构建可加 --build-arg USE_MAVEN_MIRROR=false
ARG USE_MAVEN_MIRROR=true
COPY docker/mvn-settings.xml /tmp/mvn-settings.xml
COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,target=/root/.m2/repository \
    sh -c 'set -e; \
      if [ "$USE_MAVEN_MIRROR" = "true" ]; then \
        mvn -q -B -s /tmp/mvn-settings.xml -DskipTests package; \
      else \
        mvn -q -B -DskipTests package; \
      fi; \
      mv target/*.jar /build/app.jar'

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /build/app.jar /app/app.jar
USER app
# 与 docker-compose 中 Redis 服务名一致；单独 docker run / K8s 可用 -e REDIS_HOST=… 覆盖；本机 mvn 运行不受此影响
ENV REDIS_HOST=redis
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
