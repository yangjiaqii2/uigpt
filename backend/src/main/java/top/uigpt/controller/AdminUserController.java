package top.uigpt.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.AdminUserCreateRequest;
import top.uigpt.dto.AdminUserPageResponse;
import top.uigpt.dto.AdminUserResponse;
import top.uigpt.dto.AdminUserUpdateRequest;
import top.uigpt.service.AdminAuthorizationService;
import top.uigpt.service.AdminUserService;
import top.uigpt.service.JwtService;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final JwtService jwtService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AdminUserService adminUserService;

    @GetMapping
    public AdminUserPageResponse list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String phone,
            @RequestParam(value = "username", required = false) String usernameFilter,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Integer privilege) {
        String operator = requireUser(authorization);
        adminAuthorizationService.requireAdmin(operator);
        return adminUserService.list(page, size, phone, usernameFilter, createdFrom, createdTo, privilege);
    }

    @GetMapping("/{id}")
    public AdminUserResponse get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.get(id);
    }

    @PostMapping
    public AdminUserResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AdminUserCreateRequest body) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.create(body);
    }

    @PutMapping("/{id}")
    public AdminUserResponse update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @Valid @RequestBody AdminUserUpdateRequest body) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireAdmin(username);
        return adminUserService.update(id, body, username);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        String username = requireUser(authorization);
        adminAuthorizationService.requireAdmin(username);
        adminUserService.delete(id, username);
    }

    private String requireUser(String authorization) {
        String u = jwtService.parseUsername(authorization);
        if (u == null || u.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return u;
    }
}
