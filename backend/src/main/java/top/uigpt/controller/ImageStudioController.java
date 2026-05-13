package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
import top.uigpt.entity.User;
import top.uigpt.repository.UserRepository;
import top.uigpt.service.ApiYiImageService;
import top.uigpt.service.ApiYiImageService.NanoBananaInlineImage;
import top.uigpt.service.ConversationImageService;
import top.uigpt.service.JwtService;
import top.uigpt.service.ObjectStorageService;
import top.uigpt.service.ImageStudioGenerationPipeline;
import top.uigpt.service.PointsService;
import top.uigpt.service.RagService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 沉浸式图片创作工作台：代理 API 易 Nano Banana Pro（{@code gemini-3-pro-image-preview}）文生图 / 图片编辑。
 *
 * <p>需在环境变量中配置 {@code APIYI_API_KEY}；密钥仅存服务端。
 *
 * <p>作图 Prompt：拼上下文后依次执行「意图 JSON → RAG 检索 → 英文 Prompt 组装」（{@link ImageStudioGenerationPipeline}），再调用
 * Gemini {@code :generateContent}。
 */
@Slf4j
@RestController
@RequestMapping("/api/image-studio")
@RequiredArgsConstructor
public class ImageStudioController {

    private static final int MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024;

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PointsService pointsService;
    private final ApiYiImageService apiYiImageService;
    private final ConversationImageService conversationImageService;
    private final ObjectStorageService objectStorageService;
    private final RagService ragService;
    private final ImageStudioGenerationPipeline imageStudioGenerationPipeline;

