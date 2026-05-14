-- =============================================================================
-- UIGPT MySQL 8.x 一键建库（新环境执行本文件即可）
-- 已合并原 backend/src/main/resources/db/ 下各 *.mysql.sql 拆脚，避免多头维护。
-- 极老库缺列请另执行 docs/migrate-incremental-columns.sql（可重复执行）。
-- 请按需修改库名、字符集；连接示例：localhost:3306
-- =============================================================================

CREATE DATABASE IF NOT EXISTS uigpt
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE uigpt;

-- 用户（账号密码注册）
CREATE TABLE users (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL COMMENT '登录名，唯一',
  real_name     VARCHAR(64)           DEFAULT NULL COMMENT '姓名',
  phone         VARCHAR(20)           DEFAULT NULL COMMENT '手机号，唯一',
  password_hash VARCHAR(255)           DEFAULT NULL COMMENT 'BCrypt',
  nickname      VARCHAR(128)           DEFAULT NULL,
  avatar_url    VARCHAR(512)           DEFAULT NULL,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用 2 待审核',
  privilege     TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通 1付费 2超级管理',
  points        INT          NOT NULL DEFAULT 0 COMMENT '当前可用积分',
  points_bonus  INT          NOT NULL DEFAULT 0 COMMENT '管理员日配额加项',
  points_refill_date DATE              DEFAULT NULL COMMENT '上海日历：上次日重置',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username),
  UNIQUE KEY uk_users_phone (phone),
  KEY idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户';

-- 若库已存在、缺少姓名/手机号列，执行（仅需一次）：
-- ALTER TABLE users ADD COLUMN real_name VARCHAR(64) DEFAULT NULL COMMENT '姓名' AFTER username;
-- ALTER TABLE users ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT '手机号' AFTER real_name;
-- ALTER TABLE users ADD UNIQUE KEY uk_users_phone (phone);

-- 旧版曾含 oauth_accounts（微信/支付宝）；若库里仍有该表可自行 DROP。

