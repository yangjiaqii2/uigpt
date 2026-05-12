# UI GPT

前后端分离：Vue 3 + Vite 前端，Spring Boot 3 + MySQL 后端。支持**注册 / 账号密码登录**，以及 OpenAI 兼容的对话接口。

## 结构

- `frontend/`：Vue 3，默认开发端口 `5173`
- `backend/`：Spring Boot，默认端口 `8088`
- `docs/schema-mysql.sql`：MySQL 建表脚本
- `docs/setup.md`：环境说明

## 数据库

1. 在 MySQL 上执行 `docs/schema-mysql.sql` 创建库表。
2. **端口、库名、账号、密码必须在环境变量中配置**（由 `application.yml` 的 `${...}` 引用，勿提交到 Git）。可选 `DB_HOST`，默认 `localhost`。
3. 若使用**管理端知识库**（向量条目与列表）：在 MySQL 额外执行 `backend/src/main/resources/db/knowledge_documents.mysql.sql`（与运行时 classpath 资源 `db/knowledge_documents.mysql.sql` 相同）。

## 知识库（向量检索 / RAG）

- **架构**：已取消「管理端列表」与 MySQL 之间的单独同步任务；列表与详情直接查询 `knowledge_documents` 表。每次新增或导入时，服务端在**同一保存流程**内先调用 embedding 并将向量 **upsert 至 Qdrant**，再将元数据写入 MySQL；若 MySQL 写入失败，会尝试删除刚写入的 Qdrant 点以避免孤儿向量。
- **对话检索**：需在后端开启并配全 RAG 相关项（见下表）；否则对话不会注入知识库上下文，管理端写入也会因未配全而失败。

| 环境变量 | 说明 |
|----------|------|
| `UIGPT_RAG_ENABLED` | `true` 时启用 RAG（须同时配全 Qdrant、embedding URL 与密钥、集合） |
| `UIGPT_RAG_QDRANT_URL` | Qdrant HTTP 根地址，如 `http://localhost:6333` |
| `UIGPT_RAG_QDRANT_API_KEY` | Qdrant API Key；本地可空 |
| `UIGPT_RAG_COLLECTION` | 集合名，默认 `uigpt_kb` |
| `UIGPT_RAG_EMBEDDING_BASE_URL` | OpenAI 兼容 embeddings 根 URL（须含 `/v1`） |
| `UIGPT_RAG_EMBEDDING_API_KEY` | 调用 embedding 的 Bearer；未设时可回退 `APIYI_API_KEY` / `UIGPT_APIYI_API_KEY`（见 `application.yml`） |
| `UIGPT_RAG_EMBEDDING_MODEL` | 如 `text-embedding-3-small` |
| `UIGPT_RAG_TOP_K` | 单次对话检索条数，默认 `5` |
| `UIGPT_RAG_MIN_SCORE` | 最低相似度阈值，默认 `0` |
| `UIGPT_RAG_MAX_QUERY_CHARS` | 用于 embedding 的问题最大字符数，默认 `8000` |
| `UIGPT_RAG_READ_TIMEOUT_SECONDS` | 访问 Qdrant 与 embedding 的读超时（秒），默认 `45` |

对应 YAML 键为 `uigpt.rag.*`，详见 `backend/src/main/resources/application.yml`。

## 环境变量（后端）

| 变量 | 说明 |
|------|------|
| `UIGPT_JWT_SECRET` | JWT 密钥，UTF-8 至少 32 字节 |
| `DB_PORT` | **必填**，MySQL 端口，如 `3306` |
| `DB_NAME` | **必填**，数据库名，如 `uigpt` |
| `DB_USERNAME` | **必填**，数据库用户名 |
| `DB_PASSWORD` | 数据库密码；无密码时设为空字符串或省略（等价空） |
| `DB_HOST` | 可选，默认 `localhost` |
| `DASHSCOPE_API_KEY` 或 `QWEN_API_KEY` | **通义千问**（DashScope）API Key，推荐；与 `OPENAI_API_KEY` 三选一即可 |
| `OPENAI_API_KEY` | 其它 OpenAI 兼容服务密钥；未配置任一则对话返回 503 |
| `AI_BASE_URL` / `OPENAI_BASE_URL` | 默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`；用 OpenAI 时改为 `https://api.openai.com/v1` |
| `AI_MODEL` / `OPENAI_MODEL` | 默认 `qwen-turbo`；如 `qwen-plus`、`qwen-max` |

