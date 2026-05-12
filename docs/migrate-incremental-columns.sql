-- =============================================================================
-- uigpt：增量补齐列 / 索引（与 docs/schema-mysql.sql、JPA 实体一致）
-- MySQL 8.x，在 mysql 客户端或 Navicat 等工具中执行整段即可。
-- 可重复执行：已存在的列或索引会自动跳过。
--
-- -----------------------------------------------------------------------------
-- 【与当前后端一致的增量字段一览】执行本文件后即可消除类似错误：
--   Unknown column 'pinned_at' / 'is_favorite' / ...
--
--   表 users
--     · real_name          VARCHAR(64) NULL
--     · phone              VARCHAR(20) NULL  + 唯一索引 uk_users_phone
--     · privilege          TINYINT NOT NULL DEFAULT 0（缺列会报 Unknown column privilege）
--     · points             INT NOT NULL DEFAULT 0
--     · points_bonus       INT NOT NULL DEFAULT 0
--     · points_refill_date DATE NULL
--
--   表 chat_conversations
--     · session_memory     MEDIUMTEXT NULL    （会话记忆摘要）
--     · pinned_at          DATETIME NULL      （侧栏置顶时间；缺此列会触发 InvalidDataAccessResourceUsageException）
--     · studio_channel     VARCHAR(32) NULL   （工作台归档 image-studio / video-studio；缺列会报 Unknown column studio_channel）
--     · uk_conv_user_studio (user_id, studio_channel) 唯一索引
--
--   表 chat_models
--     · api_key_cipher     MEDIUMTEXT NULL    （模型专用 Key 密文）
--     · base_url           VARCHAR(512) NULL   （对话网关，含 /v1；API易 高级模型用）
--
--   表 chat_conversation_images（若不存在则 CREATE；若表已有则仅补列）
--     · 整表见下文 CREATE TABLE IF NOT EXISTS
--     · user_id            BIGINT NOT NULL（从会话回填；删会话后收藏图仍归属用户）
--     · conversation_id    可 NULL（删会话后收藏图解绑会话，MinIO 对象保留）
--     · is_favorite        TINYINT NOT NULL DEFAULT 0  （图片收藏）
--
-- 若只想立刻修复 pinned_at，也可在选对库后单独执行（session_memory 已存在时用 AFTER session_memory；
-- 若没有 session_memory，可改为 AFTER title）：
--   ALTER TABLE chat_conversations ADD COLUMN pinned_at DATETIME NULL COMMENT '置顶时间' AFTER title;
--
-- 使用前请修改库名（若你不是 uigpt）：
USE uigpt;

