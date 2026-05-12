package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.PromptTemplateResponse;
import top.uigpt.service.JwtService;
import top.uigpt.service.PromptTemplateService;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final JwtService jwtService;
    private final PromptTemplateService promptTemplateService;

    @GetMapping
    public List<PromptTemplateResponse> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        requireUser(authorization);
        return promptTemplateService.listAll(page, size);
    }

    private String requireUser(String authorization) {
        String username = jwtService.parseUsername(authorization);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }
}
