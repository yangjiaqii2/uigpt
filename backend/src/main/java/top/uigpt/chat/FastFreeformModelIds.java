package top.uigpt.chat;

import java.util.Set;

/**
 * 经 API易 转发的允许 model id（与产品约定的各系列具体路由一致；前端大类映射至此）。
 */
public final class FastFreeformModelIds {

    private FastFreeformModelIds() {}

    public static final Set<String> ALL =
            Set.of(
                    // GPT（用户约定）
                    "gpt-5.4-pro",
                    "gpt-5.4",
                    "gpt-5.3-codex",
                    "gpt-5.3-codex-spark",
                    "gpt-5",
                    // Claude（含 APIYi 文档示例型号）
                    "claude-haiku-4-5-20251001",
                    "claude-sonnet-4-5-20250929",
                    "claude-sonnet-4-5-20250929-thinking",
                    "claude-opus-4-6",
                    "claude-opus-4-6-thinking",
                    "claude-sonnet-4-6",
                    "claude-sonnet-4-6-thinking",
                    "claude-opus-4-5-20251101",
                    // Gemini（OpenAI 兼容路径见 ChatService reasoning_effort；原生 /v1beta/ 未在本项目中单独实现）
                    "gemini-3-pro-preview",
                    "gemini-3.1-pro-preview",
                    "gemini-3-flash-preview",
                    "gemini-3-flash-preview-nothinking",
                    "gemini-3-flash-preview-thinking",
                    "gemini-2.5-pro",
                    "gemini-2.5-flash",
                    // Grok
                    "grok-4",
                    "grok-4-all",
                    "grok-4-fast-reasoning",
                    "grok-3",
                    // DeepSeek
                    "deepseek-v3.2",
                    "deepseek-v3-1-250821",
                    // 智谱
                    "glm-5",
                    "glm-4.6");

    public static boolean isAllowed(String modelId) {
        return modelId != null && !modelId.isBlank() && ALL.contains(modelId.strip());
    }
}
