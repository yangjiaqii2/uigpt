# 数据库 DDL（说明）

业务表 **一键初始化** 已合并到仓库根目录：

**`docs/schema-mysql.sql`**

新环境在 MySQL 中执行该文件即可（含用户、对话、模型池、提示词模板、知识库表、图片工作台会话、站内信等）。请勿在本目录再维护重复的 `*.mysql.sql` 拆脚。

**已有老库缺列**：使用 `docs/migrate-incremental-columns.sql`（可重复执行）。
