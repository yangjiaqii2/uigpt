package top.uigpt.imagestudio.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.service.ApiYiImageService;

@Service
@RequiredArgsConstructor
public class DefaultPromptStrategyGenerator implements PromptStrategyGenerator {

    private final ApiYiImageService apiYiImageService;

    @Override
    public String finalPromptForNanoBanana(
            String mergedForModel,
            String phase1IntentJson,
            String ragKnowledgeBlock,
            String aspectRatio,
            String imageSize,
            String studioSkillId) {
        return apiYiImageService.imageStudioPhase3FinalPrompt(
                mergedForModel, phase1IntentJson, ragKnowledgeBlock, aspectRatio, imageSize, studioSkillId);
    }
}
