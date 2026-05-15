package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ChatMessageDto;
import top.uigpt.dto.ConversationErnieImageRequest;
import top.uigpt.dto.ConversationImageResponse;
import top.uigpt.dto.ConversationPinnedUpdateRequest;
import top.uigpt.dto.ConversationSummaryResponse;
import top.uigpt.dto.ConversationTitleUpdateRequest;
import top.uigpt.dto.ImageFavoriteRequest;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.ConversationImageService;
import top.uigpt.service.ConversationService;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationImageService conversationImageService;

    @GetMapping("/conversations")
    public List<ConversationSummaryResponse> list() {
        return conversationService.listForUser(requireUser());
    }

    @PatchMapping("/conversations/{id}")
    public void patchConversationTitle(
            @PathVariable("id") Long id, @Valid @RequestBody ConversationTitleUpdateRequest body) {
        conversationService.renameConversation(requireUser(), id, body.getTitle());
    }

    @PatchMapping("/conversations/{id}/pinned")
    public void patchConversationPinned(
            @PathVariable("id") Long id, @RequestBody ConversationPinnedUpdateRequest body) {
        String username = requireUser();
        boolean pinned = body != null && body.isPinned();
        conversationService.setConversationPinned(username, id, pinned);
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(@PathVariable("id") Long id) {
        conversationService.deleteConversation(requireUser(), id);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageDto> messages(@PathVariable("id") Long id) {
        return conversationService.getMessages(requireUser(), id);
    }

    @GetMapping("/conversations/{id}/images")
    public List<ConversationImageResponse> conversationImages(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "80") int limit) {
        return conversationImageService.listForUser(requireUser(), id, offset, limit);
    }

    @PostMapping(value = "/conversations/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConversationImageResponse uploadConversationImage(
            @PathVariable("id") Long id,
            @RequestParam("messageSortOrder") int messageSortOrder,
            @RequestParam("skillId") String skillId,
            @RequestPart("file") MultipartFile file) {
        return conversationImageService.upload(requireUser(), id, messageSortOrder, skillId, file);
    }

    /**
     * 会话内文生图：APIYi 出图并写入 COS；响应 JSON 含 {@code b64_json}（及 {@code b64_mime_type}）便于前端即时展示。
     */
    @PostMapping(value = "/conversations/{id}/images/ernie-generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConversationImageResponse generateErnieConversationImage(
            @PathVariable("id") Long id, @Valid @RequestBody ConversationErnieImageRequest body) {
        return conversationImageService.generateWithErnieImage(requireUser(), id, body);
    }

    /**
     * 会话内局部重绘：multipart 含 PNG 蒙版（透明区域表示需修改）；原图从 COS 按 {@code sourceImageId} 读取，仍写入当前会话。
     */
    @PostMapping(value = "/conversations/{id}/images/ernie-inpaint", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConversationImageResponse generateErnieConversationInpaint(
            @PathVariable("id") Long id,
            @RequestParam("messageSortOrder") int messageSortOrder,
            @RequestParam("skillId") String skillId,
            @RequestParam("userMessage") String userMessage,
            @RequestParam(value = "assistantReply", required = false) String assistantReply,
            @RequestParam("sourceImageId") long sourceImageId,
            @RequestParam(value = "aspectKey", required = false) String aspectKey,
            @RequestParam(value = "styleLabel", required = false) String styleLabel,
            @RequestParam(value = "qualityTier", required = false) String qualityTier,
            @RequestParam(value = "imageConversationContext", required = false) String imageConversationContext,
            @RequestParam(value = "useRag", required = false) Boolean useRag,
            @RequestParam(value = "ragCollection", required = false) String ragCollection,
            @RequestPart("mask") MultipartFile mask) {
        String username = requireUser();
        if (mask == null || mask.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传蒙版文件");
        }
        if (mask.getSize() > 15L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "蒙版文件过大（最大 15MB）");
        }
        byte[] maskBytes;
        try {
            maskBytes = mask.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取蒙版失败");
        }
        return conversationImageService.generateInpaintWithErnie(
                username,
                id,
                messageSortOrder,
                skillId,
                userMessage,
                assistantReply,
                sourceImageId,
                aspectKey,
                styleLabel,
                qualityTier,
                imageConversationContext,
                useRag,
                ragCollection,
                maskBytes);
    }

    @DeleteMapping("/conversations/{id}/images/{imageId}")
    public void deleteConversationImage(
            @PathVariable("id") Long id, @PathVariable("imageId") Long imageId) {
        conversationImageService.delete(requireUser(), id, imageId);
    }

    @PatchMapping("/conversations/{id}/images/{imageId}/favorite")
    public ConversationImageResponse patchConversationImageFavorite(
            @PathVariable("id") Long id,
            @PathVariable("imageId") Long imageId,
            @RequestBody ImageFavoriteRequest body) {
        String username = requireUser();
        boolean fav = body != null && body.isFavorite();
        return conversationImageService.setFavorite(username, id, imageId, fav);
    }

    private String requireUser() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }
}
