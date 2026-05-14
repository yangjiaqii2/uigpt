package top.uigpt.chat;

/**
 * 从 OpenAI 兼容 SSE 的 {@code data:} JSON 行中提取正文增量；仅用字符串扫描，不反序列化整段 JSON。
 */
public final class SseOpenAiDeltaParser {

    private SseOpenAiDeltaParser() {}

    /**
     * @param dataJson {@code data:} 后的 payload（已 trim），不含 {@code [DONE]}
     */
    public static String extractStreamText(String dataJson) {
        if (dataJson == null || dataJson.isEmpty()) {
            return "";
        }
        String d = dataJson.strip();
        if (d.isEmpty() || "[DONE]".equals(d)) {
            return "";
        }
        String fromDelta = extractContentUnderFirstDelta(d);
        if (!fromDelta.isEmpty()) {
            return fromDelta;
        }
        return extractContentUnderFirstMessage(d);
    }

    private static String extractContentUnderFirstDelta(String d) {
        int di = d.indexOf("\"delta\"");
        if (di < 0) {
            return "";
        }
        int contentKey = indexOfQuotedKey(d, "content", di);
        if (contentKey < 0) {
            return "";
        }
        int valueStart = valueStartAfterQuotedKey(d, contentKey);
        if (valueStart < 0 || valueStart >= d.length()) {
            return "";
        }
        return parseContentValue(d, valueStart);
    }

    private static String extractContentUnderFirstMessage(String d) {
        int mi = d.indexOf("\"message\"");
        if (mi < 0) {
            return "";
        }
        int contentKey = indexOfQuotedKey(d, "content", mi);
        if (contentKey < 0) {
            return "";
        }
        int valueStart = valueStartAfterQuotedKey(d, contentKey);
        if (valueStart < 0 || valueStart >= d.length()) {
            return "";
        }
        return parseContentValue(d, valueStart);
    }

    /** @return index of opening {@code "} of {@code "key"} */
    private static int indexOfQuotedKey(String s, String key, int from) {
        String needle = "\"" + key + "\"";
        return s.indexOf(needle, from);
    }

    /** {@code keyOpenQuote} 指向 {@code "}content{@code "} 的第一个引号 */
    private static int valueStartAfterQuotedKey(String s, int keyOpenQuote) {
        int close = findClosingQuote(s, keyOpenQuote);
        if (close < 0) {
            return -1;
        }
        int colon = s.indexOf(':', close + 1);
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < s.length() && isWs(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int findClosingQuote(String s, int openQuote) {
        int i = openQuote + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static String parseContentValue(String s, int p) {
        if (p >= s.length()) {
            return "";
        }
        char c = s.charAt(p);
        if (c == '"') {
            return readJsonString(s, p);
        }
        if (c == '[') {
            return readTextPartsFromContentArray(s, p);
        }
        if (c == 'n' && p + 3 < s.length() && s.regionMatches(p, "null", 0, 4)) {
            return "";
        }
        return "";
    }

    private static String readJsonString(String s, int openQuote) {
        if (openQuote >= s.length() || s.charAt(openQuote) != '"') {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = openQuote + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                if (i + 1 >= s.length()) {
                    break;
                }
                char esc = s.charAt(i + 1);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            try {
                                int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) cp);
                                i += 5;
                            } catch (NumberFormatException ignored) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(esc);
                }
                i += 2;
                continue;
            }
            if (ch == '"') {
                break;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    /**
     * 多模态 {@code content:[{type:text,text:...}]}：拼接各 {@code "text":"..."}。
     */
    private static String readTextPartsFromContentArray(String s, int bracketOpen) {
        if (bracketOpen >= s.length() || s.charAt(bracketOpen) != '[') {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = bracketOpen + 1;
        while (i < s.length()) {
            int tk = s.indexOf("\"text\"", i);
            if (tk < 0) {
                break;
            }
            int vs = valueStartAfterQuotedKey(s, tk);
            if (vs < 0 || vs >= s.length() || s.charAt(vs) != '"') {
                i = tk + 6;
                continue;
            }
            sb.append(readJsonString(s, vs));
            i = consumeJsonStringEnd(s, vs);
        }
        return sb.toString();
    }

    private static int consumeJsonStringEnd(String s, int openQuote) {
        int i = openQuote + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }
}
