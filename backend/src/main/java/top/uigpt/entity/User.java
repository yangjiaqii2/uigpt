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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "real_name", length = 64)
    private String realName;

    /** 中国大陆手机，唯一；历史数据可为空 */
    @Column(length = 20, unique = true)
    private String phone;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(length = 128)
    private String nickname;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    /** 1 正常 0 禁用 2 待审核 */
    @Column(nullable = false)
    private Byte status = 1;

    /**
     * 角色：0 普通用户、1 付费用户、2 超级管理员（与 {@code uigpt.admin.usernames-csv} 共同决定后台访问，见
     * AdminAuthorizationService）。
     */
    @Column(nullable = false)
    private byte privilege = 0;

    /** 可用积分（扣费递减；新库须具备 db/user_points.mysql.sql 中的 bonus/refill 列以启用日重置） */
    @Column(nullable = false)
    private int points = 0;

    /**
     * 管理员可调的日配额加项；每个上海新自然日将 {@link #points} 重置为「角色日上限 + pointsBonus」。
     */
    @Column(name = "points_bonus", nullable = false)
    private int pointsBonus = 0;

    /** 上海日历：上次将 {@link #points} 按日规则刷新到此日期；null 表示尚未初始化 */
    @Column(name = "points_refill_date")
    private LocalDate pointsRefillDate;

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
