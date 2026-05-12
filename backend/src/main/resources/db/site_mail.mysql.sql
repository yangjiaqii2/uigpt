-- 站内信：每普通用户与管理员之间一条线程（user_id 唯一），消息存正文与图片 URL JSON。
-- 执行前请确认库名与字符集（建议 utf8mb4）。

CREATE TABLE IF NOT EXISTS site_mail_threads (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '发起方（普通用户）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_site_mail_threads_user (user_id),
    KEY idx_site_mail_threads_updated (updated_at)
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
