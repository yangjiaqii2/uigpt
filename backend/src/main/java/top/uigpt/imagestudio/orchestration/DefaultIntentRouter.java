package top.uigpt.imagestudio.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.imagestudio.orchestration.model.IntentArtifact;
import top.uigpt.service.ApiYiImageService;

@Service
@RequiredArgsConstructor
public class DefaultIntentRouter implements IntentRouter {

    private final ApiYiImageService apiYiImageService;

    @Override
    public IntentArtifact route(String mergedForModel, String aspectRatio, String imageSize, String studioSkillId) {
        return new IntentArtifact(
                apiYiImageService.imageStudioPhase1IntentJson(
                        mergedForModel, aspectRatio, imageSize, studioSkillId));
    }
}
