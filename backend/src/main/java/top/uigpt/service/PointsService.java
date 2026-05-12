package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.config.AppProperties;
import top.uigpt.entity.User;
import top.uigpt.model.UserPrivilege;
import top.uigpt.repository.UserRepository;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 用户积分：按上海时区（{@link #POINTS_ZONE}）自然日将 {@code users.points} 重置为「角色日上限 +
 * users.points_bonus」，再扣减。扣减使用悲观锁与实体写入，与 {@link UserRepository#addPoints} 退款配合。
 *
 * <p>关闭 {@code uigpt.points.enabled=false} 时不扣费、不重置、GET /api/me 仍返回库内 points。
 */
@Service
@RequiredArgsConstructor
public class PointsService {

    /** 日界线与时区：与 {@code spring.datasource.url} 常用 serverTimezone 及业务口径一致 */
    public static final ZoneId POINTS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 与 GlobalExceptionHandler / 前端 {@code ApiError.message} 一致 */
    public static final String INSUFFICIENT_POINTS_ZH = "积分不足，请稍后再试或联系管理员充值";

    private final UserRepository userRepository;
    private final AppProperties appProperties;

    public boolean isEnabled() {
        return appProperties.getPoints().isEnabled();
    }

    public LocalDate todayShanghai() {
        return LocalDate.now(POINTS_ZONE);
    }

    /**
     * GET /api/me 等读路径：在单事务内加锁并必要时执行日重置，返回当前可用积分。
     */
    @Transactional
    public int syncRefillForUsername(String username) {
        User u =
                userRepository
                        .findByUsernameForUpdate(username.strip())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        if (!isEnabled()) {
            return u.getPoints();
        }
        applyDailyRefill(u);
        userRepository.save(u);
        return u.getPoints();
    }

    /**
     * 若当前积分小于 {@code amount} 则抛出 {@link HttpStatus#PAYMENT_REQUIRED}（402）；否则扣减。
     *
     * @param reason 审计/日志用短标识，可为 {@code null}
     */
    @Transactional
    public void assertAndDeduct(long userId, int amount, String reason) {
        if (!isEnabled() || amount <= 0) {
            return;
        }
        User u =
                userRepository
                        .findByIdForUpdate(userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        applyDailyRefill(u);
        if (u.getPoints() < amount) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, INSUFFICIENT_POINTS_ZH);
        }
        u.setPoints(u.getPoints() - amount);
        userRepository.save(u);
    }

    /** 模型或持久化失败时回滚预扣积分。 */
    @Transactional
    public void refund(long userId, int amount, String reason) {
        if (!isEnabled() || amount <= 0) {
            return;
        }
        userRepository.addPoints(userId, amount);
    }

    /**
     * 管理员改角色或 {@code points_bonus} 后：将当日可用积分对齐为 cap(privilege)+bonus。若尚未记录日重置或上次重置早于
     * 今日（上海），一并视为今日池并写入 {@code points_refill_date=today}。
     */
    public void rewriteTodayPoolFromPrivilegeAndBonus(User u) {
        if (!isEnabled()) {
            return;
        }
        LocalDate today = todayShanghai();
        LocalDate refill = u.getPointsRefillDate();
        if (refill == null || !refill.isAfter(today)) {
            int cap = UserPrivilege.dailyPointsCapForDbPrivilege(u.getPrivilege());
            u.setPoints(cap + u.getPointsBonus());
            u.setPointsRefillDate(today);
        }
    }

    private void applyDailyRefill(User u) {
        LocalDate today = todayShanghai();
        LocalDate last = u.getPointsRefillDate();
        if (last == null || last.isBefore(today)) {
            int cap = UserPrivilege.dailyPointsCapForDbPrivilege(u.getPrivilege());
            u.setPoints(cap + u.getPointsBonus());
            u.setPointsRefillDate(today);
        }
    }
}
