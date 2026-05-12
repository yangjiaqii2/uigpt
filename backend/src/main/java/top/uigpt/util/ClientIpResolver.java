package top.uigpt.util;

import jakarta.servlet.http.HttpServletRequest;

/** 解析客户端 IP（适配反向代理常见头）。 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma >= 0 ? xff.substring(0, comma) : xff;
            String ip = first.trim();
            if (!ip.isEmpty()) {
                return ip;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "";
    }
}
