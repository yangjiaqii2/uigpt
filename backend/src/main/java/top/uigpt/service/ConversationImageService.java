package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.billing.ImageGenerationPointCosts;
import top.uigpt.dto.ConversationErnieImageRequest;
import top.uigpt.dto.ConversationImageResponse;
import top.uigpt.entity.ChatConversation;
import top.uigpt.entity.ChatConversationImage;
import top.uigpt.entity.ChatMessageRow;
import top.uigpt.entity.User;
import top.uigpt.repository.ChatConversationImageRepository;
import top.uigpt.repository.ChatConversationRepository;
import top.uigpt.repository.ChatMessageRowRepository;
import top.uigpt.repository.UserRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.imageio.ImageIO;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

@Service
@RequiredArgsConstructor
public class ConversationImageService {

    private static final int SKILL_MAX = 32;
    private static final int MAX_LIMIT = 120;
    private static final int STUDIO_PROMPT_ARCHIVE_MAX = 6000;

    /** 图片创作工作台 · 写入 {@link ChatConversationImage#skillId}，与会话文生图区分 */
    public static final String IMAGE_STUDIO_SKILL_ID = "studio";
    /** 视频创作工作台 · Sora 2 等落库 {@link #persistVideoStudioGeneration} */
    public static final String VIDEO_STUDIO_SKILL_ID = "video-studio";

    /** {@link ChatConversation#studioChannel}：每用户一条图片工作台归档会话 */
    public static final String STUDIO_CHANNEL_IMAGE = "image-studio";
    /** {@link ChatConversation#studioChannel}：每用户一条视频工作台归档会话 */
    public static final String STUDIO_CHANNEL_VIDEO = "video-studio";

    private final UserRepository userRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatConversationImageRepository imageRepository;
    private final ChatMessageRowRepository messageRowRepository;
    private final ObjectStorageService objectStorageService;
    private final ApiYiImageService apiYiImageService;
    private final PointsService pointsService;

    public List<ConversationImageResponse> listForUser(
            String username, Long conversationId, int offset, int limit) {
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        int lim = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int off = Math.max(offset, 0);
        return imageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId).stream()
                .skip(off)
                .limit(lim)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ConversationImageResponse upload(
            String username,
            Long conversationId,
            int messageSortOrder,
            String skillId,
            MultipartFile file) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        String safeSkill = normalizeSkillId(skillId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片文件");
        }

        String orig = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image";
        String ext = extensionFromName(orig);
        String contentType =
                file.getContentType() != null && !file.getContentType().isBlank()
                        ? file.getContentType()
                        : "image/png";

        String objectKey;
        try {
            objectKey =
                    objectStorageService.putConversationObject(
                            conversationId,
                            ext,
                            file.getInputStream(),
                            file.getSize(),
                            contentType);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }

