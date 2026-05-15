# 容器镜像构建与推送（腾讯云 TCR）

镜像地址：

| 组件 | 完整镜像名 |
|------|------------|
| 前端 | `uigpt.tencentcloudcr.com/uigpt/myapp-frontend` |
| 后端 | `uigpt.tencentcloudcr.com/uigpt/uigpt-backend` |

## 1. 登录腾讯云镜像仓库

在 [控制台](https://console.cloud.tencent.com/tcr) 获取登录指令，一般为：

```bash
docker login uigpt.tencentcloudcr.com --username <腾讯云账号ID>
```

按提示输入密码（或临时令牌）。

### 推送时出现 `denied: requested access to the resource is denied`

构建成功但 **`docker push` 被拒绝**，通常是 **未登录、令牌过期、无推送权限或仓库路径不对**，可按下面排查：

1. **重新登录**：`docker logout uigpt.tencentcloudcr.com`，再在 [TCR 控制台](https://console.cloud.tencent.com/tcr) 复制 **登录指令** 或生成 **访问凭证（临时密码）**，执行 `docker login uigpt.tencentcloudcr.com --username <账号ID>`。
2. **用户名**：一般为腾讯云 **主账号 ID（纯数字）**；使用 **子账号** 时，需在访问管理 CAM 中为该子账号授予容器镜像服务 **推送镜像** 等相关权限。
3. **命名空间与仓库名**：控制台里必须先存在命名空间 **`uigpt`**，其下创建镜像仓库 **`myapp-frontend`**、**`uigpt-backend`**（须与本脚本中的路径一致）；首次推送前仓库可为空，但 **名称不能错**。
4. **实例域名**：确认登录、推送使用的域名与控制台当前实例 **登录域名** 完全一致（例如 `uigpt.tencentcloudcr.com`）。

单独验证推送权限：`docker push uigpt.tencentcloudcr.com/uigpt/myapp-frontend:latest`（会先校验权限）。

## 2. 构建并推送

在 **仓库根目录**（`uigpt/`）执行：

```bash
chmod +x docker/build-push.sh
./docker/build-push.sh
```

默认标签为 `latest`。指定版本：

```bash
TAG=v1.0.0 ./docker/build-push.sh
```

## 3. 运行时说明

### 后端

通过 **环境变量** 注入配置（勿把密钥打进镜像），常用项：

- `UIGPT_JWT_SECRET`（UTF-8 至少约 32 字节）
- `DB_URL` 或 `SPRING_DATASOURCE_*`、`DB_USERNAME`、`DB_PASSWORD`
- **Redis**：`docker-compose.server.yml` 已包含 `redis` 服务；`docker/redis-default.env` 与**后端镜像内**默认 `REDIS_HOST=redis`（与 compose 服务名一致）。使用托管 Redis 或宿主机 Redis 时在 `server.env` 写 `REDIS_HOST` / `SPRING_DATA_REDIS_PASSWORD` 等覆盖
- `DASHSCOPE_API_KEY`（或 `config/runtime-local.properties` 需自行挂载）

### 前端（Nginx）

容器内：**前端 Nginx 监听 `8080`**，默认把 `/api` 反向代理到 **`http://uigpt-backend:8088`**（后端容器内需监听 `8088`，与 `application.yml` 一致）。

宿主机映射示例（域名走 80 时，把宿主机 **80** 映到容器内 Nginx **8080**）：

```bash
docker run -p 80:8080 ...     # 前端（浏览器用 http://域名 即可）
docker run -p 8088:8088 ...   # 后端
```

`docker-compose.server.yml` 中前端已默认 **`80:8080`**。若本机 80 已被 Nginx/其它服务占用，可改回 `8080:8080`，再用宿主机 Nginx 把 80 反代到 8080。

若后端地址不同，覆盖：

```bash
docker run -e BACKEND_HOST=你的后端主机或K8s服务名 -e BACKEND_PORT=8088 -p 80:8080 ...
```

## 4. 本地联调（可选）

同一 Docker 网络内后端服务名需为 **`uigpt-backend`**，且容器内监听 **`8088`**，前端 Nginx（**`8080`**）才能默认把 `/api` 代理过去；若改名请同步设置环境变量 **`BACKEND_HOST`** / **`BACKEND_PORT`**。

## 5. WSL2：`no such host` / `NXDOMAIN`（域名实际有效）

访问域名为 **`uigpt.tencentcloudcr.com`** 时，若在 WSL 里 `docker login` 报 **lookup … no such host**，多半是 **WSL 默认 DNS（常见 `10.255.255.254`）对该域名返回 NXDOMAIN**，而公网 DNS（如 `223.5.5.5`、`8.8.8.8`）可以正常解析。

可先验证：

```bash
nslookup uigpt.tencentcloudcr.com 223.5.5.5
```

**临时修复（当前 WSL 会话）：**

```bash
sudo sh -c 'printf "nameserver 223.5.5.5\nnameserver 8.8.8.8\n" > /etc/resolv.conf'
```

然后再执行 `docker login uigpt.tencentcloudcr.com …`。

**长期**：在 WSL 中配置 [`/etc/wsl.conf`](https://learn.microsoft.com/zh-cn/windows/wsl/wsl-config) 关闭自动生成 `resolv.conf` 后自管该文件，或调整 Windows 侧 DNS，避免转发器错误解析 `*.tencentcloudcr.com`。

---

## 6. 服务器：拉取镜像并启动

### 6.1 登录仓库（每台服务器做一次）

```bash
docker login uigpt.tencentcloudcr.com --username <腾讯云账号ID>
```

### 6.2 推荐：Docker Compose（前后端一起）

在仓库里已有编排文件 `docker/docker-compose.server.yml`，默认：

- 前端：**宿主机 `80`** → 容器内 **`8080`**（Nginx），`/api` 代理到 **`uigpt-backend:8088`**
- 后端：**宿主机 `8088`** → 容器 `8088`

**步骤：**

1. 把本目录下的 `server.env.example` 复制为 **`docker/server.env`**（或与 compose 同目录的 **`server.env`**），填好必填项；**`UIGPT_OAUTH_FRONTEND_REDIRECT`** 应与浏览器地址一致（走 80 时一般为 `https://你的域名/auth/callback`，无需 `:8080`）。
2. 确保 MySQL 已执行 `docs/schema-mysql.sql`，且 **`DB_URL` 里的主机从容器内可达**（云数据库内网地址、或宿主机 IP；在同一台机上的 MySQL 可用宿主机内网 IP，一般不要用 `127.0.0.1`，除非用 `network_mode: host` 等特殊网络）。
3. 在 **仓库根目录**（`uigpt/`）执行：

```bash
docker compose -f docker/docker-compose.server.yml pull
docker compose -f docker/docker-compose.server.yml up -d
```

查看日志：

```bash
docker compose -f docker/docker-compose.server.yml logs -f
```

更新镜像后：

```bash
docker compose -f docker/docker-compose.server.yml pull
docker compose -f docker/docker-compose.server.yml up -d
```

停止：

```bash
docker compose -f docker/docker-compose.server.yml down
```

### 6.3 不用 Compose：两条 `docker run`

后端（环境变量按实际增减）：

```bash
docker pull uigpt.tencentcloudcr.com/uigpt/uigpt-backend:latest
docker run -d --name uigpt-backend -p 8088:8088 \
  -e UIGPT_JWT_SECRET='你的JWT密钥' \
  -e DB_URL='jdbc:mysql://...' \
  -e DB_USERNAME='...' -e DB_PASSWORD='...' \
  -e DASHSCOPE_API_KEY='...' \
  -e UIGPT_OAUTH_FRONTEND_REDIRECT='https://你的域名/auth/callback' \
  --restart unless-stopped \
  uigpt.tencentcloudcr.com/uigpt/uigpt-backend:latest
```

前端（须与后端在同一 Docker 网络，且能通过主机名 `uigpt-backend` 访问后端；否则自建网络并 `--network`）：

```bash
docker network create uigpt-net
docker run -d --name uigpt-backend ... --network uigpt-net ...
docker pull uigpt.tencentcloudcr.com/uigpt/myapp-frontend:latest
docker run -d --name myapp-frontend -p 80:8080 \
  --network uigpt-net \
  -e BACKEND_HOST=uigpt-backend -e BACKEND_PORT=8088 \
  --restart unless-stopped \
  uigpt.tencentcloudcr.com/uigpt/myapp-frontend:latest
```

浏览器访问：**`http://服务器IP`** 或 **`https://你的域名`**（前端映射 **80→8080**），API 走同端口下的 **`/api`**。

## 7. 线上提示「数据库连接或权限异常」

后端日志里通常有更具体的英文/MySQL 报错（如 `Communications link failure`、`Access denied`、`Unknown database`）。可按下面排查：

1. **`server.env` 是否被 Compose 加载**：与 `docker-compose.server.yml` 同目录须有 **`server.env`**，且 `env_file` 路径正确；改完后执行 `docker compose ... up -d` **重建**后端容器。
2. **`DB_URL` 里的主机**：在 **后端容器内** 必须能连上 MySQL。**禁止写 `127.0.0.1`**（那只指向容器自身）。MySQL 在宿主机、端口如 `13306` 时，请写 **`host.docker.internal:13306`**（本仓库 compose 已为后端加上 `extra_hosts: host.docker.internal:host-gateway`）。云数据库写内网地址；同 Compose 里的 MySQL 写 **服务名**。
3. **MySQL 监听地址**：若库只绑定 `127.0.0.1`，从容器经宿主机 IP 访问可能被拒绝，需改为监听 `0.0.0.0` 或把 MySQL 也放进 Compose 网络。
4. **账号密码与库名**：用户是否有远程权限、`uigpt` 库是否已创建（执行过 `schema-mysql.sql`）。
5. **安全组 / 防火墙**：若 MySQL 在另一台机器或云上，是否放行 **3306**（或实际端口）给后端服务器。
6. **进容器自测**：`docker exec -it uigpt-backend sh`，可用 `wget -qO-`/`nc` 测 `host.docker.internal:端口`（镜像若无工具可临时 `apk add` 或装客户端）。
