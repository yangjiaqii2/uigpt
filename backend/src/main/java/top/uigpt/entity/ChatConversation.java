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
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String title;

    /** 会话级记忆摘要，请求模型时作为 system 注入 */
    @Column(name = "session_memory", columnDefinition = "MEDIUMTEXT")
    private String sessionMemory;

    /** 侧栏置顶：非空则排在列表前部，按时间倒序 */
    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    /**
     * 系统创建工作台会话：{@code image-studio}；普通对话为 null。
     * 与 {@link top.uigpt.service.ConversationImageService} 中常量对应。
     */
    @Column(name = "studio_channel", length = 32)
    private String studioChannel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime n = LocalDateTime.now();
        createdAt = n;
        updatedAt = n;
        if (title == null || title.isBlank()) {
            title = "新对话";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
