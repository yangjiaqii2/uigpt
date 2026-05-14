package top.uigpt.service;

import top.uigpt.imagestudio.ImageStudioSkillIds;

/**
 * 按技能选择图片工作台三阶段中的系统提示（便于后续增加非家装技能）。
 */
public final class ImageStudioSkillPrompts {

    private ImageStudioSkillPrompts() {}

    public static String phase1IntentSystem(String studioSkillId) {
        String skill = ImageStudioSkillIds.normalize(studioSkillId);
        if (ImageStudioSkillIds.UNIVERSAL_MASTER.equals(skill)) {
            return UniversalImageStudioIntentPrompts.PHASE1_INTENT_SYSTEM;
        }
        return ImageStudioIntentPrompts.PHASE1_INTENT_SYSTEM;
    }

    /** 第三阶段：家装 SD 组装主系统提示 + 阶段说明附录。 */
    public static String phase3SystemCombined(String studioSkillId) {
        String skill = ImageStudioSkillIds.normalize(studioSkillId);
        if (ImageStudioSkillIds.UNIVERSAL_MASTER.equals(skill)) {
            return UniversalNanoBananaPromptOptimizer.SYSTEM_PROMPT
                    + UniversalImageStudioIntentPrompts.PHASE3_SYSTEM_APPENDIX;
        }
        return InteriorNanoBananaPromptOptimizer.SYSTEM_PROMPT + ImageStudioIntentPrompts.PHASE3_SYSTEM_APPENDIX;
    }
}
