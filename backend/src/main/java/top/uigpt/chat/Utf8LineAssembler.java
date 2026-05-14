package top.uigpt.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 将上游 SSE 的 UTF-8 字节块切分为行（支持跨 chunk 的半行）。
 */
public final class Utf8LineAssembler {

    private byte[] pending = new byte[0];

    public List<String> feed(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return List.of();
        }
        byte[] merged = concat(pending, chunk);
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < merged.length; i++) {
            if (merged[i] == '\n') {
                int end = i;
                if (end > start && merged[end - 1] == '\r') {
                    end--;
                }
                out.add(new String(merged, start, end - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        int rem = merged.length - start;
        pending = rem == 0 ? new byte[0] : java.util.Arrays.copyOfRange(merged, start, merged.length);
        return out;
    }

    public String flushRemainder() {
        if (pending.length == 0) {
            return null;
        }
        String s = new String(pending, StandardCharsets.UTF_8).strip();
        pending = new byte[0];
        return s.isEmpty() ? null : s;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
