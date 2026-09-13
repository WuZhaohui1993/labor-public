package com.labor.sync.security;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final ProjectRoleAssignmentRepository assignmentRepository;
    private final AuditService auditService;
    private final CaptchaService captchaService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.refresh-token-days}")
    private long refreshDays;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult login(String username, String password, String captchaId, String captchaCode, String clientIp) {
        String normalizedUsername = username == null ? "" : username.trim();
        CaptchaService.ValidationResult captchaResult = captchaService.consume(captchaId, captchaCode, clientIp);
        if (captchaResult != CaptchaService.ValidationResult.VALID) {
            auditService.recordAuthenticationFailure(normalizedUsername, "CAPTCHA_" + captchaResult.name(), clientIp);
            throw captchaException(captchaResult);
        }

        AppUser user = userRepository.findByUsernameIgnoreCaseForUpdate(normalizedUsername).orElse(null);
        if (user == null) {
            auditService.recordAuthenticationFailure(normalizedUsername, "INVALID_CREDENTIALS", clientIp);
            throw invalidCredentials();
        }
        Instant now = Instant.now();
        if (!user.isEnabled()) {
            auditService.recordAuthenticationFailure(normalizedUsername, "ACCOUNT_DISABLED", clientIp);
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            auditService.recordAuthenticationFailure(normalizedUsername, "ACCOUNT_LOCKED", clientIp);
            throw accountLocked();
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            boolean locked = recordFailedPassword(user, now);
            userRepository.save(user);
            auditService.recordAuthenticationFailure(normalizedUsername,
                    locked ? "ACCOUNT_LOCKED" : "INVALID_PASSWORD", clientIp);
            if (locked) throw accountLocked();
            throw invalidCredentials();
        }

        user.setFailedLoginAttempts(0);
        user.setFailedLoginWindowStartedAt(null);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        userRepository.save(user);
        UserDetails details = userDetailsService.loadUserByUsername(user.getUsername());
        JwtService.Token access = jwtService.issue(details, user.getTokenVersion());
        String rawRefreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        RefreshToken refresh = new RefreshToken();
        refresh.setUser(user);
        refresh.setTokenHash(hash(rawRefreshToken));
        refresh.setExpiresAt(Instant.now().plus(refreshDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(refresh);
        auditService.record("LOGIN", "AUTH", user.getId(), null);
        return new LoginResult(profile(user, access), rawRefreshToken, refresh.getExpiresAt());
    }

    private boolean recordFailedPassword(AppUser user, Instant now) {
        if (user.getFailedLoginWindowStartedAt() == null
                || now.isAfter(user.getFailedLoginWindowStartedAt().plus(FAILURE_WINDOW))) {
            user.setFailedLoginWindowStartedAt(now);
            user.setFailedLoginAttempts(0);
        }
        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        user.setLastFailedLoginAt(now);
        if (user.getFailedLoginAttempts() < MAX_FAILED_ATTEMPTS) return false;
        user.setLockedUntil(now.plus(LOCK_DURATION));
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokenRepository.revokeActiveByUserId(user.getId(), now);
        return true;
    }

    private BusinessException captchaException(CaptchaService.ValidationResult result) {
        return switch (result) {
            case REQUIRED -> new BusinessException("CAPTCHA_REQUIRED", "请输入验证码", HttpStatus.BAD_REQUEST);
            case EXPIRED -> new BusinessException("CAPTCHA_EXPIRED", "验证码已失效，请刷新后重试", HttpStatus.BAD_REQUEST);
            case INVALID -> new BusinessException("CAPTCHA_INVALID", "验证码错误，请刷新后重试", HttpStatus.BAD_REQUEST);
            case VALID -> throw new IllegalStateException("有效验证码不应进入错误分支");
        };
    }

    private BusinessException invalidCredentials() {
        return new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
    }

    private BusinessException accountLocked() {
        return new BusinessException("ACCOUNT_LOCKED", "账号暂时锁定，请稍后重试", HttpStatus.LOCKED);
    }

    @Transactional
    public LoginResult refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException("REFRESH_TOKEN_MISSING", "刷新令牌不存在", HttpStatus.UNAUTHORIZED);
        }
        RefreshToken old = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken))
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new BusinessException("REFRESH_TOKEN_INVALID", "刷新令牌已失效", HttpStatus.UNAUTHORIZED));
        old.setRevokedAt(Instant.now());
        AppUser user = old.getUser();
        if (!user.isEnabled() || user.isLocked(Instant.now())) {
            throw accountLocked();
        }
        UserDetails details = userDetailsService.loadUserByUsername(user.getUsername());
        JwtService.Token access = jwtService.issue(details, user.getTokenVersion());
        String nextRaw = UUID.randomUUID() + "." + UUID.randomUUID();
        RefreshToken next = new RefreshToken();
        next.setUser(user);
        next.setTokenHash(hash(nextRaw));
        next.setExpiresAt(Instant.now().plus(refreshDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(next);
        return new LoginResult(profile(user, access), nextRaw, next.getExpiresAt());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken))
                    .ifPresent(token -> token.setRevokedAt(Instant.now()));
        }
        auditService.record("LOGOUT", "AUTH", null, null);
    }

    @Transactional(readOnly = true)
    public UserProfile me(String username) {
        AppUser user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND));
        return profile(user, null);
    }

    private UserProfile profile(AppUser user, JwtService.Token token) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        user.getRoles().forEach(role -> {
            roles.add(role.getCode());
            role.getPermissions().forEach(permission -> permissions.add(permission.getCode()));
        });
        assignmentRepository.findByUserUsernameIgnoreCaseOrderByProjectProjectNameAscRoleNameAsc(user.getUsername())
                .forEach(assignment -> {
                    roles.add(assignment.getRole().getCode());
                    assignment.getRole().getPermissions().forEach(permission -> permissions.add(permission.getCode()));
                });
        return new UserProfile(user.getId(), user.getUsername(), user.getDisplayName(),
                valueOrEmpty(user.getAvatarData()), valueOrEmpty(user.getEmail()), valueOrEmpty(user.getPhone()),
                valueOrEmpty(user.getDescription()),
                user.getSecurityQuestion() != null && !user.getSecurityQuestion().isBlank(),
                roles, permissions, token == null ? null : token.value(), token == null ? null : token.expiresAt());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record UserProfile(Long id, String username, String nickname, String avatar,
                              String email, String phone, String description,
                              boolean securityQuestionSet,
                              Set<String> roles, Set<String> permissions,
                              String accessToken, Instant expires) {}

    public record LoginResult(UserProfile profile, String refreshToken, Instant refreshExpiresAt) {}
}
