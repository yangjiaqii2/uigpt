package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.ForgotPasswordResetRequest;
import top.uigpt.dto.LoginRequest;
import top.uigpt.dto.LoginResponse;
import top.uigpt.dto.RegisterRequest;
import top.uigpt.entity.User;
import top.uigpt.repository.UserRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String RECOVERY_MISMATCH = "姓名、手机号或注册日期与账号记录不一致";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RecaptchaVerificationService recaptchaVerificationService;
    private final RegisterIpRateLimiter registerIpRateLimiter;
    private final RegisterImageCaptchaService registerImageCaptchaService;

    @Transactional
    public LoginResponse register(RegisterRequest req, String clientIp) {
        registerImageCaptchaService.validateAndConsume(req.getCaptchaId(), req.getCaptchaCode());
        recaptchaVerificationService.verify(clientIp, req.getRecaptchaToken());
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两次输入的密码不一致");
        }
        synchronized (registerIpRateLimiter.sync(clientIp)) {
            registerIpRateLimiter.checkAllowsOneMoreRegistration(clientIp);
            if (userRepository.existsByUsername(req.getUsername())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
            }
            String phone = req.getPhone().trim();
            if (userRepository.existsByPhone(phone)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已被注册");
            }
            User u = new User();
            u.setUsername(req.getUsername().trim());
            u.setRealName(req.getRealName().trim());
            u.setPhone(phone);
            u.setNickname(req.getRealName().trim());
            u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            userRepository.save(u);
            registerIpRateLimiter.recordSuccessfulRegistration(clientIp);
            String token = jwtService.createToken(u.getUsername());
            return new LoginResponse(token, u.getUsername());
        }
    }

    public LoginResponse login(LoginRequest req) {
        User u =
                userRepository
                        .findByUsername(req.getUsername())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (u.getPasswordHash() == null
                || !passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (u.getStatus() != null && u.getStatus() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用");
        }
        if (u.getStatus() != null && u.getStatus() == 2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号待审核，暂无法登录");
        }
        return new LoginResponse(jwtService.createToken(u.getUsername()), u.getUsername());
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        User u =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "未登录或账号不存在"));
        if (u.getPasswordHash() == null || u.getPasswordHash().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前账号未设置登录密码（例如仅第三方登录），无法在此修改密码");
        }
        if (!passwordEncoder.matches(oldPassword, u.getPasswordHash())) {
            /* 使用 400，避免前端将 401 当作「登录失效」清空令牌 */
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        if (passwordEncoder.matches(newPassword, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        if (u.getStatus() != null && u.getStatus() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用");
        }
        if (u.getStatus() != null && u.getStatus() == 2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号待审核，无法修改密码");
        }
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(u);
    }

    /**
     * 通过姓名 + 手机号 + 注册日期（年月日）核验身份后重置密码。
     * 校验失败时统一文案，避免泄露账号是否存在。
     */
    @Transactional
    public void resetPasswordByVerification(ForgotPasswordResetRequest req) {
        if (!req.getNewPassword().equals(req.getConfirmNewPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两次输入的新密码不一致");
        }
        LocalDate expectedDate;
        try {
            expectedDate = LocalDate.parse(req.getRegisteredDate(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注册日期无效");
        }

        User u =
                userRepository
                        .findByPhone(req.getPhone().trim())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, RECOVERY_MISMATCH));

        String storedName = u.getRealName() != null ? u.getRealName().trim() : "";
        if (!storedName.equals(req.getRealName().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, RECOVERY_MISMATCH);
        }

        LocalDate registeredDay = u.getCreatedAt().toLocalDate();
        if (!registeredDay.equals(expectedDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, RECOVERY_MISMATCH);
        }

        if (u.getStatus() != null && u.getStatus() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用，无法重置密码");
        }
        if (u.getStatus() != null && u.getStatus() == 2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号待审核，无法重置密码");
        }

        if (u.getPasswordHash() != null
                && passwordEncoder.matches(req.getNewPassword(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }

        u.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(u);
    }
}
