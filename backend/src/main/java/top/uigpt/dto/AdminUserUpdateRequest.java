package top.uigpt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserUpdateRequest {

    /** 非 null 则更新（空串视为清空姓名，不允许则在校验层限制） */
    @Size(max = 64, message = "姓名不超过 64 个字符")
    private String realName;

    /** 非 null 则更新为给定手机号（须 11 位） */
    private String phone;

    @Size(max = 128, message = "昵称过长")
    private String nickname;

    /** 非 null 则更新：1 正常 0 禁用 2 待审核 */
    @Min(value = 0, message = "状态无效")
    @Max(value = 2, message = "状态无效")
    private Byte status;

    /** 非 null 且非空则重置密码（长度在服务端校验） */
    private String newPassword;

    /** 非 null 则更新：0 普通 1 付费 2 超级管理 */
    @Min(value = 0, message = "角色无效")
    @Max(value = 2, message = "角色无效")
    private Byte privilege;

    /**
     * 非 null 则覆盖当前可用积分（users.points）。跨上海自然日后会先按「角色日上限 + points_bonus」重置再消费，见
     * PointsService。
     */
    @Min(value = -1_000_000, message = "积分超出允许范围")
    @Max(value = 1_000_000, message = "积分超出允许范围")
    private Integer points;

    /**
     * 非 null 则更新管理员附加日配额加项（users.points_bonus）；若当日已发生日重置，会立刻按新 cap+bonus 重写当日
     * points。
     */
    @Min(value = -1_000_000, message = "附加积分超出允许范围")
    @Max(value = 1_000_000, message = "附加积分超出允许范围")
    private Integer pointsBonus;
}
