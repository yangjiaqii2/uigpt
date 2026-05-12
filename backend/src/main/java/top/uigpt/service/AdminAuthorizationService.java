package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.uigpt.config.AppProperties;
import top.uigpt.model.UserPrivilege;
import top.uigpt.repository.UserRepository;

import java.util.Locale;

/**
 * 管理员判定：环境变量 {@code uigpt.admin.usernames-csv} 与 DB {@code privilege=SUPER_ADMIN} 任一满足即为管理员。
 *
 * <p>角色分配规则（刻意保持简单）：凡已通过 {@link #requireAdmin(String)} 的调用方，均可将任意用户的 {@code privilege}
 * 设为 0～2（含将他人设为超级管理员）；仅环境变量名单中的账号、未在 DB 中标为超级管理员时，仍具备同等的用户管理能力。
 *
 * <p>知识库管理等场景仅允许 DB {@link UserPrivilege#SUPER_ADMIN}，见 {@link #requireSuperAdmin(String)}（不包含仅环境变量名单）。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthorizationService {

    private final AppProperties appProperties;
    private final UserRepository userRepository;

    public boolean isAdmin(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (listedInAdminEnvCsv(username)) {
            return true;
        }
        return userRepository
                .findByUsername(username.strip())
                .map(u -> u.getPrivilege() == UserPrivilege.SUPER_ADMIN.getDbValue())
                .orElse(false);
    }

    public void requireAdmin(String username) {
        if (!isAdmin(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }

    /** 仅 DB {@code users.privilege = SUPER_ADMIN}，与 {@link #isAdmin(String)} 的环境变量名单无关。 */
    public boolean isSuperAdmin(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return userRepository
                .findByUsername(username.strip())
                .map(u -> u.getPrivilege() == UserPrivilege.SUPER_ADMIN.getDbValue())
                .orElse(false);
    }

    public void requireSuperAdmin(String username) {
        if (!isSuperAdmin(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要超级管理员权限");
        }
    }

    private boolean listedInAdminEnvCsv(String username) {
        String csv = appProperties.getAdmin().getUsernamesCsv();
        if (csv == null || csv.isBlank()) {
            return false;
        }
        String me = username.strip().toLowerCase(Locale.ROOT);
        for (String part : csv.split(",")) {
            if (part == null) {
                continue;
            }
            String p = part.strip().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && p.equals(me)) {
                return true;
            }
        }
        return false;
    }
}
