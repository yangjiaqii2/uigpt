-- 修复流式对话报错：Unknown column 'base_url' in 'field list'
-- 原因：JPA 实体 ChatModel 已含 base_url，旧库 chat_models 表未执行增量迁移。
-- 使用前请将 USE 后的库名改成你的库（默认 uigpt）。
--
-- 若本语句报错「Unknown column 'api_key_cipher'」，请先执行 docs/migrate-incremental-columns.sql
-- 中 chat_models 相关段落，或先把 AFTER 子句改为：AFTER sort_order

USE uigpt;

ALTER TABLE chat_models
  ADD COLUMN base_url VARCHAR(512) DEFAULT NULL COMMENT 'OpenAI 兼容对话 Base URL（须含 /v1）；空则用全局 uigpt.ai.base-url'
  AFTER api_key_cipher;
