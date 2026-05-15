package top.uigpt.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.PromptTemplateResponse;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.PromptTemplateService;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    @GetMapping
    public List<PromptTemplateResponse> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "500") int size) {
        requireUser();
        return promptTemplateService.listAll(page, size);
    }

    private void requireUser() {
        if (SecurityUtils.currentUsernameOrNull() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
    }
}
