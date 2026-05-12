package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserProfileResponse {
    private String username;
    /** 环境变量管理员名单或 DB privilege=超级管理员，可访问 /api/admin/** */
    private boolean admin;
    /** 0 普通 1 付费 2 超级管理（与 {@link #admin} 独立：付费/普通永不为 true） */
    private int privilege;
    /** 当前可用积分 */
    private int points;
}
