package top.uigpt.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.config.AppProperties;
import top.uigpt.security.JwtUser;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties appProperties;

    @PostConstruct
    void requireJwtSecret() {
        String s = appProperties.getJwt().getSecret();
        if (s == null || s.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥无效：请设置环境变量 UIGPT_JWT_SECRET（UTF-8 下至少 32 字节，约 32 个英文字符），"
                            + "或在 backend 目录执行：cp config/uigpt-local.yml.example config/uigpt-local.yml 后编辑其中的 uigpt.jwt.secret");
        }
    }

    public String createToken(String username) {
        long now = System.currentTimeMillis();
        long exp = now + appProperties.getJwt().getExpirationMs();
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(signingKey())
                .compact();
    }

    /** 解析 Authorization Bearer；验签失败返回 empty。 */
    public Optional<JwtUser> parseBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return parseRawToken(authorizationHeader.substring(7).trim());
    }

    public Optional<JwtUser> parseRawToken(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(signingKey())
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
            Date exp = claims.getExpiration();
            long expMs = exp != null ? exp.getTime() : System.currentTimeMillis();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new JwtUser(subject.strip(), expMs, token));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String parseUsername(String bearerToken) {
        return parseBearer(bearerToken).map(JwtUser::username).orElse(null);
    }

    /** 黑名单 TTL：剩余有效期（秒），至少 1，至多 jwt 配置的全局过期秒数 */
    public long blacklistTtlSeconds(JwtUser jwt) {
        long remainMs = jwt.expiresAtMillis() - System.currentTimeMillis();
        long sec = Math.max(1L, (remainMs + 999) / 1000);
        long cap = Math.max(1L, (appProperties.getJwt().getExpirationMs() + 999) / 1000);
        return Math.min(sec, cap);
    }

    private SecretKey signingKey() {
        byte[] bytes = appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(bytes);
    }
}
