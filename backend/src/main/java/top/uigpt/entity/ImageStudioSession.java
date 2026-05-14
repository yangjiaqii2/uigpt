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
@Table(name = "image_studio_sessions")
public class ImageStudioSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "context_text", columnDefinition = "MEDIUMTEXT")
    private String contextText;

    @Column(name = "image_count", nullable = false)
    private int imageCount;

    @Column(name = "last_image_url", length = 1024)
    private String lastImageUrl;

    /** 与前端 {@code studioSkillId} 一致，如 interior_designer、universal_master */
    @Column(name = "studio_skill_id", nullable = false, length = 64)
    private String studioSkillId = "interior_designer";

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
            title = "新会话";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
