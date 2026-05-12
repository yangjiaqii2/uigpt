package top.uigpt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "chat_models")
public class ChatModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上游 OpenAI 兼容接口的 model 字段，如 qwen-turbo */
    @Column(name = "api_model_code", nullable = false, length = 128)
    private String apiModelCode;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 32)
    private String provider;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /**
     * 该模型调用上游时使用的 API Key（AES-256-GCM 密文，Base64）。
     * 为空则回退使用全局 {@code DASHSCOPE_API_KEY} 等配置。
     */
    @Column(name = "api_key_cipher", columnDefinition = "MEDIUMTEXT")
    private String apiKeyCipher;

    /**
     * OpenAI 兼容对话网关 Base URL（须含版本路径），如 {@code https://api.apiyi.com/v1}；
     * 为空则使用全局 {@code uigpt.ai.base-url}（如 DashScope）。
     */
    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime n = LocalDateTime.now();
        createdAt = n;
        updatedAt = n;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
