# 环境与部署

1. 安装 JDK 17、Maven、Node.js 18+、MySQL 8+。
2. 执行 `docs/schema-mysql.sql` 创建数据库与**全部业务表**（含知识库、图片工作台会话、站内信等；勿再执行已移除的 `backend/src/main/resources/db/*.mysql.sql` 拆脚本）。
3. 配置后端环境变量：`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`（及可选 `DB_HOST`）、`UIGPT_JWT_SECRET`、对话密钥（如 `DASHSCOPE_API_KEY`；访客未登录对话可单独配置 `QIANWEN_API_KEY`，见 `application.yml` 的 `uigpt.guest-chat`）等；`backend/config/uigpt-local.yml` 勿写入数据库密码（见根目录 `README.md`）。
4. 启动 `backend` 与 `frontend`；开发时前端通过 Vite 将 `/api` 代理到 `http://localhost:8088`。

生产构建：`cd frontend && npm run build`，部署 `frontend/dist` 并反向代理 `/api` 到后端。

## MySQL 与数据库连接（运维排查）

前端与接口对连接类问题仅提示「网络异常，请稍后再试」；详细原因请看**后端日志**。部署时请自行确认：

1. **MySQL 已启动**，与后端**网络互通**（防火墙 / 安全组放行数据库端口）。
2. 环境变量 **`DB_URL`**（或 **`DB_HOST`、`DB_PORT`、`DB_NAME`**）及 **`DB_USERNAME`、`DB_PASSWORD`** 与实例一致；密码为空时勿遗漏配置项。
3. 已执行 **`docs/schema-mysql.sql`** 创建库表（含 **`chat_conversations`、`chat_messages`** 等）。若是**已有库升级**，请再执行 **`docs/migrate-incremental-columns.sql`**（可重复执行），否则会缺列（例如 **`studio_channel`**）导致会话列表 500。
4. **Docker** 内后端访问宿主机 MySQL 时，主机名常用 **`host.docker.internal`**（Linux 需额外配置）；在 **`server.env`**（或等价挂载配置）中写入正确账号密码并重启容器。
