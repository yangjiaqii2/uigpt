package top.uigpt.imagestudio.orchestration;

/** Prompt 策略生成：综合意图、知识块与用户输入，产出最终作图 prompt。 */
public interface PromptStrategyGenerator {

    String finalPromptForNanoBanana(
            String mergedForModel,
            String phase1IntentJson,
            String ragKnowledgeBlock,
            String aspectRatio,
            String imageSize,
            String studioSkillId);
}
