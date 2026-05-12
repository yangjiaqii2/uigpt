package top.uigpt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

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

    @NotBlank(message = "请再次输入密码")
    @Size(min = 8, max = 72, message = "确认密码长度为 8～72 个字符")
    private String confirmPassword;

    @NotBlank(message = "请先获取图形验证码")
    private String captchaId;

    @NotBlank(message = "请输入图形验证码")
    private String captchaCode;

    /** Google reCAPTCHA v3 token；后端开启校验时必填 */
    private String recaptchaToken;
}
