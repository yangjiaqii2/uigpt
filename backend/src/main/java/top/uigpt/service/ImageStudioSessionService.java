package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ImageStudioSessionDetailDto;
import top.uigpt.dto.ImageStudioSessionImageDto;
import top.uigpt.dto.ImageStudioSessionImageInlineDto;
import top.uigpt.dto.ImageStudioSessionSummaryDto;
import top.uigpt.entity.ImageStudioSession;
import top.uigpt.entity.ImageStudioSessionImage;
import top.uigpt.entity.User;
import top.uigpt.imagestudio.ImageStudioSkillIds;
import top.uigpt.repository.ImageStudioSessionImageRepository;
import top.uigpt.repository.ImageStudioSessionRepository;
import top.uigpt.repository.UserRepository;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageStudioSessionService {

    /** 与会话内编辑接口单图上限对齐，避免超大图撑爆内存与下游。 */
    private static final int MAX_SESSION_IMAGE_INLINE_BYTES = 8 * 1024 * 1024;

    private final ImageStudioSessionRepository sessionRepository;
    private final ImageStudioSessionImageRepository imageRepository;
    private final UserRepository userRepository;
    private final ObjectStorageService objectStorageService;
    private final ConversationImageService conversationImageService;

    @Transactional(readOnly = true)
    public Page<ImageStudioSessionSummaryDto> listSessions(String username, Pageable pageable) {
        User user = requireUser(username);
        Page<ImageStudioSession> page = sessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId(), pageable);
        return page.map(
                s ->
                        new ImageStudioSessionSummaryDto(
                                s.getId(),
                                s.getTitle(),
                                s.getUpdatedAt(),
                                s.getImageCount(),
                                s.getLastImageUrl(),
                                s.getStudioSkillId()));
    }

    @Transactional
    public ImageStudioSession createSession(String username) {
        User user = requireUser(username);
        ImageStudioSession s = new ImageStudioSession();
        s.setUserId(user.getId());
        s.setTitle("新会话");
        s.setContextText(null);
        s.setImageCount(0);
        s.setLastImageUrl(null);
        s.setStudioSkillId(ImageStudioSkillIds.UNIVERSAL_MASTER);
        return sessionRepository.save(s);
    }

    @Transactional(readOnly = true)
    public ImageStudioSessionDetailDto getSessionDetail(String username, long sessionId) {
        User user = requireUser(username);
        ImageStudioSession s =
                sessionRepository
                        .findByIdAndUserId(sessionId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        List<ImageStudioSessionImage> rows = imageRepository.findBySessionIdOrderBySortOrderAsc(sessionId);
        List<ImageStudioSessionImageDto> imgs = new ArrayList<>(rows.size());
        for (ImageStudioSessionImage r : rows) {
            imgs.add(
                    new ImageStudioSessionImageDto(
                            r.getId(),
                            objectStorageService.browserReadableUrl(r.getObjectKey()),
                            r.getUserPrompt(),
                            r.getCreatedAt()));
        }
        return new ImageStudioSessionDetailDto(
                s.getId(), s.getTitle(), s.getContextText(), s.getStudioSkillId(), imgs);
    }

    @Transactional
    public ImageStudioSessionDetailDto patchSession(
            String username, long sessionId, String contextText, String title, String studioSkillId) {
        User user = requireUser(username);
        ImageStudioSession s =
                sessionRepository
                        .findByIdAndUserId(sessionId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (contextText != null) {
            s.setContextText(contextText.isBlank() ? null : contextText);
        }
        if (title != null) {
            String t = title.strip();
            if (!t.isEmpty()) {
                s.setTitle(t.length() > 255 ? t.substring(0, 255) : t);
            }
        }
        if (studioSkillId != null) {
            String sk = studioSkillId.strip();
            if (!sk.isEmpty()) {
                s.setStudioSkillId(ImageStudioSkillIds.normalize(sk));
            }
        }
        sessionRepository.save(s);
        return getSessionDetail(username, sessionId);
    }

    /**
     * 读取会话内已落库图片的原始字节并返回 Base64，供前端在同域下组装编辑请求，
     * 避免浏览器对 COS/CDN 直链 fetch 触发 CORS 导致「网络异常」且请求未到后端。
     */
    @Transactional(readOnly = true)
    public ImageStudioSessionImageInlineDto readSessionImageInline(String username, long sessionId, long imageId) {
        User user = requireUser(username);
        sessionRepository
                .findByIdAndUserId(sessionId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        ImageStudioSessionImage img =
                imageRepository
                        .findByIdAndSessionId(imageId, sessionId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"));
        byte[] bytes = objectStorageService.getObjectBytes(img.getObjectKey());
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取图片数据");
        }
        if (bytes.length > MAX_SESSION_IMAGE_INLINE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片过大，无法作为内联参考");
        }
        String mime = mimeFromObjectKey(img.getObjectKey());
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return new ImageStudioSessionImageInlineDto(mime, b64);
    }

    @Transactional
    public void deleteSession(String username, long sessionId) {
        User user = requireUser(username);
        ImageStudioSession s =
                sessionRepository
                        .findByIdAndUserId(sessionId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        List<ImageStudioSessionImage> imgs = imageRepository.findBySessionIdOrderBySortOrderAsc(sessionId);
        for (ImageStudioSessionImage img : imgs) {
            conversationImageService.deleteStudioGalleryMirrorsByObjectKey(user.getId(), img.getObjectKey());
            try {
                objectStorageService.remove(img.getObjectKey());
            } catch (Exception ignored) {
                // 对象可能已删；继续清理 DB
            }
        }
        sessionRepository.delete(s);
    }

    /**
     * 将一次生成结果写入指定图片会话，并同步写入作品库（{@link ChatConversationImage}，与 object_key 对齐）。
     *
     * @param studioToolId 可选：txt2img / img2img 等，用于作品库分类
     * @return 新建的图片行（含 object_key）
     */
    @Transactional
    public ImageStudioSessionImage appendSessionImage(
            String username,
            long sessionId,
            byte[] imageBytes,
            String mimeTypeHint,
            String userPrompt,
            String studioToolId) {
        if (!objectStorageService.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, objectStorageService.getUnavailableReason());
        }
        User user = requireUser(username);
        ImageStudioSession s =
                sessionRepository
                        .findByIdAndUserId(sessionId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生成结果为空");
        }
        String contentType =
                mimeTypeHint != null && !mimeTypeHint.isBlank()
                        ? mimeTypeHint.strip()
                        : "image/png";
        String ext = guessExt(contentType);
        String objectKey;
        try {
            objectKey =
                    objectStorageService.putImageStudioSessionObject(
                            user.getId(), sessionId, ext, new ByteArrayInputStream(imageBytes), imageBytes.length, contentType);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "写入对象存储失败");
        }
        String url = objectStorageService.publicUrl(objectKey);
        int nextOrder =
                imageRepository
                        .findTopBySessionIdOrderBySortOrderDesc(sessionId)
                        .map(ImageStudioSessionImage::getSortOrder)
                        .orElse(0)
                        + 1;
        ImageStudioSessionImage row = new ImageStudioSessionImage();
        row.setSessionId(sessionId);
        row.setObjectKey(objectKey);
        row.setImageUrl(url);
        row.setUserPrompt(userPrompt);
        row.setSortOrder(nextOrder);
        row = imageRepository.save(row);

        boolean firstImage = s.getImageCount() == 0;
        s.setImageCount(s.getImageCount() + 1);
        s.setLastImageUrl(objectStorageService.browserReadableUrl(objectKey));
        if (firstImage && userPrompt != null && !userPrompt.isBlank()) {
            String raw = userPrompt.strip();
            s.setTitle(raw.length() > 10 ? raw.substring(0, 10) + "…" : raw);
        }
        sessionRepository.save(s);
        conversationImageService.appendImageStudioSessionWorkToGallery(username, row, studioToolId);
        return row;
    }

    private static String guessExt(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        return ".png";
    }

    private static String mimeFromObjectKey(String objectKey) {
        String k = objectKey == null ? "" : objectKey.toLowerCase();
        if (k.endsWith(".png")) {
            return "image/png";
        }
        if (k.endsWith(".webp")) {
            return "image/webp";
        }
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private User requireUser(String username) {
        return userRepository
                .findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }
}
