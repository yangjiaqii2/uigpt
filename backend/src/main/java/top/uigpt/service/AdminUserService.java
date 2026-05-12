package top.uigpt.service;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.dto.AdminUserCreateRequest;
import top.uigpt.dto.AdminUserPageResponse;
import top.uigpt.dto.AdminUserResponse;
import top.uigpt.dto.AdminUserUpdateRequest;
import top.uigpt.entity.User;
import top.uigpt.model.UserPrivilege;
import top.uigpt.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Pattern PHONE_CN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ADMIN_DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final char LIKE_ESCAPE = '\\';

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PointsService pointsService;

    public AdminUserPageResponse list(
            int page,
            int size,
            String phone,
            String username,
            String createdFrom,
            String createdTo,
            Integer privilege) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        LocalDateTime lower = parseCreatedLowerBound(createdFrom);
        CreatedUpper upper = parseCreatedUpperBound(createdTo);
        validateCreatedOrder(lower, upper);
        Byte privilegeByte = normalizeListPrivilegeFilter(privilege);
        Specification<User> spec =
                buildListSpecification(
                        phone != null ? phone : "",
                        username != null ? username : "",
                        lower,
                        upper,
                        privilegeByte);
        Page<User> pg =
                userRepository.findAll(spec, PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, "id")));
        return AdminUserPageResponse.builder()
                .content(pg.getContent().stream().map(this::toResponse).toList())
                .totalElements(pg.getTotalElements())
                .totalPages(pg.getTotalPages())
                .number(pg.getNumber())
                .size(pg.getSize())
                .build();
    }

    private static Byte normalizeListPrivilegeFilter(Integer privilege) {
        if (privilege == null) {
            return null;
        }
        int v = privilege;
        if (v == UserPrivilege.STANDARD.getDbValue()
                || v == UserPrivilege.PREMIUM.getDbValue()
                || v == UserPrivilege.SUPER_ADMIN.getDbValue()) {
            return (byte) v;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色筛选无效");
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record CreatedUpper(boolean inclusiveEnd, LocalDateTime boundary) {}

    private static LocalDateTime parseCreatedLowerBound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        try {
            if (ADMIN_DATE_ONLY.matcher(s).matches()) {
                return LocalDate.parse(s).atStartOfDay();
            }
            try {
                return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.parse(s);
            }
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "createdFrom 无法解析为日期或时间");
        }
    }

    private static CreatedUpper parseCreatedUpperBound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        try {
            if (ADMIN_DATE_ONLY.matcher(s).matches()) {
                LocalDate d = LocalDate.parse(s);
                return new CreatedUpper(false, d.plusDays(1).atStartOfDay());
            }
            LocalDateTime ldt;
            try {
                ldt = OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                ldt = LocalDateTime.parse(s);
            }
            return new CreatedUpper(true, ldt);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "createdTo 无法解析为日期或时间");
        }
    }

    private static void validateCreatedOrder(LocalDateTime lower, CreatedUpper upper) {
        if (lower == null || upper == null) {
            return;
        }
        if (upper.inclusiveEnd) {
            if (lower.isAfter(upper.boundary)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注册时间区间无效：开始时间不能晚于结束时间");
            }
        } else {
            if (!lower.isBefore(upper.boundary)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注册时间区间无效：开始日期不能晚于结束日期");
            }
        }
    }

    private Specification<User> buildListSpecification(
            String phone, String username, LocalDateTime lower, CreatedUpper upper, Byte privilege) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            String ph = phone.strip();
            if (!ph.isEmpty()) {
                String like = "%" + escapeLike(ph) + "%";
                preds.add(cb.like(root.get("phone"), like, LIKE_ESCAPE));
            }
            String un = username.strip();
            if (!un.isEmpty()) {
                String like = "%" + escapeLike(un.toLowerCase(Locale.ROOT)) + "%";
                preds.add(cb.like(cb.lower(root.get("username")), like, LIKE_ESCAPE));
            }
            if (lower != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), lower));
            }
            if (upper != null) {
                if (upper.inclusiveEnd) {
                    preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), upper.boundary));
                } else {
                    preds.add(cb.lessThan(root.get("createdAt"), upper.boundary));
                }
            }
            if (privilege != null) {
                preds.add(cb.equal(root.get("privilege"), privilege));
            }
            if (preds.isEmpty()) {
                return cb.conjunction();
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    public AdminUserResponse get(Long id) {
        User u =
                userRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        return toResponse(u);
    }

    @Transactional
    public AdminUserResponse create(AdminUserCreateRequest req) {
        String username = req.getUsername().strip();
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        String phone = req.getPhone().strip();
        if (userRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已被使用");
        }
        User u = new User();
        u.setUsername(username);
        u.setRealName(req.getRealName().strip());
        u.setPhone(phone);
        u.setNickname(
                req.getNickname() != null && !req.getNickname().isBlank()
                        ? req.getNickname().strip()
                        : req.getRealName().strip());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        byte st = normalizeUserStatusByte(req.getStatus());
        u.setStatus(st);
        u.setPrivilege(normalizePrivilegeByte(req.getPrivilege()));
        if (req.getPointsBonus() != null) {
            u.setPointsBonus(clampPoints(req.getPointsBonus(), "附加积分"));
        }
        if (pointsService.isEnabled()) {
            pointsService.rewriteTodayPoolFromPrivilegeAndBonus(u);
        }
        userRepository.save(u);
        return toResponse(u);
    }

    @Transactional
    public AdminUserResponse update(Long id, AdminUserUpdateRequest req, String operatorUsername) {
        User u =
                userRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        boolean isSelf =
                operatorUsername != null
                        && operatorUsername.strip().equalsIgnoreCase(u.getUsername());
        if (req.getRealName() != null) {
            u.setRealName(req.getRealName().isBlank() ? null : req.getRealName().strip());
        }
        if (req.getNickname() != null) {
            u.setNickname(req.getNickname().isBlank() ? null : req.getNickname().strip());
        }
        if (req.getPhone() != null) {
            String ph = req.getPhone().strip();
            if (ph.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
            }
            if (!PHONE_CN.matcher(ph).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式无效");
            }
            userRepository
                    .findByPhone(ph)
                    .filter(other -> !other.getId().equals(u.getId()))
                    .ifPresent(
                            x -> {
                                throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已被其他账号使用");
                            });
            u.setPhone(ph);
        }
        if (req.getStatus() != null) {
            byte s = normalizeUserStatusByte(req.getStatus());
            if (isSelf && (s == 0 || s == 2)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "不能将当前登录账号设为禁用或待审核");
            }
            u.setStatus(s);
        }
        if (req.getNewPassword() != null && !req.getNewPassword().isBlank()) {
            String np = req.getNewPassword();
            if (np.length() < 8 || np.length() > 72) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度为 8～72 个字符");
            }
            u.setPasswordHash(passwordEncoder.encode(np));
        }
        if (req.getPrivilege() != null) {
            u.setPrivilege(normalizePrivilegeByte(req.getPrivilege()));
        }
        if (req.getPointsBonus() != null) {
            u.setPointsBonus(clampPoints(req.getPointsBonus(), "附加积分"));
        }
        boolean poolTouch =
                req.getPrivilege() != null || req.getPointsBonus() != null;
        if (poolTouch) {
            pointsService.rewriteTodayPoolFromPrivilegeAndBonus(u);
        }
        if (req.getPoints() != null) {
            u.setPoints(clampPoints(req.getPoints(), "积分"));
        }
        userRepository.save(u);
        return toResponse(u);
    }

    @Transactional
    public void delete(Long id, String operatorUsername) {
        User u =
                userRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (operatorUsername != null
                && u.getUsername().equalsIgnoreCase(operatorUsername.strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前登录账号");
        }
        userRepository.delete(u);
    }

    private static byte normalizeUserStatusByte(Byte status) {
        if (status == null) {
            return 1;
        }
        byte s = status;
        if (s == 0 || s == 1 || s == 2) {
            return s;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态无效");
    }

    private static byte normalizePrivilegeByte(Byte privilege) {
        if (privilege == null) {
            return UserPrivilege.STANDARD.getDbValue();
        }
        byte v = privilege;
        if (v == UserPrivilege.STANDARD.getDbValue()
                || v == UserPrivilege.PREMIUM.getDbValue()
                || v == UserPrivilege.SUPER_ADMIN.getDbValue()) {
            return v;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色无效");
    }

    private AdminUserResponse toResponse(User u) {
        return AdminUserResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .realName(u.getRealName())
                .phone(u.getPhone())
                .nickname(u.getNickname())
                .avatarUrl(u.getAvatarUrl())
                .status(u.getStatus())
                .privilege(u.getPrivilege())
                .points(u.getPoints())
                .pointsBonus(u.getPointsBonus())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .build();
    }

    private static int clampPoints(int v, String label) {
        if (v < -1_000_000 || v > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "超出允许范围");
        }
        return v;
    }
}
