package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 注册成功次数：按 IP 与手机号各一条 ZSET（score=毫秒时间戳），与进程内版相同的 1h/24h 滚动窗口。
 * Key：{@code uigpt:register:ip:{ip}}、{@code uigpt:register:phone:{phone}}。
 */
@Service
@RequiredArgsConstructor
public class RegisterRedisRateLimiter {

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
            """
            local ipk = KEYS[1]
            local phonek = KEYS[2]
            local now = tonumber(ARGV[1])
            local hourMs = tonumber(ARGV[2])
            local dayMs = tonumber(ARGV[3])
            local maxHour = tonumber(ARGV[4])
            local maxDay = tonumber(ARGV[5])
            local member = ARGV[6]

            local function prune_and_counts(key)
              redis.call('ZREMRANGEBYSCORE', key, '-inf', now - dayMs)
              local total = redis.call('ZCARD', key)
              local inHour = redis.call('ZCOUNT', key, now - hourMs, '+inf')
              return total, inHour
            end

            local iTotal, iHour = prune_and_counts(ipk)
            local pTotal, pHour = prune_and_counts(phonek)
            if iHour >= maxHour or iTotal >= maxDay or pHour >= maxHour or pTotal >= maxDay then
              return 0
            end
            redis.call('ZADD', ipk, now, member)
            redis.call('ZADD', phonek, now, member)
            return 1
            """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public void assertAllowsAndRecord(String rawIp, String phone, String memberId, int maxPerHour, int maxPer24h) {
        int maxHour = Math.max(1, maxPerHour);
        int maxDay = Math.max(1, maxPer24h);
        if (maxDay < maxHour) {
            maxDay = maxHour;
        }
        String ip = normalizeIp(rawIp);
        String ph = normalizePhone(phone);
        String ipKey = "uigpt:register:ip:" + ip;
        String phoneKey = "uigpt:register:phone:" + ph;
        long now = System.currentTimeMillis();
        Long ok =
                stringRedisTemplate.execute(
                        RESERVE_SCRIPT,
                        List.of(ipKey, phoneKey),
                        String.valueOf(now),
                        String.valueOf(HOUR_MS),
                        String.valueOf(DAY_MS),
                        String.valueOf(maxHour),
                        String.valueOf(maxDay),
                        memberId);
        if (ok == null || ok == 0L) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "该网络或手机号注册次数已达上限，请稍后再试");
        }
    }

    public void rollback(String rawIp, String phone, String memberId) {
        String ipKey = "uigpt:register:ip:" + normalizeIp(rawIp);
        String phoneKey = "uigpt:register:phone:" + normalizePhone(phone);
        stringRedisTemplate.opsForZSet().remove(ipKey, memberId);
        stringRedisTemplate.opsForZSet().remove(phoneKey, memberId);
    }

    private static String normalizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        return rawIp.trim();
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "unknown";
        }
        return phone.trim();
    }
}
