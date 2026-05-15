package top.uigpt.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.config.AppProperties;
import top.uigpt.dto.ChangePasswordRequest;
import top.uigpt.dto.ForgotPasswordResetRequest;
import top.uigpt.dto.LoginRequest;
import top.uigpt.dto.LoginResponse;
import top.uigpt.dto.RegisterCaptchaResponse;
import top.uigpt.dto.RegisterOptionsResponse;
import top.uigpt.dto.RegisterRequest;
import top.uigpt.dto.UserProfileResponse;
import top.uigpt.repository.UserRepository;
import top.uigpt.security.SecurityUtils;
import top.uigpt.service.AdminAuthorizationService;
import top.uigpt.service.AuthService;
import top.uigpt.service.JwtBlacklistService;
import top.uigpt.service.JwtService;
import top.uigpt.service.PointsService;
import top.uigpt.service.RegisterImageCaptchaService;
import top.uigpt.util.ClientIpResolver;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final JwtBlacklistService jwtBlacklistService;
    private final AppProperties appProperties;
    private final RegisterImageCaptchaService registerImageCaptchaService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final UserRepository userRepository;
    private final PointsService pointsService;

    @GetMapping("/register/captcha")
    public RegisterCaptchaResponse registerCaptcha() {
        return registerImageCaptchaService.create();
    }

    @GetMapping("/register/options")
    public RegisterOptionsResponse registerOptions() {
        AppProperties.Recaptcha rc = appProperties.getRecaptcha();
        return new RegisterOptionsResponse(
                rc.isEnabled(),
                rc.getSiteKey() != null ? rc.getSiteKey() : "");
    }

    @PostMapping("/register")
    public LoginResponse register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String ip = ClientIpResolver.resolve(httpRequest);
        return authService.register(request, ip);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/forgot-password/reset")
    public void forgotPasswordReset(@Valid @RequestBody ForgotPasswordResetRequest request) {
        authService.resetPasswordByVerification(request);
    }

    /** 将当前 Bearer 令牌加入 Redis 黑名单（TTL 为剩余有效期）；前端登出时应先调用再清空本地存储。 */
    @PostMapping("/logout")
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        jwtService
                .parseBearer(authorization)
                .ifPresent(
                        jwt ->
                                jwtBlacklistService.blacklist(
                                        jwt.rawToken(), jwtService.blacklistTtlSeconds(jwt)));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public UserProfileResponse me() {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        int privilege =
                userRepository.findByUsername(username).map(u -> (int) u.getPrivilege()).orElse(0);
        int points = pointsService.syncRefillForUsername(username);
        boolean admin = adminAuthorizationService.isAdmin(username);
        return new UserProfileResponse(username, admin, privilege, points);
    }

    @PutMapping("/me/password")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        String username = SecurityUtils.currentUsernameOrNull();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        authService.changePassword(username, body.getOldPassword(), body.getNewPassword());
    }
}
