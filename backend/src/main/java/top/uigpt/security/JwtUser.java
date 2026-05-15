package top.uigpt.security;

/** jjwt 解析后的访问令牌摘要（仍保留原始串供黑名单摘要计算）。 */
public record JwtUser(String username, long expiresAtMillis, String rawToken) {}