**注册防护（推荐生产开启）**

| 变量 | 说明 |
|------|------|
| `UIGPT_RECAPTCHA_ENABLED` | `true` 时对注册校验 Google **reCAPTCHA v3** |
| `UIGPT_RECAPTCHA_SITE_KEY` | v3 站点密钥（前端通过 `/api/register/options` 获取） |
| `UIGPT_RECAPTCHA_SECRET_KEY` | v3 服务端密钥（勿泄露） |
| `UIGPT_RECAPTCHA_MIN_SCORE` | 可选，默认 `0.5`（0～1，越高越严） |
| `UIGPT_REGISTER_MAX_PER_HOUR` | 可选，默认 `2`，同一 IP 滚动 1 小时内最多成功注册次数 |
| `UIGPT_REGISTER_MAX_PER_24H` | 可选，默认 `3`，同一 IP 滚动 24 小时内最多成功注册次数 |

注册还须通过**图形验证码**：前端调用 `GET /api/register/captcha` 取得 `captchaId` 与 PNG 的 Base64 展示图片，提交 `POST /api/register` 时携带 `captchaId`、`captchaCode`（校验成功或失败后均需重新拉取图片）。

注册频率限制在**进程内存**中统计；多实例部署时请改为 Redis 等共享存储或前置网关限流。反向代理后若要准确取客户端 IP，可为 Spring 配置 `server.forward-headers-strategy: framework`（或等价），并正确设置 `X-Forwarded-For`。

`backend/config/uigpt-local.yml` 仅用于 JWT 等非数据库项；**不要在 YAML 里写数据库账号密码**。

## 启动

```bash
# 1. 配置环境变量：DB_PORT、DB_NAME、DB_USERNAME、DB_PASSWORD、UIGPT_JWT_SECRET 等；可选 uigpt-local.yml（不含 DB 密码）
cd backend && mvn spring-boot:run

cd frontend && npm install && npm run dev
```

浏览器访问：<http://localhost:5173>。登录页可注册与账号密码登录。

## 生产构建

```bash
cd frontend && npm run build
```

将 `frontend/dist` 由 Nginx 等托管；`/api` 反向代理到 Java。生产环境请按域名收紧 CORS（`WebConfig`）。

**数据库与模型密钥示例（勿把真实密码写入仓库）：**

```bash
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=uigpt
export DB_USERNAME=root
export DB_PASSWORD='你的数据库密码'

export UIGPT_JWT_SECRET='至少三十二字节的随机串'
export DASHSCOPE_API_KEY='你的DashScope密钥'
# 可选：export AI_MODEL=qwen-plus

cd backend && mvn spring-boot:run
```

若 API Key 曾出现在不受信环境，请在阿里云 DashScope 控制台**重新生成/禁用旧 Key**。

## 常见错误

- **无法启动 / IllegalStateException**：未配置 `UIGPT_JWT_SECRET` 或密钥过短。
- **无法启动 / Could not resolve placeholder**：未设置必填环境变量 `DB_PORT`、`DB_NAME` 或 `DB_USERNAME`。
- **无法连接数据库**：检查 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD` 是否与 MySQL 实际一致。
- **对话 503**：未配置 `DASHSCOPE_API_KEY`、`QWEN_API_KEY` 与 `OPENAI_API_KEY` 中任一项。
- **前端 401**：令牌无效或过期。
