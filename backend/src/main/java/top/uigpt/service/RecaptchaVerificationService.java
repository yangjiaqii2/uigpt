package top.uigpt.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.UserFacingMessages;
import top.uigpt.config.AppProperties;

/**
 * Google reCAPTCHA v3：{@code https://developers.google.com/recaptcha/docs/v3}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecaptchaVerificationService {

    private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.create();

    public void verify(String remoteIp, String token) {
        AppProperties.Recaptcha rc = appProperties.getRecaptcha();
        if (!rc.isEnabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请完成人机验证后重试");
        }
        String secret = rc.getSecretKey();
        if (secret == null || secret.isBlank()) {
            log.error("reCAPTCHA 已启用但未配置 secret-key");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, UserFacingMessages.NETWORK_TRY_LATER);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank() && !"unknown".equals(remoteIp)) {
            form.add("remoteip", remoteIp.trim());
        }

        SiteverifyResponse body;
        try {
            body =
                    restClient
                            .post()
                            .uri(VERIFY_URL)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .body(SiteverifyResponse.class);
        } catch (Exception e) {
            log.warn("reCAPTCHA siteverify 请求失败: {}", e.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, UserFacingMessages.NETWORK_TRY_LATER);
        }

        if (body == null || !Boolean.TRUE.equals(body.success)) {
            if (log.isDebugEnabled()) {
                log.debug("reCAPTCHA 校验失败 body={}", body);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人机验证未通过，请重试");
        }

        if (body.score != null && body.score < rc.getMinScore()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人机验证未通过，请重试");
        }
        if (body.action != null
                && !body.action.isBlank()
                && !"register".equalsIgnoreCase(body.action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人机验证未通过，请重试");
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SiteverifyResponse {
        private Boolean success;

        private Double score;

        private String action;

        @JsonProperty("error-codes")
        private String[] errorCodes;
    }
}
