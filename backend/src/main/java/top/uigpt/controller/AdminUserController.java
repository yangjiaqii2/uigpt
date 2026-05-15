package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.AdminUserCreateRequest;
import top.uigpt.dto.AdminUserPageResponse;
import top.uigpt.dto.AdminUserResponse;
import top.uigpt.dto.AdminUserUpdateRequest;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.AdminAuthorizationService;
import top.uigpt.service.AdminUserService;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final AdminUserService adminUserService;

    @GetMapping
    public AdminUserPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String phone,
            @RequestParam(value = "username", required = false) String usernameFilter,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Integer privilege) {
        String operator = requireUser();
        adminAuthorizationService.requireAdmin(operator);
        return adminUserService.list(page, size, phone, usernameFilter, createdFrom, createdTo, privilege);
    }

    @GetMapping("/{id}")
    public AdminUserResponse get(@PathVariable Long id) {
        String username = requireUser();
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.get(id);
    }

    @PostMapping
    public AdminUserResponse create(@Valid @RequestBody AdminUserCreateRequest body) {
        String username = requireUser();
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.create(body);
    }

    @PutMapping("/{id}")
    public AdminUserResponse update(
            @PathVariable Long id, @Valid @RequestBody AdminUserUpdateRequest body) {
        String username = requireUser();
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.update(id, body, username);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        String username = requireUser();
        adminAuthorizationService.requireAdmin(username);
        adminUserService.delete(id, username);
    }

    private String requireUser() {
        String u = SecurityUtils.currentUsernameOrNull();
        if (u == null || u.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return u;
    }
}
