-- 图片工作台「图片会话」：与 chat_conversations / 多模态对话隔离；与 spring.jpa.hibernate.ddl-auto=none 配合，部署后手动执行一次。

CREATE TABLE IF NOT EXISTS image_studio_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    context_text MEDIUMTEXT NULL,
    image_count INT NOT NULL DEFAULT 0,
    last_image_url VARCHAR(1024) NULL,
    studio_skill_id VARCHAR(64) NOT NULL DEFAULT 'interior_designer' COMMENT '作图技能：interior_designer / universal_master 等',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_image_studio_sessions_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS image_studio_session_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    image_url VARCHAR(1024) NOT NULL,
    user_prompt MEDIUMTEXT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_image_studio_session_images_session_sort (session_id, sort_order),
    CONSTRAINT fk_image_studio_session_images_session
        FOREIGN KEY (session_id) REFERENCES image_studio_sessions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
