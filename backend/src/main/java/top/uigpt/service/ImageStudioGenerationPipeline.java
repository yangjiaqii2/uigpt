package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.uigpt.imagestudio.orchestration.model.FinalImagePrompt;
import top.uigpt.imagestudio.orchestration.model.NanobananaPromptBuildInput;
import top.uigpt.imagestudio.orchestration.NanoBananaPromptPlanner;

/**
 * 图片工作台 Nano Banana：用户输入 → 鉴权/COS/拼上下文之后的三段式 Prompt 流水线。
 *
 * <p>实现已委托 {@link NanoBananaPromptPlanner}（意图 JSON → RAG → 英文 Prompt），本类保留为兼容入口。
 */
@Service
@RequiredArgsConstructor
public class ImageStudioGenerationPipeline {

    private final NanoBananaPromptPlanner nanoBananaPromptPlanner;

    public String buildNanoBananaPrompt(
            String merged,
            String rawUserPrompt,
            String aspectRatio,
            String imageSize,
            String ragCollectionOverride) {
        FinalImagePrompt fp =
                nanoBananaPromptPlanner.plan(
                        new NanobananaPromptBuildInput(
                                merged,
                                rawUserPrompt,
                                aspectRatio,
                                imageSize,
                                ragCollectionOverride,
                                null));
        return fp.promptForApi();
    }
}
