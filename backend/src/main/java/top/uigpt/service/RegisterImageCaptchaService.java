package top.uigpt.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.ShearCaptcha;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.RegisterCaptchaResponse;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册用图形验证码（进程内存储，一次性校验）。多实例需改为 Redis。
 */
@Service
public class RegisterImageCaptchaService {

    private static final long TTL_MS = 300_000;

    private record Entry(String code, long expireAtMillis) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 生成新验证码图片 */
    public RegisterCaptchaResponse create() {
        prune();
        ShearCaptcha captcha = CaptchaUtil.createShearCaptcha(130, 44, 4, 4);
        String code = captcha.getCode();
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(code, System.currentTimeMillis() + TTL_MS));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        captcha.write(baos);
        String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new RegisterCaptchaResponse(id, b64);
    }

    /**
     * 校验用户输入并作废该 captchaId（无论对错，校验后即删除，需重新获取图片）。
     */
    public void validateAndConsume(String captchaId, String userInput) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先获取图形验证码");
        }
        if (userInput == null || userInput.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入图形验证码");
        }
        Entry entry = store.remove(captchaId.trim());
        if (entry == null || System.currentTimeMillis() > entry.expireAtMillis) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图形验证码已失效，请点击图片刷新");
        }
        if (!entry.code.equalsIgnoreCase(userInput.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图形验证码错误");
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expireAtMillis < now);
    }
}
