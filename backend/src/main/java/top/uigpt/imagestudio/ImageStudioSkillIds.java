package top.uigpt.imagestudio;

/**
 * 图片工作台「技能」标识：与前端 {@code studioSkillId} 对齐，用于选择意图解析 / 第三阶段组词策略。
 */
public final class ImageStudioSkillIds {

    /** 家装设计师：对应 {@link top.uigpt.service.InteriorNanoBananaPromptOptimizer} 等家装链路。 */
    public static final String INTERIOR_DESIGNER = "interior_designer";

    /** 全能大师：通用创意作图，不绑定领域 RAG、不注入家装偏向。 */
    public static final String UNIVERSAL_MASTER = "universal_master";

    private ImageStudioSkillIds() {}

    /** 未传或未知 id 时回落为家装（保持与历史行为一致）。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERIOR_DESIGNER;
        }
        String t = raw.strip();
        if (INTERIOR_DESIGNER.equals(t)) {
            return INTERIOR_DESIGNER;
        }
        if (UNIVERSAL_MASTER.equals(t)) {
            return UNIVERSAL_MASTER;
        }
        return INTERIOR_DESIGNER;
    }

    /**
     * 三阶段流水线是否检索并注入知识库块：{@link #UNIVERSAL_MASTER} 为 false，避免领域集合与检索结果带入偏向。
     */
    public static boolean ragKnowledgeBlockEnabled(String studioSkillId) {
        return !UNIVERSAL_MASTER.equals(normalize(studioSkillId));
    }
}
