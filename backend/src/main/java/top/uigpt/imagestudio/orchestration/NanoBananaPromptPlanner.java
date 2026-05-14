package top.uigpt.imagestudio.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.uigpt.imagestudio.ImageStudioSkillIds;
import top.uigpt.imagestudio.orchestration.model.FinalImagePrompt;
import top.uigpt.imagestudio.orchestration.model.IntentArtifact;
import top.uigpt.imagestudio.orchestration.model.NanobananaPromptBuildInput;
import top.uigpt.imagestudio.orchestration.model.RagContextBlock;
import top.uigpt.service.RagService;

/**
 * Nano Banana 固定三阶段 Planner：意图 → RAG → 最终英文 prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NanoBananaPromptPlanner {

    private final IntentRouter intentRouter;
    private final NanoBananaRagQueryExtractor ragQueryExtractor;
    private final RagService ragService;
    private final PromptStrategyGenerator promptStrategyGenerator;

    public FinalImagePrompt plan(NanobananaPromptBuildInput in) {
        IntentArtifact intent =
                intentRouter.route(in.mergedForModel(), in.aspectRatio(), in.imageSize(), in.studioSkillId());
        String ragQuery =
                ragQueryExtractor.resolveRagEmbeddingQuery(
                        intent.rawJson(), in.rawUserPromptForRag(), in.mergedForModel(), in.studioSkillId());
        boolean ragOn = ImageStudioSkillIds.ragKnowledgeBlockEnabled(in.studioSkillId());
        String ragCollectionArg = ragOn ? in.ragCollectionOverride() : null;
        String ragBlock =
                ragService.retrieveKnowledgeBlockForImage(ragQuery, ragOn, ragCollectionArg);
        String safeBlock = ragBlock == null ? "" : ragBlock;
        int qLen = ragQuery == null ? 0 : ragQuery.length();
        log.info(
                "[NanoBananaPlanner] 作图RAG阶段 studioSkillId={} ragOn={} ragCollectionOverride={} ragQueryLen={} ragBlockLen={} injected={}",
                in.studioSkillId(),
                ragOn,
                ragCollectionArg,
                qLen,
                safeBlock.length(),
                !safeBlock.isBlank());
        RagContextBlock rag = new RagContextBlock(safeBlock);
        String finalPrompt =
                promptStrategyGenerator.finalPromptForNanoBanana(
                        in.mergedForModel(),
                        intent.rawJson(),
                        rag.blockText(),
                        in.aspectRatio(),
                        in.imageSize(),
                        in.studioSkillId());
        return new FinalImagePrompt(finalPrompt);
    }
}