-- 登录用户的对话会话与消息（访客不落库）
CREATE TABLE chat_conversations (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  user_id         BIGINT       NOT NULL,
  title           VARCHAR(255) NOT NULL DEFAULT '新对话',
  session_memory  MEDIUMTEXT            DEFAULT NULL COMMENT '会话级记忆摘要，注入模型 system',
  pinned_at       DATETIME              DEFAULT NULL COMMENT '置顶时间，NULL 表示未置顶',
  studio_channel  VARCHAR(32)           DEFAULT NULL COMMENT '工作台归档：image-studio / video-studio；普通对话为 NULL',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_conv_user_studio (user_id, studio_channel),
  KEY idx_conv_user_updated (user_id, updated_at),
  CONSTRAINT fk_conv_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='对话会话';

-- 若库已存在、仅缺记忆字段，执行（仅需一次）：
-- ALTER TABLE chat_conversations ADD COLUMN session_memory MEDIUMTEXT NULL COMMENT '会话级记忆摘要' AFTER title;

-- 若库已存在、需工作台对话归档与唯一约束：
--   推荐执行整段 docs/migrate-incremental-columns.sql（含可重复执行的 studio_channel / uk_conv_user_studio）。
-- 或手工执行（仅需一次）：
-- ALTER TABLE chat_conversations ADD COLUMN studio_channel VARCHAR(32) NULL COMMENT 'image-studio / video-studio' AFTER pinned_at;
-- ALTER TABLE chat_conversations ADD UNIQUE KEY uk_conv_user_studio (user_id, studio_channel);

CREATE TABLE chat_messages (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  conversation_id  BIGINT       NOT NULL,
  role             VARCHAR(16)  NOT NULL,
  content          MEDIUMTEXT   NOT NULL,
  sort_order       INT          NOT NULL DEFAULT 0,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_msg_conv_sort (conversation_id, sort_order),
  CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话内消息';

-- 会话内生成的图片（MinIO 等对象存储 URL），按 message_sort_order 与会话消息对齐
CREATE TABLE IF NOT EXISTS chat_conversation_images (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  conversation_id    BIGINT                DEFAULT NULL COMMENT '所属会话；删除会话后收藏图此项为空',
  user_id            BIGINT       NOT NULL COMMENT '所有者（会话删除后仍用于归属与列表）',
  message_sort_order INT          NOT NULL COMMENT '对应 chat_messages.sort_order',
  skill_id           VARCHAR(32)  NOT NULL COMMENT '技能 id：mockup / wireframe / retouch 等',
  object_key         VARCHAR(512) NOT NULL COMMENT '桶内对象键',
  image_url          VARCHAR(1024) NOT NULL COMMENT '浏览器可访问完整 URL',
  is_favorite        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 收藏',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv_img_conv_created (conversation_id, created_at),
  KEY idx_conv_img_conv_sort (conversation_id, message_sort_order),
  KEY idx_conv_img_user_created (user_id, created_at),
  CONSTRAINT fk_conv_img_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_conv_img_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话生成图';

-- 对话可用模型池（服务端每次请求从 enabled 行中随机选用）；可自行 INSERT/UPDATE 维护
CREATE TABLE IF NOT EXISTS chat_models (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  api_model_code   VARCHAR(128)  NOT NULL COMMENT '上游兼容接口 model，如 qwen-turbo',
  display_name     VARCHAR(128)  NOT NULL COMMENT '界面展示名',
  provider         VARCHAR(32)            DEFAULT NULL COMMENT '如 qwen',
  enabled          TINYINT       NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
  sort_order       INT           NOT NULL DEFAULT 0,
  api_key_cipher   MEDIUMTEXT             DEFAULT NULL COMMENT '模型专用 API Key，AES-GCM 密文 Base64；空则用全局 DASHSCOPE_API_KEY',
  base_url         VARCHAR(512)           DEFAULT NULL COMMENT '对话网关 Base URL（须含 /v1），如 https://api.apiyi.com/v1；空则用全局 uigpt.ai.base-url',
  remark           MEDIUMTEXT             DEFAULT NULL COMMENT '备注说明',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_models_api_code (api_model_code),
  KEY idx_chat_models_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='对话模型配置';

INSERT IGNORE INTO chat_models (api_model_code, display_name, provider, enabled, sort_order, remark)
VALUES (
  'qwen-turbo',
  '千问',
  'qwen',
  1,
  0,
  '默认示例；可在表中添加更多行，api_model_code 须与 DashScope 兼容接口一致'
);

-- API易「高级」对话示例（取消注释并填写 api_key_cipher 或依赖全局 Key）：
-- INSERT IGNORE INTO chat_models (api_model_code, display_name, provider, enabled, sort_order, base_url, remark)
-- VALUES ('gpt-4.1', 'GPT-4.1（API易）', 'apiyi', 1, 10, 'https://api.apiyi.com/v1', '热门模型列表见 docs.apiyi.com');

-- 若库已存在、仅缺模型密钥列，执行（仅需一次）：
-- ALTER TABLE chat_models ADD COLUMN api_key_cipher MEDIUMTEXT NULL COMMENT 'AES-GCM 密文 Base64' AFTER sort_order;

-- 若流式对话报错 Unknown column 'base_url' in 'field list'，执行（仅需一次）：
-- ALTER TABLE chat_models ADD COLUMN base_url VARCHAR(512) DEFAULT NULL COMMENT '对话网关 Base URL（须含 /v1）；空则用全局 uigpt.ai.base-url' AFTER api_key_cipher;
--（亦可直接运行 docs/fix-chat-models-base-url.sql；或执行 docs/migrate-incremental-columns.sql 完整脚本）

-- 全局提示词（GET /api/prompts 任意登录用户；增删改仅 users.privilege=2）
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

-- -----------------------------------------------------------------------------
-- 知识库文档元数据（与 Qdrant 向量 point_id 一致；RagAdmin / RagService）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_documents (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  point_id VARCHAR(36) NOT NULL COMMENT '与 Qdrant 点 id 一致',
  title VARCHAR(512) DEFAULT NULL,
  content MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_knowledge_point_id (point_id),
  KEY idx_knowledge_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 图片工作台「图片会话」（与 chat_conversations 隔离；ddl-auto=none 时依赖本脚本）
-- studio_skill_id：与前端 studioSkillId 一致，如 interior_designer、universal_master
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS image_studio_sessions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  context_text MEDIUMTEXT NULL,
  image_count INT NOT NULL DEFAULT 0,
  last_image_url VARCHAR(1024) NULL,
  studio_skill_id VARCHAR(64) NOT NULL DEFAULT 'interior_designer'
    COMMENT '作图技能：interior_designer=家装，universal_master=全能大师等',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_image_studio_sessions_user_updated (user_id, updated_at),
  CONSTRAINT fk_image_studio_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS image_studio_session_images (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  image_url VARCHAR(1024) NOT NULL,
  user_prompt MEDIUMTEXT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_image_studio_session_images_session_sort (session_id, sort_order),
  CONSTRAINT fk_image_studio_session_images_session
    FOREIGN KEY (session_id) REFERENCES image_studio_sessions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 站内信（普通用户与管理员线程 + 消息）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS site_mail_threads (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '发起方（普通用户）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_site_mail_threads_user (user_id),
  KEY idx_site_mail_threads_updated (updated_at),
  CONSTRAINT fk_site_mail_threads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_mail_messages (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  thread_id BIGINT NOT NULL,
  sender_user_id BIGINT NOT NULL,
  body TEXT NOT NULL,
  image_urls_json VARCHAR(8000) NULL COMMENT 'JSON 数组字符串，元素为可访问的图片 URL',
  read_by_admin TINYINT(1) NOT NULL DEFAULT 0,
  read_by_user TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_site_mail_messages_thread_created (thread_id, created_at),
  CONSTRAINT fk_site_mail_messages_thread FOREIGN KEY (thread_id) REFERENCES site_mail_threads (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
