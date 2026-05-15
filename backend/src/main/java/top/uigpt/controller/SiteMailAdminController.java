package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.site.AdminSiteMailThreadPageResponse;
import top.uigpt.dto.site.SiteMailMessageResponse;
import top.uigpt.dto.site.SiteMailThreadDetailResponse;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.SiteMailService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/admin/site-mail")
@RequiredArgsConstructor
public class SiteMailAdminController {

    private final SiteMailService siteMailService;

    @GetMapping("/unread-count")
    public AdminUnreadBody unread() {
        requireUser();
        return new AdminUnreadBody(siteMailService.adminUnreadTotal());
    }

    @GetMapping("/threads")
    public AdminSiteMailThreadPageResponse listThreads(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String u = requireUser();
        return siteMailService.adminListThreads(u, page, size);
    }

    @GetMapping("/threads/{threadId}")
    public SiteMailThreadDetailResponse thread(@PathVariable long threadId) {
        String u = requireUser();
        return siteMailService.adminThreadDetail(u, threadId);
    }

    @PostMapping(value = "/threads/{threadId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SiteMailMessageResponse reply(
            @PathVariable long threadId,
            @RequestParam(value = "body", required = false, defaultValue = "") String body,
            @RequestPart(value = "images", required = false) MultipartFile[] images) {
        String u = requireUser();
        List<MultipartFile> list =
                images == null ? Collections.emptyList() : Arrays.stream(images).filter(f -> f != null && !f.isEmpty()).toList();
        return siteMailService.sendAdminReply(u, threadId, body, list);
    }

    private String requireUser() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }

    public record AdminUnreadBody(long count) {}
}
