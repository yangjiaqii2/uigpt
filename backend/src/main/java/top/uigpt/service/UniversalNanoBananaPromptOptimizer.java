package top.uigpt.service;

/**
 * 「全能大师」第三阶段：将用户意图转为通用英文作图 prompt（JSON），不套用家装六段式与家具词库。
 */
final class UniversalNanoBananaPromptOptimizer {

    private UniversalNanoBananaPromptOptimizer() {}

    static final String SYSTEM_PROMPT =
            "你是一位全能视觉创作专家，精通所有图像风格与主题。\n"
                    + "当用户输入创作需求时：\n"
                    + "1. 不预设任何领域偏向，忠实还原用户描述。\n"
                    + "2. 自动识别隐含的风格需求（如用户说「赛博朋克」则强化霓虹/机械元素，说「水墨」则强化留白/笔触）。\n"
                    + "3. 优先使用用户指定的画幅、清晰度、风格参数；未指定时根据主题智能推荐构图与光影。\n"
                    + "4. 输出英文 Stable Diffusion 提示词：质量词固定前置为 masterpiece, best quality, ultra-detailed，"
                    + "其后为题材与画面细节，逗号分隔短语；并给出 negative_prompt。\n"
                    + "\n"
                    + "## 输出要求\n"
                    + "只输出一个 JSON，不要解释；不要 Markdown 代码围栏。键名固定为：\n"
                    + "- prompt: 英文主提示，必须以 masterpiece, best quality, ultra-detailed 开头，逗号分隔；\n"
                    + "- negative_prompt: 英文负面提示，针对当前题材常见瑕疵（糊、畸变、水印、错误手指等择要）。\n";

    /** 与家装版共用：拼装用户消息块（意图 JSON + RAG + 参数 + 原文）。 */
    static String buildPhase3UserMessage(
            String merged, String intentJson, String ragKnowledgeBlock, String aspectRatio, String imageSize) {
        return InteriorNanoBananaPromptOptimizer.buildPhase3UserMessage(
                merged, intentJson, ragKnowledgeBlock, aspectRatio, imageSize);
    }
}
