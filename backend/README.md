# uigpt-backend

Spring Boot 3.3，JDK 17，Maven。主类：`top.uigpt.UigptApplication`。

## 接口摘要

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/register/captcha` | 图形验证码，`{ captchaId, imageBase64 }`（PNG Base64） |
| GET | `/api/register/options` | 注册页选项（如是否启用 reCAPTCHA、站点密钥） |
| POST | `/api/register` | 注册：`realName`、`phone`、`username`、`password`、`confirmPassword`、`captchaId`、`captchaCode`、可选 `recaptchaToken`；按 IP 限流 |
| POST | `/api/login` | 登录 |
| POST | `/api/forgot-password/reset` | 忘记密码：核验姓名、手机号、`registeredDate`(yyyy-MM-DD) 与创建日后重置密码 |
| GET | `/api/me` | 当前用户，`Authorization: Bearer` |
| POST | `/api/chat` | 对话（需登录） |

建表脚本见仓库根目录 `docs/schema-mysql.sql`。

数据源在 `application.yml` 中通过环境变量 `DB_HOST`（可选）、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`（可为空）注入；勿在 YAML 中写数据库密码。其它项见可选 `config/uigpt-local.yml`。

## 模型 API Key（库表加密）

表 `chat_models.api_key_cipher` 存放 **AES-256-GCM** 密文（Base64）。当前产品默认经 **APIYi**（`APIYI_API_KEY`）转发自由对话；若未配置 APIYi 密钥，则会话请求会在 `chat_models` 中随机选取一条 **enabled** 的记录（若该行密文非空则用解密后的 Key），否则回退全局 `DASHSCOPE_API_KEY`（等）。

1. 设置与线上一致的主密钥：`export UIGPT_MODEL_KEY_MASTER='<足够长的随机串>'`
2. 生成密文（勿把明文提交仓库）：

```bash
cd backend && mvn -q exec:java -Dexec.mainClass=top.uigpt.cli.EncryptModelApiKeyCli -Dexec.args='你的明文API_Key'
```

3. 将输出的单行写入 MySQL：`UPDATE chat_models SET api_key_cipher='...' WHERE id=1;`
4. 启动后端时同样注入 `UIGPT_MODEL_KEY_MASTER`，否则无法解密。

## APIYi（会话内文生图）

历史会话中若有生成图卡片，重新生成/变体仍走 `POST /api/conversations/{id}/images/ernie-generate`（路径名保留兼容）；服务端固定调用 **APIYi** 文生图并写入 **COS**。JSON 体可选 `imageConversationContext`（近期对话摘录，由前端从消息列表组装），与 `userMessage` / `assistantReply` 一并拼入出图 prompt。局部重绘 `POST .../ernie-inpaint` 可选同名表单字段 `imageConversationContext`。需配置 `APIYI_API_KEY`（或 `UIGPT_APIYI_API_KEY`）及 COS，详见根目录 `application.yml` 中 `uigpt.api-yi-image` / `uigpt.cos`。
