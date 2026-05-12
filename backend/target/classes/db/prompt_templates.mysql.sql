-- 全局提示词模板（所有已登录用户可读；增删改仅 DB privilege=2 超级管理员，见 AdminPromptTemplateController）
CREATE TABLE IF NOT EXISTS prompt_templates (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  title      VARCHAR(512) NOT NULL,
  body       MEDIUMTEXT   NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_prompt_templates_updated (updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='全局提示词';

-- 旧表 user_prompts 已废弃；若需迁移数据，可手工将去重后的行插入本表，并将相关账号 users.privilege 设为 2 以管理模板。
