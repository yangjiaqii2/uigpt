package top.uigpt.imagestudio.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.uigpt.config.AppProperties;
import top.uigpt.dto.ImageStudioEditRequest;
import top.uigpt.dto.ImageStudioTextRequest;
import top.uigpt.imagestudio.orchestration.model.FinalImagePrompt;
import top.uigpt.imagestudio.orchestration.model.NanobananaPromptBuildInput;
import top.uigpt.service.ApiYiImageService.NanoBananaInlineImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static top.uigpt.config.AsyncConfig.IMAGE_STUDIO_GENERATION_EXECUTOR;

/**
 * 图片工作台 Nano Banana：编排记忆合并、三阶段 prompt 规划、并行候选与可选 Judge/Selector。
 */
@Slf4j
@Service
public class ImageStudioNanoBananaOrchestrator {

    private final ImageMemoryService imageMemoryService;
    private final NanoBananaPromptPlanner promptPlanner;
    private final ImageToolExecutor imageToolExecutor;
    private final AppProperties appProperties;
    private final ImageCandidateJudge imageCandidateJudge;
    private final Executor imageStudioGenerationExecutor;

    public ImageStudioNanoBananaOrchestrator(
            ImageMemoryService imageMemoryService,
            NanoBananaPromptPlanner promptPlanner,
            ImageToolExecutor imageToolExecutor,
            AppProperties appProperties,
            ImageCandidateJudge imageCandidateJudge,
            @Qualifier(IMAGE_STUDIO_GENERATION_EXECUTOR) Executor imageStudioGenerationExecutor) {
        this.imageMemoryService = imageMemoryService;
        this.promptPlanner = promptPlanner;
        this.imageToolExecutor = imageToolExecutor;
        this.appProperties = appProperties;
        this.imageCandidateJudge = imageCandidateJudge;
        this.imageStudioGenerationExecutor = imageStudioGenerationExecutor;
    }

    public FinalImagePrompt buildFinalPromptForTextRequest(ImageStudioTextRequest body) {
        return buildFinalPromptInternal(
                body.getPrompt(),
                body.getImageSessionContext(),
                body.getAspectRatio(),
                body.getImageSize(),
                body.getRagCollection(),
                body.getStudioSkillId());
    }

    public FinalImagePrompt buildFinalPromptForEditRequest(ImageStudioEditRequest body) {
        return buildFinalPromptInternal(
                body.getPrompt(),
                body.getImageSessionContext(),
                body.getAspectRatio(),
                body.getImageSize(),
                body.getRagCollection(),
                body.getStudioSkillId());
    }

    private FinalImagePrompt buildFinalPromptInternal(
            String userPrompt,
            String sessionContext,
            String aspectRatio,
            String imageSize,
            String ragCollection,
            String studioSkillId) {
        String merged = imageMemoryService.mergeForApi(userPrompt, sessionContext);
        String ragQuery = userPrompt == null ? "" : userPrompt.strip();
        return promptPlanner.plan(
                new NanobananaPromptBuildInput(
                        merged, ragQuery, aspectRatio, imageSize, ragCollection, studioSkillId));
    }

    public int resolveParallelCandidateCount(Integer requested) {
        int max = Math.max(2, appProperties.getImageStudio().getPairMaxCandidates());
        if (requested == null) {
            return 2;
        }
        return Math.max(2, Math.min(max, requested));
    }

    public List<byte[]> parallelTextToImage(
            String basePromptForApi, String aspectRatio, String imageSize, int candidateCount) {
        List<String> prompts = promptVariants(basePromptForApi, candidateCount);
        List<CompletableFuture<byte[]>> futures = new ArrayList<>(prompts.size());
        for (String p : prompts) {
            futures.add(
                    CompletableFuture.supplyAsync(
                            () -> tryTextToImageQuiet(p, aspectRatio, imageSize),
                            imageStudioGenerationExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<byte[]> out = new ArrayList<>(futures.size());
        for (CompletableFuture<byte[]> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    public List<byte[]> parallelEdit(
            String basePromptForApi,
            List<NanoBananaInlineImage> images,
            String aspectRatio,
            String imageSize,
            int candidateCount) {
        List<String> prompts = promptVariants(basePromptForApi, candidateCount);
        List<CompletableFuture<byte[]>> futures = new ArrayList<>(prompts.size());
        for (String p : prompts) {
            futures.add(
                    CompletableFuture.supplyAsync(
                            () -> tryEditQuiet(p, images, aspectRatio, imageSize),
                            imageStudioGenerationExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<byte[]> out = new ArrayList<>(futures.size());
        for (CompletableFuture<byte[]> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /**
     * 在「全部失败」之外返回推荐槽的全局下标 {@code 0..n-1}；Judge 关闭、无有效候选或平分无法区分时返回 {@code null}。
     */
    public Integer selectRecommendedSlot(List<byte[]> candidates, String userGoalText) {
        if (!appProperties.getImageStudio().isPairJudgeEnabled()) {
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<Double> scores = new ArrayList<>(candidates.size());
        int anyOk = 0;
        for (byte[] b : candidates) {
            boolean ok = b != null && b.length > 0;
            if (ok) {
                anyOk++;
            }
            scores.add(ok ? imageCandidateJudge.score(b, userGoalText) : Double.NEGATIVE_INFINITY);
        }
        if (anyOk == 0) {
            return null;
        }
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < scores.size(); i++) {
            double s = scores.get(i);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        int tieCount = 0;
        for (double s : scores) {
            if (Double.compare(s, bestScore) == 0) {
                tieCount++;
            }
        }
        if (tieCount > 1) {
            return null;
        }
        return best;
    }

    private List<String> promptVariants(String basePrompt, int candidateCount) {
        List<String> out = new ArrayList<>(candidateCount);
        boolean diversify =
                candidateCount > 1 && appProperties.getImageStudio().isMultiCandidateDiversifyPrompt();
        for (int i = 0; i < candidateCount; i++) {
            if (!diversify || i == 0) {
                out.add(basePrompt);
            } else {
                out.add(
                        basePrompt
                                + "\n\n[Candidate variation "
                                + (i + 1)
                                + ": distinct composition, camera angle, and lighting mood from other candidates.]");
            }
        }
        return out;
    }

    private byte[] tryTextToImageQuiet(String prompt, String aspect, String imageSize) {
        try {
            return imageToolExecutor.nanoBananaTextToImage(prompt, aspect, imageSize);
        } catch (Exception e) {
            log.warn("Nano Banana 文生图（多路之一）失败: {}", e.getMessage());
            return null;
        }
    }

    private byte[] tryEditQuiet(
            String prompt, List<NanoBananaInlineImage> images, String aspectRatio, String imageSize) {
        try {
            return imageToolExecutor.nanoBananaEditImages(prompt, images, aspectRatio, imageSize);
        } catch (Exception e) {
            log.warn("Nano Banana 编辑（多路之一）失败: {}", e.getMessage());
            return null;
        }
    }
}