        String url = objectStorageService.publicUrl(objectKey);
        ChatConversationImage row = new ChatConversationImage();
        row.setConversationId(conversationId);
        row.setUserId(user.getId());
        row.setMessageSortOrder(messageSortOrder);
        row.setSkillId(safeSkill);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row = imageRepository.save(row);
        return toResponse(row);
    }

    /**
     * 会话内文生图：由助手回复与用户输入组装 prompt，经 APIYi 出图后写入 COS；响应 JSON 含 {@code b64_json} 便于前端即时展示。
     */
    @Transactional
    public ConversationImageResponse generateWithErnieImage(
            String username, Long conversationId, ConversationErnieImageRequest req) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        String safeSkill = normalizeSkillId(req.getSkillId());

        if (!apiYiImageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 APIYi 密钥（uigpt.api-yi-image.api-key，环境变量 APIYI_API_KEY 或 UIGPT_APIYI_API_KEY）。"
                            + " 可设 UIGPT_DOTENV_FILE=/绝对路径/.env，或将 .env 放在 jar 同级 / 仓库 backend/.env / 工作目录。");
        }

        String prompt =
                buildFastDirectImagePrompt(
                        req.getUserMessage(),
                        req.getAssistantReply(),
                        req.getAspectKey(),
                        req.getStyleLabel(),
                        safeSkill,
                        req.getImageConversationContext());

        byte[] bytes =
                apiYiImageService.generateImageFromTextPrompt(
                        prompt, req.getAspectKey(), req.getQualityTier());
        int imgCost = ImageGenerationPointCosts.forConversationImageQualityTier(req.getQualityTier());
        pointsService.assertAndDeduct(user.getId(), imgCost, "conversation_ernie_image");
        String contentType = sniffImageContentType(bytes);
        String remoteUrlForExt = "";

        String ext = guessImageExtension(contentType, remoteUrlForExt);
        String objectKey;
        try {
            objectKey =
                    objectStorageService.putConversationObject(
                            conversationId,
                            ext,
                            new ByteArrayInputStream(bytes),
                            bytes.length,
                            contentType);
        } catch (Exception e) {
            pointsService.refund(user.getId(), imgCost, "conversation_ernie_image_cos_fail");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "写入对象存储失败");
        }

        String url = objectStorageService.publicUrl(objectKey);
        ChatConversationImage row = new ChatConversationImage();
        row.setConversationId(conversationId);
        row.setUserId(user.getId());
        row.setMessageSortOrder(req.getMessageSortOrder());
        row.setSkillId(safeSkill);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row = imageRepository.save(row);
        return toResponseWithB64(row, bytes, contentType);
    }

    /**
     * 会话内局部重绘：从 COS 读取原图，将用户蒙版缩放至原图尺寸后调用 APIYi {@code /v1/images/edits}，结果写入 COS。
     *
     * <p>UX 约定由前端保证：用户先涂抹蒙版、再填写提示词后提交；本方法仅校验蒙版非空且含透明重绘区。
     */
    @Transactional
    public ConversationImageResponse generateInpaintWithErnie(
            String username,
            Long conversationId,
            int messageSortOrder,
            String skillId,
            String userMessage,
            String assistantReply,
            Long sourceImageId,
            String aspectKey,
            String styleLabel,
            String qualityTier,
            String imageConversationContext,
            byte[] maskPngBytes) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        String safeSkill = normalizeSkillId(skillId);

        if (!apiYiImageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 APIYi 密钥（uigpt.api-yi-image.api-key，环境变量 APIYI_API_KEY 或 UIGPT_APIYI_API_KEY）。"
                            + " 可设 UIGPT_DOTENV_FILE=/绝对路径/.env，或将 .env 放在 jar 同级 / 仓库 backend/.env / 工作目录。");
        }
        if (maskPngBytes == null || maskPngBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传重绘蒙版（PNG）");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "局部重绘提示词不能为空");
        }

        ChatConversationImage src =
                imageRepository
                        .findById(sourceImageId)
                        .filter(
                                r ->
                                        Objects.equals(conversationId, r.getConversationId())
                                                && user.getId().equals(r.getUserId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "原图不存在或不属于本会话"));

        byte[] imageBytes = objectStorageService.getObjectBytes(src.getObjectKey());
        BufferedImage baseImg;
        BufferedImage maskImg;
        try {
            baseImg = ImageIO.read(new ByteArrayInputStream(imageBytes));
            maskImg = ImageIO.read(new ByteArrayInputStream(maskPngBytes));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析图片或蒙版");
        }
        if (baseImg == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取原图");
        }
        if (maskImg == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "蒙版须为有效 PNG");
        }
        assertMaskHasInpaintRegion(maskImg);
        byte[] maskForApi;
        try {
            maskForApi = resizeMaskPngToMatchImage(baseImg, maskImg);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理蒙版失败");
        }

        String um = userMessage.strip();
        String ar = assistantReply == null ? "" : assistantReply.strip();
        String combined = um;
        if (!ar.isBlank()) {
            String clip = ar.length() > 1200 ? ar.substring(0, 1200) + "…" : ar;
            combined = um + "\n\n【本轮助手画面描述（供参考）】\n" + clip;
        }
        String prompt =
                buildFastDirectImagePrompt(
                        combined, null, aspectKey, styleLabel, safeSkill, imageConversationContext);
        String ext = guessImageExtension(sniffImageContentType(imageBytes), "");
        String fn = "source" + (ext.startsWith(".") ? ext : ".png");
        byte[] outBytes =
                apiYiImageService.editImageWithMask(
                        imageBytes,
                        fn,
                        sniffImageContentType(imageBytes),
                        maskForApi,
                        prompt,
                        "b64_json");
        int imgCost = ImageGenerationPointCosts.forConversationImageQualityTier(qualityTier);
        pointsService.assertAndDeduct(user.getId(), imgCost, "conversation_ernie_image");
        String outContentType = sniffImageContentType(outBytes);
        String remoteUrlForExt = "";

        String outExt = guessImageExtension(outContentType, remoteUrlForExt);
        String objectKey;
        try {
            objectKey =
                    objectStorageService.putConversationObject(
                            conversationId,
                            outExt,
                            new ByteArrayInputStream(outBytes),
                            outBytes.length,
                            outContentType);
        } catch (Exception e) {
            pointsService.refund(user.getId(), imgCost, "conversation_ernie_image_cos_fail");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "写入对象存储失败");
        }

        String url = objectStorageService.publicUrl(objectKey);
        ChatConversationImage row = new ChatConversationImage();
        row.setConversationId(conversationId);
        row.setUserId(user.getId());
        row.setMessageSortOrder(messageSortOrder);
        row.setSkillId(safeSkill);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row = imageRepository.save(row);
        return toResponseWithB64(row, outBytes, outContentType);
    }

    /**
     * 工作台 Nano Banana 生成结果：写入 COS {@code conv/{conversationId}/…}，绑定「图片工作台」归档会话并写入
     * {@code chat_messages}。
     */
    @Transactional
    public ChatConversationImage persistImageStudioGeneration(String username, byte[] imageBytes) {
        return persistImageStudioGeneration(username, imageBytes, null, null);
    }

    @Transactional
    public ChatConversationImage persistImageStudioGeneration(
            String username, byte[] imageBytes, String mimeTypeHint) {
        return persistImageStudioGeneration(username, imageBytes, mimeTypeHint, null, true);
    }

    @Transactional
    public ChatConversationImage persistImageStudioGeneration(
            String username, byte[] imageBytes, String mimeTypeHint, String userPrompt) {
        return persistImageStudioGeneration(username, imageBytes, mimeTypeHint, userPrompt, true);
    }

    /**
     * @param appendChatArchive 为 false 时仅写 COS/图片表，不向工作台归档会话追加 user/assistant 消息（用于同一次动作的第二张候选，避免重复「用户提示」气泡）。
     */
    @Transactional
    public ChatConversationImage persistImageStudioGeneration(
            String username,
            byte[] imageBytes,
            String mimeTypeHint,
            String userPrompt,
            boolean appendChatArchive) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生成结果为空");
        }
        ChatConversation conv =
                getOrCreateStudioConversation(user, STUDIO_CHANNEL_IMAGE, "图片创作工作台");
        String contentType =
                mimeTypeHint != null && !mimeTypeHint.isBlank()
                        ? mimeTypeHint.strip()
                        : sniffImageContentType(imageBytes);
        String ext = guessImageExtension(contentType, "");
        int baseOrder = messageRowRepository.maxSortOrderByConversationId(conv.getId());
        int assistantOrder = baseOrder + 2;

        String objectKey;
        try {
            objectKey =
                    objectStorageService.putConversationObject(
                            conv.getId(),
                            ext,
                            new ByteArrayInputStream(imageBytes),
                            imageBytes.length,
                            contentType);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "写入对象存储失败");
        }

        String url = objectStorageService.publicUrl(objectKey);
        ChatConversationImage row = new ChatConversationImage();
        row.setConversationId(conv.getId());
        row.setUserId(user.getId());
        row.setMessageSortOrder(assistantOrder);
        row.setSkillId(IMAGE_STUDIO_SKILL_ID);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row.setFavorite(false);
        row = imageRepository.save(row);

        if (appendChatArchive) {
            String userLine = studioUserArchiveLine(userPrompt, "图片工作台");
            String assistantLine =
                    "已生成图片并保存至对象存储。\n预览链接："
                            + objectStorageService.browserReadableUrl(objectKey)
                            + "\n作品记录 ID："
                            + row.getId();
            appendStudioExchange(conv, userLine, assistantLine);
        }
        return row;
    }

    /**
     * 视频工作台：写入 COS {@code conv/{conversationId}/…}，{@link ChatConversationImage#skillId} 为 {@link
     * #VIDEO_STUDIO_SKILL_ID}，并写入「视频工作台」归档会话消息。
     */
    @Transactional
    public ChatConversationImage persistVideoStudioGeneration(String username, byte[] mp4Bytes) {
        return persistVideoStudioGeneration(username, mp4Bytes, null);
    }

    @Transactional
    public ChatConversationImage persistVideoStudioGeneration(
            String username, byte[] mp4Bytes, String userPrompt) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        if (mp4Bytes == null || mp4Bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生成视频为空");
        }
        ChatConversation conv =
                getOrCreateStudioConversation(user, STUDIO_CHANNEL_VIDEO, "视频创作工作台");
        String contentType = "video/mp4";
        String ext = guessImageExtension(contentType, "");
        int baseOrder = messageRowRepository.maxSortOrderByConversationId(conv.getId());
        int assistantOrder = baseOrder + 2;

        String objectKey;
        try {
            objectKey =
                    objectStorageService.putConversationObject(
                            conv.getId(),
                            ext,
                            new ByteArrayInputStream(mp4Bytes),
                            mp4Bytes.length,
                            contentType);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "写入对象存储失败");
        }

        String url = objectStorageService.publicUrl(objectKey);
        ChatConversationImage row = new ChatConversationImage();
        row.setConversationId(conv.getId());
        row.setUserId(user.getId());
        row.setMessageSortOrder(assistantOrder);
        row.setSkillId(VIDEO_STUDIO_SKILL_ID);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row.setFavorite(false);
        row = imageRepository.save(row);

        String userLine = studioUserArchiveLine(userPrompt, "视频工作台");
        String assistantLine =
                "已生成视频并保存至对象存储。\n播放链接："
                        + objectStorageService.browserReadableUrl(objectKey)
                        + "\n作品记录 ID："
                        + row.getId();
        appendStudioExchange(conv, userLine, assistantLine);
        return row;
    }

    private ChatConversation getOrCreateStudioConversation(User user, String channel, String title) {
        var existing = conversationRepository.findByUserIdAndStudioChannel(user.getId(), channel);
        if (existing.isPresent()) {
            return existing.get();
        }
        ChatConversation c = new ChatConversation();
        c.setUserId(user.getId());
        c.setTitle(title);
        c.setStudioChannel(channel);
        try {
            return conversationRepository.save(c);
        } catch (DataIntegrityViolationException ex) {
            return conversationRepository
                    .findByUserIdAndStudioChannel(user.getId(), channel)
                    .orElseThrow(() -> ex);
        }
    }

    private void appendStudioExchange(
            ChatConversation conv, String userContent, String assistantContent) {
        int base = messageRowRepository.maxSortOrderByConversationId(conv.getId());
        int uOrd = base + 1;
        int aOrd = base + 2;
        ChatMessageRow userRow = new ChatMessageRow();
        userRow.setConversationId(conv.getId());
        userRow.setRole("user");
        userRow.setContent(userContent);
        userRow.setSortOrder(uOrd);
        messageRowRepository.save(userRow);
        ChatMessageRow asstRow = new ChatMessageRow();
        asstRow.setConversationId(conv.getId());
        asstRow.setRole("assistant");
        asstRow.setContent(assistantContent);
        asstRow.setSortOrder(aOrd);
        messageRowRepository.save(asstRow);
        conversationRepository.save(conv);
    }

    private static String studioUserArchiveLine(String prompt, String studioLabel) {
        String p = truncateStudioPrompt(prompt);
        if (p.isEmpty()) {
            return "【" + studioLabel + "】生成请求（未附带提示词归档）";
        }
        return "【" + studioLabel + "】" + p;
    }

    private static String truncateStudioPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String s = prompt.strip();
        if (s.length() <= STUDIO_PROMPT_ARCHIVE_MAX) {
            return s;
        }
        return s.substring(0, STUDIO_PROMPT_ARCHIVE_MAX) + "…";
    }

    /**
     * 高速出图：不经过千问二次改写，用助手对本轮的描述直接驱动 APIYi；正文优先 {@code assistantReply}，否则退回用户原话。
     *
     * @param imageConversationContext 可选，近期会话摘录，置于正文前供连续出图/再编辑对齐设定
     */
    private static String buildFastDirectImagePrompt(
            String userMessage,
            String assistantReply,
            String aspectKey,
            String styleLabel,
            String skillId,
            String imageConversationContext) {
        String ar = assistantReply == null ? "" : assistantReply.strip();
        String um = userMessage == null ? "" : userMessage.strip();
        String body = !ar.isBlank() ? ar : um;
        if (body.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "高速出图需要助手回复或用户消息中至少一方包含内容");
        }
        String aspect = aspectKey == null || aspectKey.isBlank() ? "1:1" : aspectKey.strip();
        String style = styleLabel == null || styleLabel.isBlank() ? "" : styleLabel.strip();
        String skill = skillId == null || skillId.isBlank() ? "" : skillId.strip();

        final int maxBody = 7500;
        if (body.length() > maxBody) {
            body = body.substring(0, maxBody) + "…";
        }

        String ctx = imageConversationContext == null ? "" : imageConversationContext.strip();
        final int maxCtx = 7000;
        if (ctx.length() > maxCtx) {
            ctx = ctx.substring(0, maxCtx) + "…";
        }

        StringBuilder sb = new StringBuilder();
        if (!ctx.isBlank()) {
            sb.append("【近期对话上下文】\n").append(ctx).append("\n\n");
        }
        sb.append(body);
        sb.append("\n\n——\n【出图约束】比例 ").append(aspect);
        if (!style.isEmpty()) {
            sb.append("；风格/标签：").append(style);
        }
        if (!skill.isEmpty()) {
            sb.append("；技能类型：").append(skill);
        }
        sb.append("；画面如出现文字请使用简体中文。");
        if (!ar.isBlank() && !um.isBlank() && um.length() <= 1200) {
            String ref = um.length() > 800 ? um.substring(0, 800) + "…" : um;
            sb.append("\n【用户原话参考】").append(ref);
        }
        String out = sb.toString();
        final int maxTotal = 14000;
        if (out.length() > maxTotal) {
            out = out.substring(0, maxTotal) + "…";
        }
        return out;
    }

    /** OpenAI edits：透明区域为需重绘部分。 */
    private static void assertMaskHasInpaintRegion(BufferedImage mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int step = Math.max(1, Math.min(w, h) / 256);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int a = (mask.getRGB(x, y) >>> 24) & 0xff;
                if (a < 253) {
                    return;
                }
            }
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "请先在图上涂抹需要重绘的区域");
    }

    /** 将蒙版缩放为与原图像素尺寸一致；宽高比偏差过大则拒绝以免选区错位。 */
    private static byte[] resizeMaskPngToMatchImage(BufferedImage base, BufferedImage maskSrc)
            throws IOException {
        if (base.getWidth() == maskSrc.getWidth() && base.getHeight() == maskSrc.getHeight()) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ImageIO.write(maskSrc, "png", bout);
            return bout.toByteArray();
        }
        double ra = (double) base.getWidth() / base.getHeight();
        double rb = (double) maskSrc.getWidth() / maskSrc.getHeight();
        if (Math.abs(ra - rb) / Math.max(ra, rb) > 0.04) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "蒙版与图片宽高比不一致，请关闭后重试");
        }
        BufferedImage scaled =
                new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(maskSrc, 0, 0, base.getWidth(), base.getHeight(), null);
        g.dispose();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", bout);
        return bout.toByteArray();
    }

    private static String sniffImageContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            return "image/png";
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F') {
            return "image/webp";
        }
        return "image/png";
    }

    private static String guessImageExtension(String contentType, String remoteUrl) {
        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (ct.contains("mp4")) {
            return ".mp4";
        }
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        String u = remoteUrl.toLowerCase(Locale.ROOT);
        if (u.contains(".png")) {
            return ".png";
        }
        if (u.contains(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank() || skillId.length() > SKILL_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillId 无效");
        }
        String safe = skillId.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (safe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillId 无效");
        }
        return safe;
    }

    /**
     * 删除会话前：未收藏图从 COS 与库中移除；收藏图保留对象，仅与会话解绑（conversation_id 置空）。
     */
    @Transactional
    public void detachOrRemoveImagesBeforeConversationDelete(ChatConversation conv) {
        Long convId = conv.getId();
        Long ownerId = conv.getUserId();
        ArrayList<ChatConversationImage> rows =
                new ArrayList<>(imageRepository.findByConversationIdOrderByCreatedAtDesc(convId));
        for (ChatConversationImage img : rows) {
            if (img.isFavorite()) {
                img.setConversationId(null);
                if (img.getUserId() == null) {
                    img.setUserId(ownerId);
                }
                imageRepository.save(img);
            } else {
                objectStorageService.remove(img.getObjectKey());
                imageRepository.delete(img);
            }
        }
    }

    /**
     * 当前用户对自己的图片设置收藏（含会话已删除后的 orphan 图）。
     * 若本会话已不存在（conversation_id 为空）且取消收藏，则同步删除 COS 对象与库记录。
     */
    @Transactional
    public ConversationImageResponse setFavoriteForOwner(
            String username, Long imageId, boolean favorite) {
        User user = requireUser(username);
        ChatConversationImage img =
                imageRepository
                        .findById(imageId)
                        .filter(r -> user.getId().equals(r.getUserId()))
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"));
        if (!favorite
                && img.getConversationId() == null
                && !IMAGE_STUDIO_SKILL_ID.equals(img.getSkillId())
                && !VIDEO_STUDIO_SKILL_ID.equals(img.getSkillId())) {
            objectStorageService.remove(img.getObjectKey());
            imageRepository.delete(img);
            return null;
        }
        img.setFavorite(favorite);
        imageRepository.save(img);
        return toResponse(img);
    }

    @Transactional
    public void delete(String username, Long conversationId, Long imageId) {
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        ChatConversationImage img =
                imageRepository
                        .findById(imageId)
                        .filter(
                                r ->
                                        Objects.equals(conversationId, r.getConversationId())
                                                && user.getId().equals(r.getUserId()))
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"));
        objectStorageService.remove(img.getObjectKey());
        imageRepository.delete(img);
    }

    /** 当前用户删除自己名下的图片（含工作台归档、会话已删除后的 orphan 等）。 */
    @Transactional
    public void deleteImageForOwner(String username, Long imageId) {
        User user = requireUser(username);
        ChatConversationImage img =
                imageRepository
                        .findById(imageId)
                        .filter(r -> user.getId().equals(r.getUserId()))
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"));
        objectStorageService.remove(img.getObjectKey());
        imageRepository.delete(img);
    }

    @Transactional
    public ConversationImageResponse setFavorite(
            String username, Long conversationId, Long imageId, boolean favorite) {
        User user = requireUser(username);
        requireConversation(conversationId, user.getId());
        ChatConversationImage img =
                imageRepository
                        .findById(imageId)
                        .filter(
                                r ->
                                        Objects.equals(conversationId, r.getConversationId())
                                                && user.getId().equals(r.getUserId()))
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"));
        img.setFavorite(favorite);
        imageRepository.save(img);
        return toResponse(img);
    }

    private ConversationImageResponse toResponse(ChatConversationImage row) {
        return new ConversationImageResponse(
                row.getId(),
                row.getMessageSortOrder(),
                row.getSkillId(),
                objectStorageService.browserReadableUrl(row.getObjectKey()),
                row.getCreatedAt(),
                row.isFavorite(),
                null,
                null);
    }

    /** 高速线路：在 JSON 中附带 {@code b64_json}（仍写入 COS，{@code imageUrl} 照旧）。 */
    private ConversationImageResponse toResponseWithB64(
            ChatConversationImage row, byte[] imageBytes, String mimeType) {
        String mt =
                mimeType != null && !mimeType.isBlank()
                        ? mimeType.strip()
                        : sniffImageContentType(imageBytes);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        return new ConversationImageResponse(
                row.getId(),
                row.getMessageSortOrder(),
                row.getSkillId(),
                objectStorageService.browserReadableUrl(row.getObjectKey()),
                row.getCreatedAt(),
                row.isFavorite(),
                b64,
                mt);
    }

    private User requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return userRepository
                .findByUsername(username)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
    }

    private ChatConversation requireConversation(Long conversationId, Long userId) {
        return conversationRepository
                .findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private static String extensionFromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return ".png";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
