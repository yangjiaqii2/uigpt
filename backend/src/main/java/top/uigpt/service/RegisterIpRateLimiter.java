package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.config.AppProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 IP 限制注册成功次数：默认 1 小时内最多 2 次、滚动 24 小时内最多 3 次。
 * 进程内存储；多实例部署需改为 Redis 等共享存储。
 */
@Service
@RequiredArgsConstructor
public class RegisterIpRateLimiter {

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private final AppProperties appProperties;

    private final ConcurrentHashMap<String, Object> syncs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Long>> events = new ConcurrentHashMap<>();

    public Object sync(String rawIp) {
        String ip = normalize(rawIp);
        return syncs.computeIfAbsent(ip, k -> new Object());
    }

    /**
     * 在持有 {@link #sync(String)} 的同一把锁内调用：校验当前是否还可再成功注册 1 次。
     */
    public void checkAllowsOneMoreRegistration(String ip) {
        AppProperties.RegisterRateLimit cfg = appProperties.getRegisterRateLimit();
        int maxHour = Math.max(1, cfg.getMaxPerHour());
        int maxDay = Math.max(1, cfg.getMaxPer24Hours());
        if (maxDay < maxHour) {
            maxDay = maxHour;
        }

        long now = System.currentTimeMillis();
        String key = normalize(ip);
        List<Long> list = events.computeIfAbsent(key, k -> new ArrayList<>());
        prune(list, now);

        long inHour = list.stream().filter(t -> now - t <= HOUR_MS).count();
        if (inHour >= maxHour) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "该网络 1 小时内注册次数已达上限，请稍后再试");
        }
        if (list.size() >= maxDay) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "该网络 24 小时内注册次数已达上限，请稍后再试");
        }
    }

    /**
     * 在注册事务成功写入用户后、仍持有同一把锁时调用。
     */
    public void recordSuccessfulRegistration(String ip) {
        long now = System.currentTimeMillis();
        String key = normalize(ip);
        List<Long> list = events.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(now);
        prune(list, now);
    }

    private static void prune(List<Long> list, long now) {
        list.removeIf(t -> now - t > DAY_MS);
    }

    private static String normalize(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        return rawIp.trim();
    }
}
