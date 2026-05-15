package top.uigpt.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import top.uigpt.entity.User;
import top.uigpt.model.UserPrivilege;
import top.uigpt.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u =
                userRepository
                        .findByUsername(username.strip())
                        .orElseThrow(() -> new UsernameNotFoundException(username));
        boolean enabled = u.getStatus() != null && u.getStatus() == 1;
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (enabled) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (u.getPrivilege() == UserPrivilege.PREMIUM.getDbValue()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_PREMIUM"));
            }
            if (u.getPrivilege() == UserPrivilege.SUPER_ADMIN.getDbValue()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
            }
            if (adminAuthorizationService.isAdmin(u.getUsername())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_APP_ADMIN"));
            }
        }
        String ph = u.getPasswordHash() != null ? u.getPasswordHash() : "";
        return org.springframework.security.core.userdetails.User.builder()
                .username(u.getUsername())
                .password(ph)
                .disabled(!enabled)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .authorities(authorities)
                .build();
    }
}
