-- 一次性执行：若列已存在会报错（1060 Duplicate column），可忽略或改用 image_studio_sessions_add_studio_skill_id.mysql.sql 中的可重复执行版本。

ALTER TABLE image_studio_sessions
    ADD COLUMN studio_skill_id VARCHAR(64) NOT NULL DEFAULT 'interior_designer'
        COMMENT '作图技能：interior_designer=家装，universal_master=全能大师'
        AFTER last_image_url;
