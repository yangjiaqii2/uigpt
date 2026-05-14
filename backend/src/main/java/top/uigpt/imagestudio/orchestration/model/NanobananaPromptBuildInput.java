package top.uigpt.imagestudio.orchestration.model;

/**
 * 图片工作台 Nano Banana：意图/RAG/第三阶段组装所需的输入（合并后的用户侧文本 + RAG 检索句）。
 */
public record NanobananaPromptBuildInput(
        String mergedForModel,
        String rawUserPromptForRag,
        String aspectRatio,
        String imageSize,
        String ragCollectionOverride,
        /** 与前端 {@code studioSkillId} 一致，如 {@code interior_designer} */
        String studioSkillId) {}
