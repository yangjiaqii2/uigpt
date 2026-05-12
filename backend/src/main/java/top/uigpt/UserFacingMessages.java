package top.uigpt;

/** 面向终端用户的统一提示文案（不含运维细节，细节见文档与日志）。 */
public final class UserFacingMessages {

    private UserFacingMessages() {}

    /** 数据库、网络、上游服务等异常时对用户的统一提示 */
    public static final String NETWORK_TRY_LATER = "网络异常，请稍后再试";

    /** 上游网关返回无渠道、欠费、模型下架等（日志中保留原始 status/body） */
    public static final String UPSTREAM_MODEL_UNAVAILABLE = "模型线路繁忙或当前账号暂无该模型通道，请更换模型或稍后重试";
}
