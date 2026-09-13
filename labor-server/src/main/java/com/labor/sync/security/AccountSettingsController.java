package com.labor.sync.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.audit.AuditLog;
import com.labor.sync.audit.AuditLogRepository;
import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin")
public class AccountSettingsController {
    private static final int MAX_AVATAR_BYTES = 5 * 1024 * 1024;
    private static final TypeReference<Map<String, Boolean>> PREFERENCES_TYPE = new TypeReference<>() {};

    private final AppUserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @GetMapping("/mine")
    @Transactional(readOnly = true)
    public ApiResponse<AuthService.UserProfile> mine(org.springframework.security.core.Authentication authentication) {
        return ApiResponse.ok(profile(user(authentication.getName())));
    }

    @PutMapping("/mine/profile")
    @Transactional
    public ApiResponse<AuthService.UserProfile> updateProfile(
            org.springframework.security.core.Authentication authentication,
            @Valid @RequestBody ProfileRequest request) {
        AppUser user = user(authentication.getName());
        user.setDisplayName(request.nickname().trim());
        user.setEmail(normalize(request.email()));
        user.setPhone(normalize(request.phone()));
        user.setDescription(normalize(request.description()));
        auditService.record("UPDATE_PROFILE", "USER", user.getId(), null);
        return ApiResponse.ok(profile(user));
    }

    @PostMapping(value = "/mine/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ApiResponse<AuthService.UserProfile> updateAvatar(
            org.springframework.security.core.Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("AVATAR_REQUIRED", "请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException("AVATAR_TOO_LARGE", "头像文件不能超过5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException("AVATAR_TYPE_INVALID", "头像必须是图片文件");
        }
        try {
            AppUser user = user(authentication.getName());
            user.setAvatarData("data:" + contentType + ";base64," + java.util.Base64.getEncoder()
                    .encodeToString(file.getBytes()));
            auditService.record("UPDATE_AVATAR", "USER", user.getId(), null);
            return ApiResponse.ok(profile(user));
        } catch (IOException exception) {
            throw new BusinessException("AVATAR_READ_FAILED", "头像文件读取失败", HttpStatus.BAD_REQUEST);
        }
    }

    @PutMapping("/mine/password")
    @Transactional
    public ApiResponse<Void> updatePassword(
            org.springframework.security.core.Authentication authentication,
            @Valid @RequestBody PasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException("PASSWORD_CONFIRM_MISMATCH", "两次输入的新密码不一致");
        }
        AppUser user = user(authentication.getName());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("CURRENT_PASSWORD_INVALID", "当前密码不正确", HttpStatus.BAD_REQUEST);
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokenRepository.revokeActiveByUserId(user.getId(), Instant.now());
        auditService.record("UPDATE_PASSWORD", "USER", user.getId(), null);
        return ApiResponse.ok();
    }

    @GetMapping("/mine/preferences")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Boolean>> preferences(org.springframework.security.core.Authentication authentication) {
        AppUser user = user(authentication.getName());
        return ApiResponse.ok(readPreferences(user.getPreferencesJson()));
    }

    @PutMapping("/mine/preferences")
    @Transactional
    public ApiResponse<Map<String, Boolean>> updatePreferences(
            org.springframework.security.core.Authentication authentication,
            @RequestBody Map<String, Boolean> request) {
        AppUser user = user(authentication.getName());
        Map<String, Boolean> preferences = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            if (key != null && value != null && SetOfPreferences.ALLOWED.contains(key)) {
                preferences.put(key, value);
            }
        });
        try {
            user.setPreferencesJson(objectMapper.writeValueAsString(preferences));
        } catch (IOException exception) {
            throw new BusinessException("PREFERENCES_SAVE_FAILED", "偏好设置保存失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        auditService.record("UPDATE_PREFERENCES", "USER", user.getId(), null);
        return ApiResponse.ok(preferences);
    }

    @PutMapping("/mine/security-question")
    @Transactional
    public ApiResponse<AuthService.UserProfile> updateSecurityQuestion(
            org.springframework.security.core.Authentication authentication,
            @Valid @RequestBody SecurityQuestionRequest request) {
        AppUser user = user(authentication.getName());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("CURRENT_PASSWORD_INVALID", "当前密码不正确", HttpStatus.BAD_REQUEST);
        }
        user.setSecurityQuestion(request.question().trim());
        user.setSecurityAnswerHash(passwordEncoder.encode(request.answer().trim()));
        auditService.record("UPDATE_SECURITY_QUESTION", "USER", user.getId(), null);
        return ApiResponse.ok(profile(user));
    }

    @GetMapping("/mine-logs")
    @Transactional(readOnly = true)
    public ApiResponse<LogPage> mineLogs(
            org.springframework.security.core.Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<AuditLog> all = auditLogRepository.findByActorOrderByCreatedAtDesc(authentication.getName());
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return ApiResponse.ok(new LogPage(all.subList(from, to).stream().map(LogView::from).toList(),
                all.size(), safeSize, safePage));
    }

    private AuthService.UserProfile profile(AppUser user) {
        return new AuthService.UserProfile(user.getId(), user.getUsername(), user.getDisplayName(),
                valueOrEmpty(user.getAvatarData()), valueOrEmpty(user.getEmail()), valueOrEmpty(user.getPhone()),
                valueOrEmpty(user.getDescription()),
                user.getSecurityQuestion() != null && !user.getSecurityQuestion().isBlank(),
                null, null, null, null);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private AppUser user(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND));
    }

    private Map<String, Boolean> readPreferences(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Boolean> parsed = objectMapper.readValue(json, PREFERENCES_TYPE);
            Map<String, Boolean> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (SetOfPreferences.ALLOWED.contains(key) && value != null) result.put(key, value);
            });
            return result;
        } catch (IOException exception) {
            return new LinkedHashMap<>();
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ProfileRequest(@NotBlank @Size(max = 100) String nickname,
                                 @Email @Size(max = 254) String email,
                                 @Size(max = 32) String phone,
                                 @Size(max = 500) String description) {}

    public record PasswordRequest(@NotBlank String currentPassword,
                                  @NotBlank @Size(min = 8, max = 100) String newPassword,
                                  @NotBlank String confirmPassword) {}

    public record SecurityQuestionRequest(@NotBlank @Size(max = 200) String question,
                                           @NotBlank @Size(max = 100) String answer,
                                           @NotBlank String currentPassword) {}

    public record LogPage(List<LogView> list, int total, int pageSize, int currentPage) {}

    public record LogView(Long id, String summary, String ip, String address, String system,
                          String browser, Instant operatingTime) {
        static LogView from(AuditLog log) {
            return new LogView(log.getId(), log.getAction() + " " + log.getResourceType(),
                    log.getClientIp(), "", "", "", log.getCreatedAt());
        }
    }

    private static final class SetOfPreferences {
        private static final java.util.Set<String> ALLOWED = java.util.Set.of("userMessage", "systemMessage", "todo");
    }
}
