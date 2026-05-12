package top.uigpt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserCreateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名不超过 64 个字符")
    private String realName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的 11 位中国大陆手机号")
    private String phone;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 64, message = "用户名为 3～64 个字符")
    @Pattern(
            regexp = "^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$",
            message = "用户名仅支持字母、数字、下划线、横线或中文")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度为 8～72 个字符")
    private String password;

    @Size(max = 128, message = "昵称过长")
    private String nickname;

    /** 1 正常 0 禁用 2 待审核；默认 1 */
    @Min(value = 0, message = "状态无效")
    @Max(value = 2, message = "状态无效")
    private Byte status = 1;

    /** 0 普通 1 付费 2 超级管理；默认 0 */
    @Min(value = 0, message = "角色无效")
    @Max(value = 2, message = "角色无效")
    private Byte privilege = 0;

    /** 可选：新建账号的 points_bonus；省略则为 0 */
    @Min(value = -1_000_000, message = "附加积分超出允许范围")
    @Max(value = 1_000_000, message = "附加积分超出允许范围")
    private Integer pointsBonus;
}
