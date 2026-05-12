package top.uigpt.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 自由对话在 API易线路下：网关异常时可按型号链降级；{@code deepReasoning=true} 时链从最强 toward 较快，{@code false}
 * 时从最快 toward 较强。
 */
public final class FastFreeformModelFallback {

    private FastFreeformModelFallback() {}

    private static final List<String> GPT_CHAIN =
            List.of(
                    "gpt-5.4-pro",
                    "gpt-5.4",
                    "gpt-5.3-codex",
                    "gpt-5.3-codex-spark",
                    "gpt-5");

    private static final List<String> CLAUDE_CHAIN =
            List.of(
                    "claude-opus-4-6",
                    "claude-opus-4-6-thinking",
                    "claude-sonnet-4-6-thinking",
                    "claude-sonnet-4-6",
                    "claude-opus-4-5-20251101",
                    "claude-sonnet-4-5-20250929-thinking",
                    "claude-sonnet-4-5-20250929",
                    "claude-haiku-4-5-20251001");

    private static final List<String> GEMINI_CHAIN =
            List.of(
                    "gemini-3.1-pro-preview",
                    "gemini-3-pro-preview",
                    "gemini-3-flash-preview-thinking",
                    "gemini-3-flash-preview",
                    "gemini-3-flash-preview-nothinking",
                    "gemini-2.5-pro",
                    "gemini-2.5-flash");

    private static final List<String> GROK_CHAIN =
            List.of("grok-4", "grok-4-fast-reasoning", "grok-4-all", "grok-3");

    private static final List<String> DEEPSEEK_CHAIN =
            List.of("deepseek-v3.2", "deepseek-v3-1-250821");

    private static final List<String> ZHIPU_CHAIN = List.of("glm-5", "glm-4.6");

    /**
     * 候选顺序：始终先尝试客户端显式请求的 {@code requestedModelId}（若在白名单内），再按族内链路补齐。
     *
     * @param deepReasoning {@code true}：链路为「强→弱」便于优先质量；{@code false}：链路反转，优先最快型号再逐级增强。
     */
    public static List<String> orderedCandidates(String requestedModelId, boolean deepReasoning) {
        String raw = requestedModelId == null ? "" : requestedModelId.strip();
        String family = raw.isEmpty() ? "gpt" : familyOf(raw);
        List<String> chain = orderedChainForFamily(family, deepReasoning);
        if (raw.isEmpty()) {
            return filterAllowed(chain);
        }
        List<String> out = new ArrayList<>();
        if (FastFreeformModelIds.isAllowed(raw)) {
            out.add(raw);
        }
        for (String id : chain) {
            if (FastFreeformModelIds.isAllowed(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        if (out.isEmpty()) {
            out.add(raw);
        }
        return List.copyOf(out);
    }

    private static List<String> orderedChainForFamily(String family, boolean deepReasoning) {
        List<String> base = new ArrayList<>(chainForFamily(family));
        if (!deepReasoning) {
            Collections.reverse(base);
        }
        return base;
    }

    private static List<String> filterAllowed(List<String> chain) {
        List<String> out = new ArrayList<>();
        for (String id : chain) {
            if (FastFreeformModelIds.isAllowed(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static String familyOf(String modelId) {
        String m = modelId.toLowerCase(Locale.ROOT);
        if (m.startsWith("gpt-") || m.startsWith("o3") || m.startsWith("o4")) {
            return "gpt";
        }
        if (m.startsWith("claude-")) {
            return "claude";
        }
        if (m.startsWith("gemini-")) {
            return "gemini";
        }
        if (m.startsWith("grok-")) {
            return "grok";
        }
        if (m.startsWith("deepseek-")) {
            return "deepseek";
        }
        if (m.startsWith("glm-")) {
            return "zhipu";
        }
        return "unknown";
    }

    private static List<String> chainForFamily(String family) {
        return switch (family) {
            case "gpt" -> GPT_CHAIN;
            case "claude" -> CLAUDE_CHAIN;
            case "gemini" -> GEMINI_CHAIN;
            case "grok" -> GROK_CHAIN;
            case "deepseek" -> DEEPSEEK_CHAIN;
            case "zhipu" -> ZHIPU_CHAIN;
            default -> List.of();
        };
    }
}
