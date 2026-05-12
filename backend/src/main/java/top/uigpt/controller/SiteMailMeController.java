package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.site.SiteMailMessageResponse;
import top.uigpt.dto.site.SiteMailSummaryResponse;
import top.uigpt.dto.site.SiteMailThreadDetailResponse;
import top.uigpt.service.JwtService;
import top.uigpt.service.SiteMailService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/me/site-mail")
@RequiredArgsConstructor
public class SiteMailMeController {

    private final JwtService jwtService;
    private final SiteMailService siteMailService;

    @GetMapping("/summary")
    public SiteMailSummaryResponse summary(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String u = requireUser(authorization);
        return siteMailService.summaryForUser(u);
    }

    @GetMapping("/thread")
    public SiteMailThreadDetailResponse thread(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String u = requireUser(authorization);
        return siteMailService.threadForUser(u);
    }

    @PostMapping(value = "/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SiteMailMessageResponse send(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "body", required = false, defaultValue = "") String body,
            @RequestPart(value = "images", required = false) MultipartFile[] images) {
        String u = requireUser(authorization);
        List<MultipartFile> list =
                images == null ? Collections.emptyList() : Arrays.stream(images).filter(f -> f != null && !f.isEmpty()).toList();
        return siteMailService.sendUserMessage(u, body, list);
    }

    private String requireUser(String authorization) {
        String username = jwtService.parseUsername(authorization);
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }
}
