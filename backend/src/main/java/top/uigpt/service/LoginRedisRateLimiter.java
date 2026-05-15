package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Locale;

/**
 * 登录防暴力：用户名维度失败计数与封锁。Key：{@code uigpt:login:fail:{user}}、{@code uigpt:login:block:{user}}。
 */
@Service
@RequiredArgsConstructor
public class LoginRedisRateLimiter {

    private static final String FAIL_PREFIX = "uigpt:login:fail:";
    private static final String BLOCK_PREFIX = "uigpt:login:block:";

    private final StringRedisTemplate stringRedisTemplate;

    public void assertNotBlocked(String username) {
        String norm = normalize(username);
        String blockKey = BLOCK_PREFIX + norm;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(blockKey))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    /** 密码校验失败时调用（含用户不存在场景，避免枚举）。 */
    public void onLoginFailure(String username, int maxFailures, int failureWindowSec, int blockSec) {
        String norm = normalize(username);
        String failKey = FAIL_PREFIX + norm;
        Long c = stringRedisTemplate.opsForValue().increment(failKey);
        if (c != null && c == 1L) {
            stringRedisTemplate.expire(failKey, Duration.ofSeconds(Math.max(1, failureWindowSec)));
        }
        if (c != null && c >= Math.max(1, maxFailures)) {
            stringRedisTemplate.opsForValue().set(BLOCK_PREFIX + norm, "1", Duration.ofSeconds(Math.max(1, blockSec)));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    public void onLoginSuccess(String username) {
        String norm = normalize(username);
        stringRedisTemplate.delete(FAIL_PREFIX + norm);
        stringRedisTemplate.delete(BLOCK_PREFIX + norm);
    }

    private static String normalize(String username) {
        if (username == null) {
            return "";
        }
        return username.strip().toLowerCase(Locale.ROOT);
    }
}
