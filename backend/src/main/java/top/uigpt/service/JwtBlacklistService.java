package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String PREFIX = "uigpt:jwt:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public boolean isBlacklisted(String rawJwt) {
        if (rawJwt == null || rawJwt.isBlank()) {
            return false;
        }
        String key = PREFIX + sha256Hex(rawJwt);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /** @param ttlSeconds Redis 键生存时间，不超过 JWT 剩余有效期 */
    public void blacklist(String rawJwt, long ttlSeconds) {
        if (rawJwt == null || rawJwt.isBlank() || ttlSeconds <= 0) {
            return;
        }
        long ttl = Math.min(ttlSeconds, Integer.MAX_VALUE);
        String key = PREFIX + sha256Hex(rawJwt);
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttl));
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
