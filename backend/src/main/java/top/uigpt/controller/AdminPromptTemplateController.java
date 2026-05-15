package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.PromptTemplateResponse;
import top.uigpt.dto.PromptTemplateWriteRequest;
import top.uigpt.model.UserPrivilege;
import top.uigpt.repository.UserRepository;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.PromptTemplateService;

/**
 * 提示词模板的增删改：<b>仅</b>当 {@code users.privilege == 2}（超级管理员）时允许。
 *
 * <p>环境变量 {@code uigpt.admin.usernames-csv} 中的运维账号若 DB 中 privilege 仍为 0，则无法通过此处管理提示词；需在库中将其
 * {@code privilege} 更新为 2，或由已是超级管理员的账号操作。
 */
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
public class AdminPromptTemplateController {

    private final UserRepository userRepository;
    private final PromptTemplateService promptTemplateService;

    @PostMapping
    public ResponseEntity<PromptTemplateResponse> create(@Valid @RequestBody PromptTemplateWriteRequest body) {
        String username = requireUser();
        requireDbSuperAdmin(username);
        PromptTemplateResponse res = promptTemplateService.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PutMapping("/{id}")
    public PromptTemplateResponse update(
            @PathVariable("id") long id, @Valid @RequestBody PromptTemplateWriteRequest body) {
        String username = requireUser();
        requireDbSuperAdmin(username);
        return promptTemplateService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        String username = requireUser();
        requireDbSuperAdmin(username);
        promptTemplateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String requireUser() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return username;
    }

    private void requireDbSuperAdmin(String username) {
        byte p =
                userRepository
                        .findByUsername(username)
                        .map(u -> u.getPrivilege())
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效"));
        if (p != UserPrivilege.SUPER_ADMIN.getDbValue()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要超级管理员权限（users.privilege=2）");
        }
    }
}
