package com.labor.sync.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.labor.sync.common.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.integration.mock-enabled", havingValue = "false")
public class RealLaborPlatformGateway implements LaborPlatformGateway {
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> tokenCache;

    public RealLaborPlatformGateway(IntegrationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tokenCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(60, properties.getLaborPlatform().getTokenTtlSeconds())))
                .maximumSize(1000)
                .build();
    }

    @Override
    public ConnectionResult testConnection(IntegrationConfig config) {
        String proCode = config.getProject().getProCode();
        getToken(config, proCode, true);
        return new ConnectionResult(true, "劳务平台 Token 获取成功");
    }

    @Override
    public LaborPushResult push(IntegrationConfig config, String proCode, LaborPushOperation operation,
                                List<Map<String, Object>> payload) {
        validateConfig(config, proCode);
        String token = getToken(config, proCode, false);
        LaborPushResult result = post(config, operation, payload, token);
        if (tokenRejected(result)) {
            tokenCache.invalidate(tokenKey(config, proCode));
            result = post(config, operation, payload, getToken(config, proCode, true));
        }
        return result;
    }

    private LaborPushResult post(IntegrationConfig config, LaborPushOperation operation,
                                 List<Map<String, Object>> payload, String token) {
        String path = path(operation);
        try {
            String body = client(config).post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, authorization(token))
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseResult(body, 200);
        } catch (RestClientResponseException exception) {
            LaborPushResult parsed = parseResult(exception.getResponseBodyAsString(), exception.getStatusCode().value());
            return new LaborPushResult(false, parsed.code(), parsed.message(), exception.getStatusCode().value(), parsed.responseSummary());
        } catch (Exception exception) {
            return new LaborPushResult(false, "NETWORK_ERROR", exception.getMessage(), null, "{}");
        }
    }

    private String getToken(IntegrationConfig config, String proCode, boolean forceRefresh) {
        validateConfig(config, proCode);
        String key = tokenKey(config, proCode);
        if (forceRefresh) tokenCache.invalidate(key);
        return tokenCache.get(key, ignored -> requestToken(config, proCode));
    }

    private String requestToken(IntegrationConfig config, String proCode) {
        try {
            String body = client(config).get()
                    .uri(builder -> builder.path(properties.getLaborPlatform().getTokenPath())
                            .queryParam("proCode", proCode).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = read(body);
            if (!isSuccess(root)) {
                throw new BusinessException("LABOR_TOKEN_FAILED", "劳务平台 Token 获取失败：" + remoteMessage(root));
            }
            String token = root.path("data").asText();
            if (token == null || token.isBlank()) {
                throw new BusinessException("LABOR_TOKEN_EMPTY", "劳务平台返回的 Token 为空");
            }
            return token.trim();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new BusinessException("LABOR_TOKEN_FAILED", "劳务平台 Token 获取失败：HTTP " + exception.getStatusCode().value());
        } catch (Exception exception) {
            throw new BusinessException("LABOR_TOKEN_FAILED", "劳务平台 Token 获取失败：" + exception.getMessage());
        }
    }

    private LaborPushResult parseResult(String body, int httpStatus) {
        try {
            JsonNode root = read(body);
            String code = root.path("code").isMissingNode() ? null : root.path("code").asText();
            String message = remoteMessage(root);
            boolean success = httpStatus >= 200 && httpStatus < 300 && isSuccess(root);
            JsonNode data = root.path("data");
            if ((data.isBoolean() && !data.asBoolean())
                    || (data.isTextual() && "false".equalsIgnoreCase(data.asText().trim()))) success = false;
            return new LaborPushResult(success, code, message, httpStatus, root.toString());
        } catch (Exception exception) {
            return new LaborPushResult(false, "INVALID_RESPONSE", "无法解析劳务平台返回结果", httpStatus, "{}");
        }
    }

    private boolean isSuccess(JsonNode root) {
        String code = root.path("code").asText();
        JsonNode successNode = root.path("success");
        boolean successFlag = successNode.isMissingNode() || successNode.isNull()
                || successNode.asBoolean(false) || "true".equalsIgnoreCase(successNode.asText());
        return (code.isBlank() || "0".equals(code) || "200".equals(code)) && successFlag;
    }

    private String remoteMessage(JsonNode root) {
        String message = root.path("message").asText();
        if (message.isBlank()) message = root.path("msg").asText();
        return message.isBlank() ? "无返回信息" : message;
    }

    private RestClient client(IntegrationConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getLaborPlatform().getConnectTimeoutSeconds())))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getLaborPlatform().getReadTimeoutSeconds())));
        return RestClient.builder().baseUrl(trimSlash(config.getBaseUrl())).requestFactory(factory).build();
    }

    private JsonNode read(String body) throws Exception {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("empty response");
        return objectMapper.readTree(body);
    }

    private String path(LaborPushOperation operation) {
        return switch (operation) {
            case PROJECT -> properties.getLaborPlatform().getProjectPath();
            case COMPANY -> properties.getLaborPlatform().getCompanyPath();
            case TEAM -> properties.getLaborPlatform().getTeamPath();
            case PERSON -> properties.getLaborPlatform().getPersonPath();
            case ATTENDANCE -> properties.getLaborPlatform().getAttendancePath();
        };
    }

    private String authorization(String token) {
        String prefix = properties.getLaborPlatform().getAuthorizationPrefix();
        return prefix == null || prefix.isBlank() ? token : prefix.trim() + " " + token;
    }

    private boolean tokenRejected(LaborPushResult result) {
        if (result.httpStatus() != null && (result.httpStatus() == 401 || result.httpStatus() == 403)) return true;
        String code = result.code();
        return code != null && Set.of("401", "403", "TOKEN_EXPIRED", "TOKEN_INVALID")
                .contains(code.trim().toUpperCase());
    }

    private String tokenKey(IntegrationConfig config, String proCode) {
        return config.getId() + ":" + config.getConfigVersion() + ":" + proCode;
    }

    private void validateConfig(IntegrationConfig config, String proCode) {
        if (!config.isEnabled()) throw new BusinessException("INTEGRATION_DISABLED", "劳务平台接口尚未启用");
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new BusinessException("LABOR_CONFIG_INCOMPLETE", "劳务平台服务地址未配置");
        }
        if (proCode == null || proCode.isBlank()) throw new BusinessException("PROJECT_CODE_REQUIRED", "项目编码不能为空");
    }

    private String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

}
