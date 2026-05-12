package top.uigpt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "chat_conversation_images")
public class ChatConversationImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话；删除会话后收藏图保留记录且此项置空 */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 所有者；与会话解绑后仍用于「我的作品」与权限校验 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 对应 chat_messages.sort_order（助手气泡），便于会话重载后还原与会话内定位 */
    @Column(name = "message_sort_order", nullable = false)
    private Integer messageSortOrder;

    /** 技能 id：mockup / wireframe / retouch 等 */
    @Column(name = "skill_id", nullable = false, length = 32)
    private String skillId;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    /** 浏览器可直接访问的完整 URL（由 COS 公有读前缀或 CDN + object_key 构成） */
    @Column(name = "image_url", nullable = false, length = 1024)
    private String imageUrl;

    @Column(name = "is_favorite", nullable = false)
    private boolean favorite = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
