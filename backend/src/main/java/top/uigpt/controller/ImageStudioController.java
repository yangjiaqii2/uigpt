package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.billing.ImageGenerationPointCosts;
import top.uigpt.dto.ImageStudioEditRequest;
import top.uigpt.dto.ImageStudioGenerateResponse;
import top.uigpt.dto.ImageStudioPairResponse;
import top.uigpt.dto.ImageStudioPromptOptimizeRequest;
import top.uigpt.dto.ImageStudioPromptOptimizeResponse;
import top.uigpt.dto.ImageStudioSlotResult;
import top.uigpt.dto.ImageStudioTextRequest;
import top.uigpt.entity.ChatConversationImage;
import top.uigpt.entity.ImageStudioSessionImage;
import top.uigpt.entity.User;
import top.uigpt.imagestudio.orchestration.ImageStudioNanoBananaOrchestrator;
import top.uigpt.imagestudio.orchestration.ImageToolExecutor;
import top.uigpt.imagestudio.orchestration.model.FinalImagePrompt;
import top.uigpt.repository.UserRepository;
import top.uigpt.service.ApiYiImageService;
import top.uigpt.service.ApiYiImageService.NanoBananaInlineImage;
import top.uigpt.service.ConversationImageService;
import top.uigpt.service.ImageStudioSessionService;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.ObjectStorageService;
import top.uigpt.service.PointsService;
import top.uigpt.service.RagService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 沉浸式图片创作工作台：代理 API 易 Nano Banana Pro（{@code gemini-3-pro-image-preview}）文生图 / 图片编辑。
 *
 * <p>需在环境变量中配置 {@code APIYI_API_KEY}；密钥仅存服务端。
 *
 * <p>作图 Prompt：拼上下文后由 {@link ImageStudioNanoBananaOrchestrator} 编排「意图 JSON → RAG → 英文 Prompt」，再调用 Gemini
 * {@code :generateContent}。
 */
@Slf4j
@RestController
@RequestMapping("/api/image-studio")
@RequiredArgsConstructor
public class ImageStudioController {

    private static final int MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024;

    private final UserRepository userRepository;
    private final PointsService pointsService;
    private final ApiYiImageService apiYiImageService;
    private final ConversationImageService conversationImageService;
    private final ObjectStorageService objectStorageService;
    private final RagService ragService;
    private final ImageStudioNanoBananaOrchestrator imageStudioNanoBananaOrchestrator;
    private final ImageToolExecutor imageToolExecutor;
    private final ImageStudioSessionService imageStudioSessionService;

    @PostMapping("/nano-banana/text-to-image")
    public ImageStudioGenerateResponse nanoBananaTextToImage(
            @Valid @RequestBody ImageStudioTextRequest body) {
        String username = requireUser();
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        FinalImagePrompt fp = imageStudioNanoBananaOrchestrator.buildFinalPromptForTextRequest(body);
        byte[] png =
                imageToolExecutor.nanoBananaTextToImage(
                        fp.promptForApi(), body.getAspectRatio(), body.getImageSize());
        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_txt2img");
        try {
            return buildPersistedResponse(
                    username,
                    png,
                    persistedUserPrompt(body.getUserDisplayPrompt(), body.getPrompt()),
                    body.getImageStudioSessionId(),
                    body.getStudioToolId());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_txt2img_refund");
            throw e;
        }
    }

