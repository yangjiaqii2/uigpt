package top.uigpt.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.uigpt.service.JwtBlacklistService;
import top.uigpt.service.JwtService;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JwtBlacklistService jwtBlacklistService;
    private final UserDetailsService userDetailsService;
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<JwtUser> parsed = jwtService.parseBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (parsed.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        JwtUser jwt = parsed.get();
        if (jwtBlacklistService.isBlacklisted(jwt.rawToken())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UserDetails ud = userDetailsService.loadUserByUsername(jwt.username());
            if (!ud.isEnabled()) {
                filterChain.doFilter(request, response);
                return;
            }
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
            token.setDetails(detailsSource.buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(token);
        } catch (UsernameNotFoundException ignored) {
            // 令牌有效但账号不存在：视为未登录
        }
        filterChain.doFilter(request, response);
    }
}
