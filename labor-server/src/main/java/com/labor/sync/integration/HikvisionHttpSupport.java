package com.labor.sync.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hikvision.artemis.sdk.Client;
import com.hikvision.artemis.sdk.Request;
import com.hikvision.artemis.sdk.Response;
import com.hikvision.artemis.sdk.constant.Constants;
import com.hikvision.artemis.sdk.enums.Method;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.integration.mock-enabled", havingValue = "false")
public class HikvisionHttpSupport {
    private static final int LOG_BODY_LIMIT = 2000;

    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final IntegrationProperties properties;

    public JsonNode post(IntegrationConfig config, String path, Object body, String errorMessage) {
        validateConfig(config);
        long started = System.nanoTime();
        try {
            applyTimeouts();
            String bodyJson = objectMapper.writeValueAsString(body);
            Request<String> request = buildRequest(config, path, bodyJson);
            log.debug("hikvision request path={} body={}", request.getPath(), safeBody(bodyJson));
            Response response = Client.execute(request);
            String responseBody = response == null ? null : response.getBody();
            log.debug("hikvision response path={} durationMs={} body={}", request.getPath(),
                    (System.nanoTime() - started) / 1_000_000, safeBody(responseBody));
            if (responseBody == null || responseBody.isBlank()) {
                throw new BusinessException("HIKVISION_EMPTY_RESPONSE", errorMessage + "：海康返回为空");
            }
            JsonNode root = objectMapper.readTree(responseBody);
            assertSuccess(root, errorMessage);
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("HIKVISION_REQUEST_FAILED", errorMessage + "：" + exception.getMessage());
        }
    }

    private Request<String> buildRequest(IntegrationConfig config, String path, String bodyJson) {
        ParsedEndpoint endpoint = parseEndpoint(config.getBaseUrl(), normalizePath(path));
        Request<String> request = new Request<>(Method.POST_STRING, endpoint.schemaPrefix() + endpoint.hostPort(),
                endpoint.path(), config.getAppKey(), cryptoService.decrypt(config.getAppSecretEncrypted()), 0);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", MediaType.APPLICATION_JSON_VALUE);
        headers.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (config.getUserId() != null && !config.getUserId().isBlank()) {
            headers.put("userId", config.getUserId().trim());
        }
        request.setHeaders(headers);
        request.setStringBody(bodyJson);
        return request;
    }

    private ParsedEndpoint parseEndpoint(String baseUrl, String requestPath) {
        try {
            String normalized = baseUrl.trim();
            if (!normalized.contains("://")) normalized = "https://" + normalized;
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BusinessException("HIKVISION_CONFIG_INVALID", "海康服务地址缺少主机");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : ("http".equals(scheme) ? 80 : 443);
            String prefix = normalizeOptionalPath(uri.getPath());
            String mergedPath = prefix.isEmpty() || requestPath.startsWith(prefix + "/") || requestPath.equals(prefix)
                    ? requestPath : prefix + requestPath;
            return new ParsedEndpoint(scheme + "://", uri.getHost() + ":" + port, mergedPath);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("HIKVISION_CONFIG_INVALID", "海康服务地址格式非法");
        }
    }

    private void validateConfig(IntegrationConfig config) {
        if (!config.isEnabled()) throw new BusinessException("INTEGRATION_DISABLED", "海康接口尚未启用");
        if (blank(config.getBaseUrl()) || blank(config.getAppKey()) || blank(config.getAppSecretEncrypted())) {
            throw new BusinessException("HIKVISION_CONFIG_INCOMPLETE", "海康服务地址、AppKey 和 AppSecret 必须配置");
        }
    }

    private void applyTimeouts() {
        Constants.DEFAULT_TIMEOUT = Math.max(1, properties.getHikvision().getConnectTimeoutSeconds()) * 1000;
        Constants.SOCKET_TIMEOUT = Math.max(1, properties.getHikvision().getReadTimeoutSeconds()) * 1000;
    }

    private void assertSuccess(JsonNode root, String errorMessage) {
        JsonNode codeNode = root.path("code");
        if (codeNode.isMissingNode() || codeNode.isNull()) return;
        String code = codeNode.asText();
        if ("0".equals(code) || "200".equals(code)) return;
        String message = firstText(root, "msg", "message", "errorMsg", "detail");
        throw new BusinessException("HIKVISION_REMOTE_ERROR", errorMessage + "：" + code + " " + message);
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            String value = root.path(field).asText();
            if (!blank(value)) return value;
        }
        return "未知错误";
    }

    private String safeBody(String body) {
        if (body == null) return null;
        String safe = body.replaceAll("\\s+", " ").trim();
        return safe.length() <= LOG_BODY_LIMIT ? safe : safe.substring(0, LOG_BODY_LIMIT) + "...(truncated)";
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private String normalizeOptionalPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) return "";
        String normalized = path.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ParsedEndpoint(String schemaPrefix, String hostPort, String path) {
    }
}