-- -----------------------------------------------------------------------------
-- users：姓名、手机号、手机号唯一索引（忘记密码等依赖 phone + created_at）
-- 若 ADD UNIQUE 失败，请先检查是否存在重复 phone 或非 NULL 重复：
--   SELECT phone, COUNT(*) c FROM users WHERE phone IS NOT NULL AND phone <> '' GROUP BY phone HAVING c > 1;
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'real_name') = 0,
    'ALTER TABLE users ADD COLUMN real_name VARCHAR(64) DEFAULT NULL COMMENT ''姓名'' AFTER username',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'phone') = 0,
    'ALTER TABLE users ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT ''手机号'' AFTER real_name',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_users_phone') = 0,
    'ALTER TABLE users ADD UNIQUE KEY uk_users_phone (phone)',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- users：角色 privilege（须先于 points 列的 AFTER privilege 语义；仅用 INFORMATION_SCHEMA 判存）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'privilege') = 0,
    'ALTER TABLE users ADD COLUMN privilege TINYINT NOT NULL DEFAULT 0 COMMENT ''0普通 1付费 2超级管理'' AFTER status',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- users：积分 points / points_bonus / points_refill_date（依赖上节 privilege 已存在）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'points') = 0,
    'ALTER TABLE users ADD COLUMN points INT NOT NULL DEFAULT 0 COMMENT ''当前可用积分'' AFTER privilege',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'points_bonus') = 0,
    'ALTER TABLE users ADD COLUMN points_bonus INT NOT NULL DEFAULT 0 COMMENT ''管理员日配额加项'' AFTER points',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'points_refill_date') = 0,
    'ALTER TABLE users ADD COLUMN points_refill_date DATE NULL COMMENT ''上海日历：上次日重置'' AFTER points_bonus',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_conversations：会话级记忆摘要
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversations' AND COLUMN_NAME = 'session_memory') = 0,
    'ALTER TABLE chat_conversations ADD COLUMN session_memory MEDIUMTEXT NULL COMMENT ''会话级记忆摘要'' AFTER title',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_models：单模型 API Key 密文（空则用全局配置）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_models' AND COLUMN_NAME = 'api_key_cipher') = 0,
    'ALTER TABLE chat_models ADD COLUMN api_key_cipher MEDIUMTEXT NULL COMMENT ''模型专用 API Key AES-GCM 密文 Base64'' AFTER sort_order',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_models：可选独立对话网关（与全局 uigpt.ai.base-url 分离，便于 API易 等中转）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_models' AND COLUMN_NAME = 'base_url') = 0,
    'ALTER TABLE chat_models ADD COLUMN base_url VARCHAR(512) DEFAULT NULL COMMENT ''OpenAI 兼容对话 Base URL（须含 /v1），空则用全局 AI_BASE_URL'' AFTER api_key_cipher',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_conversations：会话置顶（侧栏排序）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversations' AND COLUMN_NAME = 'pinned_at') = 0,
    'ALTER TABLE chat_conversations ADD COLUMN pinned_at DATETIME NULL COMMENT ''置顶时间'' AFTER session_memory',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_conversations：工作台归档 channel（图片/视频创作会话绑定）
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversations' AND COLUMN_NAME = 'studio_channel') = 0,
    'ALTER TABLE chat_conversations ADD COLUMN studio_channel VARCHAR(32) NULL COMMENT ''image-studio / video-studio''',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversations' AND INDEX_NAME = 'uk_conv_user_studio') = 0,
    'ALTER TABLE chat_conversations ADD UNIQUE KEY uk_conv_user_studio (user_id, studio_channel)',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_conversation_images：会话生成图元数据（对象存储）
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS chat_conversation_images (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  conversation_id    BIGINT                DEFAULT NULL COMMENT '所属会话；删除会话后收藏图此项为空',
  user_id            BIGINT       NOT NULL COMMENT '所有者',
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

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images' AND COLUMN_NAME = 'is_favorite') = 0,
    'ALTER TABLE chat_conversation_images ADD COLUMN is_favorite TINYINT NOT NULL DEFAULT 0 COMMENT ''1 收藏'' AFTER image_url',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

-- -----------------------------------------------------------------------------
-- chat_conversation_images：user_id + conversation_id 可空（删会话：未收藏删 MinIO；收藏保留）
-- 已有表时执行：补 user_id → 删会话外键 → conversation_id 置可空 → 重建外键与用户外键
-- -----------------------------------------------------------------------------

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images' AND COLUMN_NAME = 'user_id') = 0,
    'ALTER TABLE chat_conversation_images ADD COLUMN user_id BIGINT NULL COMMENT ''所有者'' AFTER conversation_id',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

UPDATE chat_conversation_images i
INNER JOIN chat_conversations c ON i.conversation_id = c.id
SET i.user_id = c.user_id
WHERE i.user_id IS NULL;

DELETE FROM chat_conversation_images WHERE user_id IS NULL;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images'
       AND COLUMN_NAME = 'user_id' AND IS_NULLABLE = 'YES'),
    'ALTER TABLE chat_conversation_images MODIFY COLUMN user_id BIGINT NOT NULL COMMENT ''所有者''',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @fk_conv := (
  SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'chat_conversation_images'
    AND COLUMN_NAME = 'conversation_id'
    AND REFERENCED_TABLE_NAME = 'chat_conversations'
  LIMIT 1
);
SET @sql := IF(
  @fk_conv IS NOT NULL,
  CONCAT('ALTER TABLE chat_conversation_images DROP FOREIGN KEY `', @fk_conv, '`'),
  'SELECT 1'
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images'
       AND COLUMN_NAME = 'conversation_id' AND IS_NULLABLE = 'NO'),
    'ALTER TABLE chat_conversation_images MODIFY COLUMN conversation_id BIGINT NULL COMMENT ''所属会话；收藏 orphan 为空''',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images' AND CONSTRAINT_NAME = 'fk_conv_img_conv') = 0,
    'ALTER TABLE chat_conversation_images ADD CONSTRAINT fk_conv_img_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id) ON DELETE CASCADE',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images' AND CONSTRAINT_NAME = 'fk_conv_img_user') = 0,
    'ALTER TABLE chat_conversation_images ADD CONSTRAINT fk_conv_img_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;

SET @sql := (
  SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_conversation_images' AND INDEX_NAME = 'idx_conv_img_user_created') = 0,
    'ALTER TABLE chat_conversation_images ADD KEY idx_conv_img_user_created (user_id, created_at)',
    'SELECT 1'
  )
);
PREPARE _stmt FROM @sql;
EXECUTE _stmt;
DEALLOCATE PREPARE _stmt;
