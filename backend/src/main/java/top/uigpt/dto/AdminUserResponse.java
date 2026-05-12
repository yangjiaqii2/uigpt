package top.uigpt.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserResponse {
    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String nickname;
    private String avatarUrl;
    private Byte status;
    /** 0 普通 1 付费 2 超级管理 */
    private Byte privilege;
    /** 当前可用积分（users.points） */
    private Integer points;
    /** 管理员日配额加项（users.points_bonus） */
    private Integer pointsBonus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
