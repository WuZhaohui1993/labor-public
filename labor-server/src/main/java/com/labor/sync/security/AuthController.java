package com.labor.sync.security;

import com.labor.sync.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "labor_refresh_token";
    private final AuthService authService;

    @Value("${app.security.refresh-cookie-secure}")
    private boolean secureCookie;

    private final CaptchaService captchaService;

    @GetMapping("/captcha")
    public ApiResponse<CaptchaService.CaptchaView> captcha(jakarta.servlet.http.HttpServletRequest request,
                                                            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        return ApiResponse.ok(captchaService.issue(clientIp(request)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.UserProfile> login(@Valid @RequestBody LoginRequest request,
                                                       HttpServletResponse response,
                                                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        AuthService.LoginResult result = authService.login(request.username(), request.password(),
                request.captchaId(), request.captchaCode(), clientIp(httpRequest));
        setRefreshCookie(response, result);
        return ApiResponse.ok(result.profile());
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.UserProfile> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        AuthService.LoginResult result = authService.refresh(refreshToken);
        setRefreshCookie(response, result);
        return ApiResponse.ok(result.profile());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return ApiResponse.ok();
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.UserProfile> me(Authentication authentication) {
        return ApiResponse.ok(authService.me(authentication.getName()));
    }

    private void setRefreshCookie(HttpServletResponse response, AuthService.LoginResult result) {
        Duration age = Duration.between(java.time.Instant.now(), result.refreshExpiresAt());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), age).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/labor/admin/auth")
                .maxAge(maxAge)
                .build();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password,
                               String captchaId, String captchaCode) {}
}
