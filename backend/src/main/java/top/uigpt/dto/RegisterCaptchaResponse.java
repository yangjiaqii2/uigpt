package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterCaptchaResponse {

    /** 提交注册时随表单回传 */
    private String captchaId;

    /** PNG 的 Base64（不含 data URL 前缀） */
    private String imageBase64;
}
