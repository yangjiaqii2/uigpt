package top.uigpt.service;

/** 图片工作台三阶段 Prompt：意图解析、RAG 查询句、第三阶段与家装 SD 规范衔接 */
final class ImageStudioIntentPrompts {

    private ImageStudioIntentPrompts() {}

    /** 第一阶段：从用户合并输入提取结构化标签，并生成用于向量检索的英文查询句 */
    static final String PHASE1_INTENT_SYSTEM =
            "你是家装作图「意图解析」模块。根据用户输入（可含会话上下文、尺寸、画幅说明），只输出**一个合法 JSON**，不要"
                    + " Markdown、不要解释。\n"
                    + "\n"
                    + "## 必须字段（键名固定）\n"
                    + "- room: 空间英文短语，如 living room / bedroom / kitchen；不确定时用 living room\n"
                    + "- style_tags: 字符串数组，中文风格标签，如 [\"奶油风\"]，无则 []\n"
                    + "- style_en_hints: 字符串数组，英文风格关键词短语，如 [\"cream style\",\"warm white\"]\n"
                    + "- material_light_furniture: 字符串数组，材质/灯光/家具中文要点，无则 []\n"
                    + "- constraints: 用户特殊要求、尺寸、门窗等原文要点摘要（中文），无则空字符串\n"
                    + "- rag_embedding_query: **一段英文**（约 40～500 字符），用于向量数据库检索；须综合 room、"
                    + "style_en_hints、材质家具与 constraints，用空格分隔的关键词式短句，**不要中文**。此字段不得为空。\n"
                    + "\n"
                    + "## 规则\n"
                    + "1. 用户描述模糊时，room 用 living room，style 侧向 modern minimalist interior。\n"
                    + "2. rag_embedding_query 要具体可检索，避免空泛单词堆砌。\n";

    /** 接在 InteriorNanoBananaPromptOptimizer.SYSTEM_PROMPT 之后，说明第三阶段输入形态 */
    static final String PHASE3_SYSTEM_APPENDIX =
            "\n"
                    + "## 第三阶段（当前轮）\n"
                    + "你还会收到：【第一阶段·结构化意图 JSON】、【第二阶段·知识库检索片段】、【出图参数】、【用户合并输入】。\n"
                    + "请综合四者：将知识片段中有用的专业表述融入英文 prompt；片段无关或与用户冲突时以用户合并输入为准。\n"
                    + "仍须遵守上文「输出要求」：只输出一个 JSON，键为 prompt、negative_prompt、room、style。\n";
}
