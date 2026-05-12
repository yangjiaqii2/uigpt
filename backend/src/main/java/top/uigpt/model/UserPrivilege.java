package top.uigpt.model;

/**
 * 用户角色（持久化列 {@code users.privilege}，TINYINT）。
 *
 * <p>0 普通用户、1 付费用户、2 超级管理员。管理员 API 访问权由 {@code AdminAuthorizationService} 根据环境变量名单与
 * {@link #SUPER_ADMIN} 共同判定。
 */
public enum UserPrivilege {
    STANDARD((byte) 0),
    PREMIUM((byte) 1),
    SUPER_ADMIN((byte) 2);

    /**
     * 按上海时区自然日刷新的日消费上限（与 {@code users.points_refill_date} 语义一致，见 {@code
     * PointsService}）。
     */
    public int dailyPointsCap() {
        return switch (this) {
            case STANDARD -> 100;
            case PREMIUM, SUPER_ADMIN -> 2000;
        };
    }

    /** 非法值时按普通用户处理，避免脏数据导致系统异常 */
    public static int dailyPointsCapForDbPrivilege(byte privilege) {
        for (UserPrivilege p : values()) {
            if (p.dbValue == privilege) {
                return p.dailyPointsCap();
            }
        }
        return STANDARD.dailyPointsCap();
    }

    private final byte dbValue;

    UserPrivilege(byte dbValue) {
        this.dbValue = dbValue;
    }

    public byte getDbValue() {
        return dbValue;
    }

    public static UserPrivilege fromDbValue(byte b) {
        for (UserPrivilege p : values()) {
            if (p.dbValue == b) {
                return p;
            }
        }
        throw new IllegalArgumentException("无效 privilege 值: " + b);
    }
}
