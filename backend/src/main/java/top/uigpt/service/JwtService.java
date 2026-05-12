package top.uigpt.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.config.AppProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

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

    public String parseUsername(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            return null;
        }
        String token = bearerToken.substring(7);
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    private SecretKey signingKey() {
        byte[] bytes = appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(bytes);
    }
}
