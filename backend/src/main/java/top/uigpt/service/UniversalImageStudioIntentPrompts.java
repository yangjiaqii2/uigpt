package top.uigpt.service;

/**
 * 「全能大师」技能：通用创意作图，意图解析阶段不预设室内/家装题材。
 */
final class UniversalImageStudioIntentPrompts {

    private UniversalImageStudioIntentPrompts() {}

    /** 第一阶段：提取中性结构化信息 + 英文检索句（不默认 living room / interior）。 */
    static final String PHASE1_INTENT_SYSTEM =
            "你是高质量文生图「意图解析」模块。根据用户输入（可含会话上下文、画幅说明），只输出**一个合法 JSON**，不要"
                    + " Markdown、不要解释。\n"
                    + "\n"
                    + "## 必须字段（键名固定）\n"
                    + "- subject_en: 英文短语，概括画面主体与场景（如 coastal sunset beach with people）；不确定时根据用户"
                    + "中文合理意译，不要强行室内或家装\n"
                    + "- mood_en: 英文短语，氛围/光线/情绪；无则空字符串\n"
                    + "- style_tags: 字符串数组，中文风格或题材标签，无则 []\n"
                    + "- style_en_hints: 字符串数组，英文风格/镜头/质感关键词短语，无则 []\n"
                    + "- constraints: 用户特殊要求、人物数量、时间、禁忌等原文要点摘要（中文），无则空字符串\n"
                    + "- rag_embedding_query: **一段英文**（约 40～500 字符），空格分隔的关键词式短句，**不要中文**；"
                    + "须综合 subject_en、mood_en、style_en_hints 与 constraints；此字段不得为空。\n"
                    + "\n"
                    + "## 规则\n"
                    + "1. 不要默认假设室内、家装、样板间或房地产；以用户描述的真实题材为准。\n"
                    + "2. 用户描述模糊时，用中性构图/光影/质感与题材大类作英文概括，不要填入 living room、interior design"
                    + " photography 等室内专用词，除非用户明确提到。\n"
                    + "3. rag_embedding_query 要具体可检索，避免空泛单词堆砌。\n";

    /** 第三阶段附录：输出形态与家装技能区分（仅 prompt / negative_prompt）。 */
    static final String PHASE3_SYSTEM_APPENDIX =
            "\n"
                    + "## 第三阶段（当前轮）\n"
                    + "你还会收到：【第一阶段·结构化意图 JSON】、【第二阶段·知识库检索片段】、【出图参数】、【用户合并输入】。\n"
                    + "知识片段可能为空；若有，仅在与用户题材明显相关时融入英文 prompt，否则忽略。\n"
                    + "只输出一个 JSON，不要 Markdown；键名固定为：prompt、negative_prompt（均为英文）。\n";
}
