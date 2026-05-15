package top.uigpt.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/** 从 {@link SecurityContextHolder} 读取当前登录用户名（JWT 过滤器已落库用户）。 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** 未登录或匿名时返回 {@code null}。 */
    public static String currentUsernameOrNull() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null
                || !a.isAuthenticated()
                || a instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object p = a.getPrincipal();
        if (p instanceof UserDetails ud) {
            return ud.getUsername();
        }
        return p != null ? p.toString() : null;
    }
}