    @PostMapping("/nano-banana/text-to-image")
    public ImageStudioGenerateResponse nanoBananaTextToImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ImageStudioTextRequest body) {
        String username = requireUser(authorization);
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        String merged = mergeImageSessionContextForApi(body.getPrompt(), body.getImageSessionContext());
        String ragQuery = body.getPrompt() == null ? "" : body.getPrompt().strip();
        String promptForApi =
                imageStudioGenerationPipeline.buildNanoBananaPrompt(
                        merged, ragQuery, body.getAspectRatio(), body.getImageSize(), body.getRagCollection());
        byte[] png =
                apiYiImageService.nanoBananaTextToImage(
                        promptForApi, body.getAspectRatio(), body.getImageSize());
        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_txt2img");
        try {
            return buildPersistedResponse(username, png, body.getPrompt());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_txt2img_refund");
            throw e;
        }
    }

    /**
     * 文生图双候选：Nano Banana 为 Gemini {@code generateContent}，无 OpenAI {@code n} 参数，故服务端并行两次相同
     * prompt。积分按「一次用户动作」仅扣 {@link ImageGenerationPointCosts#forNanoBananaImageSize} 一档（与单次文生图相同，
     * 非按张×2）。
     */
    @PostMapping("/nano-banana/text-to-image-pair")
    public ImageStudioPairResponse nanoBananaTextToImagePair(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ImageStudioTextRequest body) {
        String username = requireUser(authorization);
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        String merged = mergeImageSessionContextForApi(body.getPrompt(), body.getImageSessionContext());
        String ragQuery = body.getPrompt() == null ? "" : body.getPrompt().strip();
        String promptForApi =
                imageStudioGenerationPipeline.buildNanoBananaPrompt(
                        merged, ragQuery, body.getAspectRatio(), body.getImageSize(), body.getRagCollection());
        String aspect = body.getAspectRatio();
        String imageSize = body.getImageSize();

        CompletableFuture<byte[]> f1 =
                CompletableFuture.supplyAsync(() -> tryNanoBananaTextToImageQuiet(promptForApi, aspect, imageSize));
        CompletableFuture<byte[]> f2 =
                CompletableFuture.supplyAsync(() -> tryNanoBananaTextToImageQuiet(promptForApi, aspect, imageSize));
        byte[] b1 = f1.join();
        byte[] b2 = f2.join();
        boolean ok1 = b1 != null && b1.length > 0;
        boolean ok2 = b2 != null && b2.length > 0;
        if (!ok1 && !ok2) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "两路文生图均失败，请稍后重试");
        }

        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_txt2img_pair");
        try {
            boolean appendFirst = ok1;
            boolean appendSecond = ok2 && !ok1;
            ImageStudioSlotResult first = persistSlot(username, b1, body.getPrompt(), ok1, appendFirst);
            ImageStudioSlotResult second = persistSlot(username, b2, body.getPrompt(), ok2, appendSecond);
            String partialHint = null;
            if (ok1 != ok2) {
                partialHint = "另一路生成失败，已保留成功的一张。";
            }
            return new ImageStudioPairResponse(first, second, partialHint);
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_txt2img_pair_refund");
            throw e;
        }
    }

    @PostMapping("/nano-banana/edit")
    public ImageStudioGenerateResponse nanoBananaEdit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ImageStudioEditRequest body) {
        String username = requireUser(authorization);
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        String merged = mergeImageSessionContextForApi(body.getPrompt(), body.getImageSessionContext());
        String ragQuery = body.getPrompt() == null ? "" : body.getPrompt().strip();
        String promptForApi =
                imageStudioGenerationPipeline.buildNanoBananaPrompt(
                        merged, ragQuery, body.getAspectRatio(), body.getImageSize(), body.getRagCollection());
        List<NanoBananaInlineImage> list = new ArrayList<>();
        for (ImageStudioEditRequest.InlineImagePart p : body.getImages()) {
            byte[] bytes = decodeInlineBase64(p.getDataBase64());
            if (bytes.length > MAX_INLINE_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张参考图超过 8MB");
            }
            list.add(new NanoBananaInlineImage(p.getMimeType(), bytes));
        }
        byte[] out =
                apiYiImageService.nanoBananaEditImages(
                        promptForApi, list, body.getAspectRatio(), body.getImageSize());
        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_edit");
        try {
            return buildPersistedResponse(username, out, body.getPrompt());
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_edit_refund");
            throw e;
        }
    }

    /**
     * 图片编辑双候选：并行两次相同 prompt + 参考图。扣费规则同 {@link #nanoBananaTextToImagePair}。
     */
    @PostMapping("/nano-banana/edit-pair")
    public ImageStudioPairResponse nanoBananaEditPair(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ImageStudioEditRequest body) {
        String username = requireUser(authorization);
        ensureCosForPersist();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        int cost = ImageGenerationPointCosts.forNanoBananaImageSize(body.getImageSize());
        String merged = mergeImageSessionContextForApi(body.getPrompt(), body.getImageSessionContext());
        String ragQuery = body.getPrompt() == null ? "" : body.getPrompt().strip();
        String promptForApi =
                imageStudioGenerationPipeline.buildNanoBananaPrompt(
                        merged, ragQuery, body.getAspectRatio(), body.getImageSize(), body.getRagCollection());
        List<NanoBananaInlineImage> list = new ArrayList<>();
        for (ImageStudioEditRequest.InlineImagePart p : body.getImages()) {
            byte[] bytes = decodeInlineBase64(p.getDataBase64());
            if (bytes.length > MAX_INLINE_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张参考图超过 8MB");
            }
            list.add(new NanoBananaInlineImage(p.getMimeType(), bytes));
        }

        CompletableFuture<byte[]> f1 =
                CompletableFuture.supplyAsync(
                        () -> tryNanoBananaEdit(promptForApi, list, body.getAspectRatio(), body.getImageSize()));
        CompletableFuture<byte[]> f2 =
                CompletableFuture.supplyAsync(
                        () -> tryNanoBananaEdit(promptForApi, list, body.getAspectRatio(), body.getImageSize()));
        byte[] b1 = f1.join();
        byte[] b2 = f2.join();
        boolean ok1 = b1 != null && b1.length > 0;
        boolean ok2 = b2 != null && b2.length > 0;
        if (!ok1 && !ok2) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "两路编辑均失败，请稍后重试");
        }

        pointsService.assertAndDeduct(user.getId(), cost, "image_studio_edit_pair");
        try {
            boolean appendFirst = ok1;
            boolean appendSecond = ok2 && !ok1;
            ImageStudioSlotResult first = persistSlot(username, b1, body.getPrompt(), ok1, appendFirst);
            ImageStudioSlotResult second = persistSlot(username, b2, body.getPrompt(), ok2, appendSecond);
            String partialHint = null;
            if (ok1 != ok2) {
                partialHint = "另一路编辑失败，已保留成功的一张。";
            }
            return new ImageStudioPairResponse(first, second, partialHint);
        } catch (RuntimeException e) {
            pointsService.refund(user.getId(), cost, "image_studio_edit_pair_refund");
            throw e;
        }
    }

    private void ensureCosForPersist() {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    objectStorageService.getUnavailableReason());
        }
    }

    private ImageStudioGenerateResponse buildPersistedResponse(
            String username, byte[] pngBytes, String userPrompt) {
        ChatConversationImage row =
                conversationImageService.persistImageStudioGeneration(
                        username, pngBytes, "image/png", userPrompt);
        String url = objectStorageService.browserReadableUrl(row.getObjectKey());
        return new ImageStudioGenerateResponse(
                "image/png",
                Base64.getEncoder().encodeToString(pngBytes),
                row.getId(),
                url,
                row.isFavorite());
    }

    private byte[] tryNanoBananaTextToImageQuiet(String prompt, String aspect, String imageSize) {
        try {
            return apiYiImageService.nanoBananaTextToImage(prompt, aspect, imageSize);
        } catch (Exception e) {
            log.warn("Nano Banana 文生图（双路之一）失败: {}", e.getMessage());
            return null;
        }
    }

    private byte[] tryNanoBananaEdit(
            String prompt, List<NanoBananaInlineImage> images, String aspectRatio, String imageSize) {
        try {
            return apiYiImageService.nanoBananaEditImages(prompt, images, aspectRatio, imageSize);
        } catch (Exception e) {
            log.warn("Nano Banana 编辑（双路之一）失败: {}", e.getMessage());
            return null;
        }
    }

    private ImageStudioSlotResult persistSlot(
            String username, byte[] pngBytes, String userPrompt, boolean ok, boolean appendChatArchive) {
        if (!ok || pngBytes == null || pngBytes.length == 0) {
            return new ImageStudioSlotResult(false, "本路无结果", null, null, null, null, false);
        }
        ChatConversationImage row =
                conversationImageService.persistImageStudioGeneration(
                        username, pngBytes, "image/png", userPrompt, appendChatArchive);
        String url = objectStorageService.browserReadableUrl(row.getObjectKey());
        return new ImageStudioSlotResult(
                true,
                null,
                "image/png",
                Base64.getEncoder().encodeToString(pngBytes),
                row.getId(),
                url,
                row.isFavorite());
    }

    /** 调用 LLM 将简短描述扩写为更适合 Banana/Gemini 的提示词 */
    @PostMapping("/prompt/optimize")
    public ImageStudioPromptOptimizeResponse optimizePrompt(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ImageStudioPromptOptimizeRequest body) {
        requireUser(authorization);
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

    private String requireUser(String authorization) {
        String username = jwtService.parseUsername(authorization);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return username;
    }

    /**
     * 将本页多轮摘要拼入模型 prompt；落库仍只用用户输入的 {@code userPrompt}。
     */
    private static String mergeImageSessionContextForApi(String userPrompt, String sessionContext) {
        String p = userPrompt == null ? "" : userPrompt.strip();
        String c = sessionContext == null ? "" : sessionContext.strip();
        if (c.isEmpty()) {
            return p;
        }
        final int cap = 8000;
        if (c.length() > cap) {
            c = c.substring(0, cap) + "…";
        }
        if (p.isEmpty()) {
            return "【本页创作上下文】\n" + c;
        }
        return "【本页创作上下文】\n" + c + "\n\n——\n【本次指令】\n" + p;
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
