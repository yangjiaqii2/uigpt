package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ForgotPasswordResetRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名不超过 64 个字符")
    private String realName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的 11 位中国大陆手机号")
    private String phone;

    /** 注册日期，yyyy-MM-dd，须与账号创建日一致 */
    @NotBlank(message = "请选择注册日期")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "注册日期格式须为 yyyy-MM-dd")
    private String registeredDate;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "新密码长度为 8～72 个字符")
    private String newPassword;

    @NotBlank(message = "请再次输入新密码")
    @Size(min = 8, max = 72, message = "确认密码长度为 8～72 个字符")
    private String confirmNewPassword;
}
