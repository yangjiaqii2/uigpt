package top.uigpt.chat;

/** 为 SSE 下行 {@code {"delta":"..."}} 做最小 JSON 字符串转义。 */
public final class SseClientJsonEscapes {

    private SseClientJsonEscapes() {}

    public static String escapeForJsonString(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String sseDeltaEvent(String deltaText) {
        return "data: {\"delta\":\"" + escapeForJsonString(deltaText) + "\"}\n\n";
    }

    public static String sseErrorEvent(String message) {
        return "data: {\"error\":\"" + escapeForJsonString(message) + "\"}\n\n";
    }

    public static String sseDoneEvent(Long conversationId) {
        if (conversationId == null) {
            return "data: {\"done\":true,\"conversationId\":null}\n\n";
        }
        return "data: {\"done\":true,\"conversationId\":" + conversationId + "}\n\n";
    }
}