    /**
     * 文生图多候选：Nano Banana 为 Gemini {@code generateContent}，无 OpenAI {@code n} 参数，故服务端并行多路（默认 2，可由
     * {@link ImageStudioTextRequest#getCandidateCount()} 指定，上限见 {@code uigpt.image-studio.pair-max-candidates}）。积分按「一次用户动作」仅扣
     * {@link ImageGenerationPointCosts#forNanoBananaImageSize} 一档（与单次文生图相同，非按张×N）。
     */
    @PostMapping("/nano-banana/text-to-image-pair")
    public ImageStudioPairResponse nanoBananaTextToImagePair(
            @Valid @RequestBody ImageStudioTextRequest body) {
        String username = requireUser();
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        int n = imageStudioNanoBananaOrchestrator.resolveParallelCandidateCount(body.getCandidateCount());
        FinalImagePrompt fp = imageStudioNanoBananaOrchestrator.buildFinalPromptForTextRequest(body);
        List<byte[]> raw =
                imageStudioNanoBananaOrchestrator.parallelTextToImage(
                        fp.promptForApi(), body.getAspectRatio(), body.getImageSize(), n);
        if (!anyNonEmpty(raw)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "多路文生图均失败，请稍后重试");
        }
        Integer recommended = imageStudioNanoBananaOrchestrator.selectRecommendedSlot(raw, body.getPrompt());

        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_txt2img_pair");
        try {
            return buildMultiSlotPairResponse(
                    username,
                    raw,
                    persistedUserPrompt(body.getUserDisplayPrompt(), body.getPrompt()),
                    recommended,
                    n,
                    true,
                    body.getImageStudioSessionId(),
                    body.getStudioToolId());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_txt2img_pair_refund");
            throw e;
        }
    }

    @PostMapping("/nano-banana/edit")
    public ImageStudioGenerateResponse nanoBananaEdit(
            @Valid @RequestBody ImageStudioEditRequest body) {
        String username = requireUser();
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        FinalImagePrompt fp = imageStudioNanoBananaOrchestrator.buildFinalPromptForEditRequest(body);
        List<NanoBananaInlineImage> list = decodeEditInlineImages(body);
        byte[] out =
                imageToolExecutor.nanoBananaEditImages(
                        fp.promptForApi(), list, body.getAspectRatio(), body.getImageSize());
        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_edit");
        try {
            return buildPersistedResponse(
                    username,
                    out,
                    persistedUserPrompt(body.getUserDisplayPrompt(), body.getPrompt()),
                    body.getImageStudioSessionId(),
                    body.getStudioToolId());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_edit_refund");
            throw e;
        }
    }

    /**
     * 图片编辑多候选：并行多路相同（或经多样化配置略调）的 prompt + 参考图。扣费规则同 {@link #nanoBananaTextToImagePair}。
     */
    @PostMapping("/nano-banana/edit-pair")
    public ImageStudioPairResponse nanoBananaEditPair(
            @Valid @RequestBody ImageStudioEditRequest body) {
        String username = requireUser();
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        int n = imageStudioNanoBananaOrchestrator.resolveParallelCandidateCount(body.getCandidateCount());
        FinalImagePrompt fp = imageStudioNanoBananaOrchestrator.buildFinalPromptForEditRequest(body);
        List<NanoBananaInlineImage> list = decodeEditInlineImages(body);
        List<byte[]> raw =
                imageStudioNanoBananaOrchestrator.parallelEdit(
                        fp.promptForApi(), list, body.getAspectRatio(), body.getImageSize(), n);
        if (!anyNonEmpty(raw)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "多路编辑均失败，请稍后重试");
        }
        Integer recommended = imageStudioNanoBananaOrchestrator.selectRecommendedSlot(raw, body.getPrompt());

        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_edit_pair");
        try {
            return buildMultiSlotPairResponse(
                    username,
                    raw,
                    persistedUserPrompt(body.getUserDisplayPrompt(), body.getPrompt()),
                    recommended,
                    n,
                    false,
                    body.getImageStudioSessionId(),
                    body.getStudioToolId());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_edit_pair_refund");
            throw e;
        }
    }

    /** 会话/作品库展示用：优先用户输入框原文，否则退回完整 API prompt。 */
    private static String persistedUserPrompt(String userDisplayPrompt, String apiPrompt) {
        if (userDisplayPrompt != null && !userDisplayPrompt.isBlank()) {
            return userDisplayPrompt.strip();
        }
        return apiPrompt == null ? "" : apiPrompt.strip();
    }

    private ImageStudioPairResponse buildMultiSlotPairResponse(
            String username,
            List<byte[]> raw,
            String userPrompt,
            Integer recommendedSlot,
            int expectedSlots,
            boolean textToImage,
            Long imageStudioSessionId,
            String studioToolId) {
        List<ImageStudioSlotResult> slots = new ArrayList<>(expectedSlots);
        boolean appended = false;
        for (int i = 0; i < expectedSlots; i++) {
            byte[] b = i < raw.size() ? raw.get(i) : null;
            boolean ok = b != null && b.length > 0;
            boolean appendChatArchive = imageStudioSessionId == null && ok && !appended;
            if (appendChatArchive) {
                appended = true;
            }
            slots.add(
                    persistSlot(
                            username,
                            b,
                            userPrompt,
                            ok,
                            appendChatArchive,
                            imageStudioSessionId,
                            studioToolId));
        }
        long okCount = slots.stream().filter(ImageStudioSlotResult::isOk).count();
        String partialHint = null;
        if (okCount > 0 && okCount < expectedSlots) {
            partialHint = textToImage ? "部分候选生成失败，已保留成功的结果。" : "部分候选编辑失败，已保留成功的结果。";
        }
        ImageStudioSlotResult first = slots.get(0);
        ImageStudioSlotResult second = slots.get(1);
        List<ImageStudioSlotResult> extras = expectedSlots > 2 ? slots.subList(2, expectedSlots) : null;
        return new ImageStudioPairResponse(first, second, partialHint, recommendedSlot, extras);
    }

    private static boolean anyNonEmpty(List<byte[]> raw) {
        for (byte[] b : raw) {
            if (b != null && b.length > 0) {
                return true;
            }
        }
        return false;
    }

    private void ensureCosForPersist() {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    objectStorageService.getUnavailableReason());
        }
    }

    private ImageStudioGenerateResponse buildPersistedResponse(
            String username, byte[] pngBytes, String userPrompt, Long imageStudioSessionId, String studioToolId) {
        if (imageStudioSessionId != null) {
            ImageStudioSessionImage row =
                    imageStudioSessionService.appendSessionImage(
                            username,
                            imageStudioSessionId,
                            pngBytes,
                            "image/png",
                            userPrompt,
                            studioToolId);
            String url = objectStorageService.browserReadableUrl(row.getObjectKey());
            return new ImageStudioGenerateResponse(
                    "image/png",
                    Base64.getEncoder().encodeToString(pngBytes),
                    row.getId(),
                    url,
                    false,
                    true);
        }
        ChatConversationImage row =
                conversationImageService.persistImageStudioGeneration(
                        username, pngBytes, "image/png", userPrompt, true, studioToolId);
        String url = objectStorageService.browserReadableUrl(row.getObjectKey());
        return new ImageStudioGenerateResponse(
                "image/png",
                Base64.getEncoder().encodeToString(pngBytes),
                row.getId(),
                url,
                row.isFavorite(),
                false);
    }

    private ImageStudioSlotResult persistSlot(
            String username,
            byte[] pngBytes,
            String userPrompt,
            boolean ok,
            boolean appendChatArchive,
            Long imageStudioSessionId,
            String studioToolId) {
        if (!ok || pngBytes == null || pngBytes.length == 0) {
            return new ImageStudioSlotResult(false, "本路无结果", null, null, null, null, false, false);
        }
        if (imageStudioSessionId != null) {
            ImageStudioSessionImage row =
                    imageStudioSessionService.appendSessionImage(
                            username,
                            imageStudioSessionId,
                            pngBytes,
                            "image/png",
                            userPrompt,
                            studioToolId);
            String url = objectStorageService.browserReadableUrl(row.getObjectKey());
            return new ImageStudioSlotResult(
                    true,
                    null,
                    "image/png",
                    Base64.getEncoder().encodeToString(pngBytes),
                    row.getId(),
                    url,
                    false,
                    true);
        }
        ChatConversationImage row =
                conversationImageService.persistImageStudioGeneration(
                        username, pngBytes, "image/png", userPrompt, appendChatArchive, studioToolId);
        String url = objectStorageService.browserReadableUrl(row.getObjectKey());
        return new ImageStudioSlotResult(
                true,
                null,
                "image/png",
                Base64.getEncoder().encodeToString(pngBytes),
                row.getId(),
                url,
                row.isFavorite(),
                false);
    }

    /** 调用 LLM 将简短描述扩写为更适合 Banana/Gemini 的提示词 */
    @PostMapping("/prompt/optimize")
    public ImageStudioPromptOptimizeResponse optimizePrompt(
            @Valid @RequestBody ImageStudioPromptOptimizeRequest body) {
        requireUser();
        String q = body.getPrompt() == null ? "" : body.getPrompt().strip();
        String forLlm = ragService.augmentPromptForImage(q, q, Boolean.TRUE, body.getRagCollection());
        String optimized =
                apiYiImageService.optimizeImageStudioPrompt(
                        forLlm,
                        body.getTool(),
                        body.getStyleLabel(),
                        body.getAspectLabel(),
                        body.getQualityLabel(),
                        body.getMedium());
        return new ImageStudioPromptOptimizeResponse(optimized);
    }

    private String requireUser() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return username;
    }

    private List<NanoBananaInlineImage> decodeEditInlineImages(ImageStudioEditRequest body) {
        List<NanoBananaInlineImage> list = new ArrayList<>();
        for (ImageStudioEditRequest.InlineImagePart p : body.getImages()) {
            byte[] bytes = decodeInlineBase64(p.getDataBase64());
            if (bytes.length > MAX_INLINE_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张参考图超过 8MB");
            }
            list.add(new NanoBananaInlineImage(p.getMimeType(), bytes));
        }
        return list;
    }

    private static byte[] decodeInlineBase64(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考图 Base64 为空");
        }
        String s = raw.strip();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(s.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考图 Base64 无效");
        }
    }
}
