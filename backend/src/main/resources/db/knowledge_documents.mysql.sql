-- 知识库条目元数据（与 Qdrant 向量 point_id 一致）。在业务库执行一次即可。
CREATE TABLE IF NOT EXISTS knowledge_documents (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  point_id VARCHAR(36) NOT NULL COMMENT '与 Qdrant 点 id 一致',
  title VARCHAR(512) DEFAULT NULL,
  content MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_knowledge_point_id (point_id),
  KEY idx_knowledge_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
