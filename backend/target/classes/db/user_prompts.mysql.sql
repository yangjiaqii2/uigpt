-- 用户自定义提示词（与 spring.jpa.hibernate.ddl-auto=none 配合，部署后手动执行一次）。
-- 已废弃：产品改为全局表 prompt_templates（见 prompt_templates.mysql.sql）。保留脚本仅便于旧库对照。
CREATE TABLE IF NOT EXISTS user_prompts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT 'FK users.id',
  title VARCHAR(512) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT fk_user_prompts_user FOREIGN KEY (user_id) REFERENCES users (id),
  KEY idx_user_prompts_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
