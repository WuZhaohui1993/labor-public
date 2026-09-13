package com.labor.sync.integration;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/integration-configs")
public class IntegrationController {
    private final IntegrationConfigRepository repository;
    private final CryptoService cryptoService;
    private final HikvisionGateway hikvisionGateway;
    private final LaborPlatformGateway laborPlatformGateway;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('integration:view')")
    public ApiResponse<List<ConfigView>> list() {
        return ApiResponse.ok(repository.findByProjectProCodeOrderByIntegrationType(WorkspaceContext.proCode())
                .stream().map(this::view).toList());
    }

    @PutMapping("/{type}")
    @PreAuthorize("hasAuthority('integration:edit')")
    @Transactional
    public ApiResponse<ConfigView> update(@PathVariable String type, @Valid @RequestBody ConfigWrite request) {
        IntegrationConfig config = find(type);
        config.setBaseUrl(request.baseUrl());
        config.setAppKey(request.appKey());
        config.setUserId(request.userId());
        config.setEnabled(request.enabled());
        if (request.appSecret() != null && !request.appSecret().isBlank()) {
            config.setAppSecretEncrypted(cryptoService.encrypt(request.appSecret()));
        }
        config.setConfigVersion(config.getConfigVersion() + 1);
        auditService.record("UPDATE", "INTEGRATION_CONFIG", type, "配置版本 " + config.getConfigVersion());
        return ApiResponse.ok(view(config));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{type}/test")
    @PreAuthorize("hasAuthority('integration:test')")
    @Transactional
    public ApiResponse<ConnectionResult> test(@PathVariable String type) {
        IntegrationConfig config = find(type);
        ConnectionResult result = switch (type.toUpperCase()) {
            case "HIKVISION" -> hikvisionGateway.testConnection(config);
            case "LABOR_PLATFORM" -> laborPlatformGateway.testConnection(config);
            default -> throw new BusinessException("INTEGRATION_TYPE_INVALID", "不支持的接口类型");
        };
        config.setLastTestStatus(result.success() ? "SUCCESS" : "FAILED");
        config.setLastTestMessage(result.message());
        config.setLastTestAt(Instant.now());
        auditService.record("TEST_CONNECTION", "INTEGRATION_CONFIG", type, result.message());
        return ApiResponse.ok(result);
    }

    private IntegrationConfig find(String type) {
        return repository.findByProjectProCodeAndIntegrationType(WorkspaceContext.proCode(), type.toUpperCase())
                .orElseThrow(() -> new BusinessException("CONFIG_NOT_FOUND", "接口配置不存在", HttpStatus.NOT_FOUND));
    }

    private ConfigView view(IntegrationConfig config) {
        String secret = config.getAppSecretEncrypted() == null || config.getAppSecretEncrypted().isBlank()
                ? null : cryptoService.decrypt(config.getAppSecretEncrypted());
        return new ConfigView(config.getId(), config.getIntegrationType(), config.getBaseUrl(), config.getAppKey(),
                secret, config.getUserId(), config.isEnabled(), config.getConfigVersion(), config.getLastTestStatus(),
                config.getLastTestMessage(), config.getLastTestAt());
    }

    public record ConfigWrite(String baseUrl, String appKey, String appSecret, String userId, boolean enabled) {}

    public record ConfigView(Long id, String integrationType, String baseUrl, String appKey,
                             String appSecret, String userId, boolean enabled, int configVersion,
                             String lastTestStatus, String lastTestMessage, Instant lastTestAt) {}
}
