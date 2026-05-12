package top.uigpt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterOptionsResponse {

    /** 是否启用 reCAPTCHA（v3） */
    private boolean recaptchaEnabled;

    /** 前端站点密钥；未启用时为空串 */
    private String recaptchaSiteKey;
}
