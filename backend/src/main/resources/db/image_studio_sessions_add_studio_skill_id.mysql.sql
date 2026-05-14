-- 图片会话：记录作图技能（与前端 studioSkillId 对齐）。
-- 已有库部署后执行一次即可；新建库若已用 image_studio_sessions.mysql.sql 完整建表则无需执行。
-- 本脚本可重复执行：列已存在时不会报错。

SET @schema := DATABASE();
SET @has_col := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema
      AND TABLE_NAME = 'image_studio_sessions'
      AND COLUMN_NAME = 'studio_skill_id'
);
SET @ddl := IF(
    @has_col > 0,
    'SELECT ''column studio_skill_id already exists'' AS notice',
    'ALTER TABLE image_studio_sessions
        ADD COLUMN studio_skill_id VARCHAR(64) NOT NULL DEFAULT ''interior_designer''
            COMMENT ''作图技能：interior_designer=家装，universal_master=全能大师''
            AFTER last_image_url'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
