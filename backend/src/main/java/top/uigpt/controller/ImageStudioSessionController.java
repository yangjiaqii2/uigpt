package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ImageStudioSessionDetailDto;
import top.uigpt.dto.ImageStudioSessionImageInlineDto;
import top.uigpt.dto.ImageStudioSessionPatchRequest;
import top.uigpt.dto.ImageStudioSessionSummaryDto;
import top.uigpt.entity.ImageStudioSession;
import top.uigpt.service.ImageStudioSessionService;
import top.uigpt.service.JwtService;

import java.util.HashMap;
import java.util.Map;

/**
 * 图片工作台「图片会话」：与多模态对话 / chat 会话隔离的独立历史与上下文存储。
 */
@RestController
@RequestMapping("/api/image-studio/sessions")
@RequiredArgsConstructor
public class ImageStudioSessionController {

    private final JwtService jwtService;
    private final ImageStudioSessionService imageStudioSessionService;

    @GetMapping
    public Page<ImageStudioSessionSummaryDto> listSessions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "40") int size) {
        String username = requireUser(authorization);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return imageStudioSessionService.listSessions(username, PageRequest.of(safePage, safeSize));
    }

    @PostMapping
    public Map<String, Object> createSession(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String username = requireUser(authorization);
        ImageStudioSession s = imageStudioSessionService.createSession(username);
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("imageCount", s.getImageCount());
        m.put("thumbUrl", s.getLastImageUrl());
        m.put("studioSkillId", s.getStudioSkillId());
        return m;
    }

    @GetMapping("/{id}")
    public ImageStudioSessionDetailDto getSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") long id) {
        String username = requireUser(authorization);
        return imageStudioSessionService.getSessionDetail(username, id);
    }

    @GetMapping("/{id}/images/{imageId}/inline-data")
    public ImageStudioSessionImageInlineDto getSessionImageInlineData(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") long sessionId,
            @PathVariable long imageId) {
        String username = requireUser(authorization);
        return imageStudioSessionService.readSessionImageInline(username, sessionId, imageId);
    }

    @PatchMapping("/{id}")
    public ImageStudioSessionDetailDto patchSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") long id,
            @Valid @RequestBody ImageStudioSessionPatchRequest body) {
        String username = requireUser(authorization);
        return imageStudioSessionService.patchSession(
                username, id, body.getContextText(), body.getTitle(), body.getStudioSkillId());
    }

    @DeleteMapping("/{id}")
    public void deleteSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") long id) {
        String username = requireUser(authorization);
        imageStudioSessionService.deleteSession(username, id);
    }

    private String requireUser(String authorization) {
        String username = jwtService.parseUsername(authorization);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return username;
    }
}
