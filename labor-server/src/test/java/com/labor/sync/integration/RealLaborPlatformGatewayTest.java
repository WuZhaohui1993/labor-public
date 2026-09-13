package com.labor.sync.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RealLaborPlatformGatewayTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void tokenIsCachedAndRawAuthorizationHeaderAndV3PathAreUsed() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger pushCalls = new AtomicInteger();
        CopyOnWriteArrayList<String> authorizations = new CopyOnWriteArrayList<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/getTokenV2", exchange -> {
            tokenCalls.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("proCode=P-HTTP-001");
            respond(exchange, 200, "{\"code\":\"200\",\"message\":\"操作成功\",\"data\":\"TOKEN-001\",\"success\":true}");
        });
        server.createContext("/collBasicInfo/pushProjectBasicDataV2", exchange -> {
            pushCalls.incrementAndGet();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"msg\":\"成功\",\"data\":true,\"success\":\"true\"}");
        });
        server.start();

        IntegrationProperties properties = new IntegrationProperties();
        properties.getLaborPlatform().setAuthorizationPrefix("");
        RealLaborPlatformGateway gateway = new RealLaborPlatformGateway(properties, new ObjectMapper().findAndRegisterModules());
        IntegrationConfig config = new IntegrationConfig();
        config.setIntegrationType("LABOR_PLATFORM");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setEnabled(true);

        List<Map<String, Object>> payload = List.of(Map.of(
                "proCode", "P-HTTP-001", "collCompanyName", "接口测试项目", "sign", 1));
        LaborPushResult first = gateway.push(config, "P-HTTP-001", LaborPushOperation.PROJECT, payload);
        LaborPushResult second = gateway.push(config, "P-HTTP-001", LaborPushOperation.PROJECT, payload);
        properties.getLaborPlatform().setAuthorizationPrefix("Bearer");
        LaborPushResult third = gateway.push(config, "P-HTTP-001", LaborPushOperation.PROJECT, payload);

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(third.success()).isTrue();
        assertThat(tokenCalls).hasValue(1);
        assertThat(pushCalls).hasValue(3);
        assertThat(authorizations).containsExactly("TOKEN-001", "TOKEN-001", "Bearer TOKEN-001");
        assertThat(requestBody.get()).contains("\"sign\":1").contains("\"proCode\":\"P-HTTP-001\"");
    }

    @Test
    void allV3PushPathsUseArrayPayloads() throws Exception {
        Map<LaborPushOperation, String> paths = new EnumMap<>(LaborPushOperation.class);
        paths.put(LaborPushOperation.PROJECT, "/collBasicInfo/pushProjectBasicDataV2");
        paths.put(LaborPushOperation.COMPANY, "/collBasicInfo/pushCollBasicDataV2");
        paths.put(LaborPushOperation.TEAM, "/collBasicInfo/pushTeamDataV2");
        paths.put(LaborPushOperation.PERSON, "/personnelInfo/pushPersonnelDataV2");
        paths.put(LaborPushOperation.ATTENDANCE, "/punchRecords/dataV2");
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/getTokenV2", exchange ->
                respond(exchange, 200, "{\"code\":\"200\",\"data\":\"TOKEN-ALL\",\"success\":true}"));
        paths.forEach((operation, path) -> server.createContext(path, exchange -> {
            received.add(exchange.getRequestURI().getPath() + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"message\":\"成功\",\"data\":true,\"success\":true}");
        }));
        server.start();

        RealLaborPlatformGateway gateway = gateway();
        IntegrationConfig config = config();
        paths.keySet().forEach(operation -> assertThat(gateway.push(config, "P-ALL-001", operation,
                List.of(Map.of("proCode", "P-ALL-001"))).success()).isTrue());

        assertThat(received).hasSize(5);
        paths.values().forEach(path -> assertThat(received).anyMatch(value -> value.startsWith(path + "|[{")
                && value.contains("\"proCode\":\"P-ALL-001\"")));
    }

    @Test
    void textualFalseDataIsFailureAndResponseFieldsRemainVisible() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/getTokenV2", exchange ->
                respond(exchange, 200, "{\"code\":\"200\",\"data\":\"TOKEN-001\",\"success\":true}"));
        server.createContext("/collBasicInfo/pushProjectBasicDataV2", exchange -> respond(exchange, 200,
                "{\"code\":\"200\",\"success\":\"true\",\"data\":\"false\","
                        + "\"idcardNumber\":\"110101199001011234\",\"mobile\":\"13800000000\","
                        + "\"headImage\":\"BASE64-CONTENT\"}"));
        server.start();

        LaborPushResult result = gateway().push(config(), "P-HTTP-001", LaborPushOperation.PROJECT,
                List.of(Map.of("proCode", "P-HTTP-001")));

        assertThat(result.success()).isFalse();
        assertThat(result.responseSummary()).contains("110101199001011234")
                .contains("13800000000").contains("BASE64-CONTENT");
    }

    @Test
    void businessTokenExpiryResponseRefreshesTokenOnce() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger pushCalls = new AtomicInteger();
        CopyOnWriteArrayList<String> authorizations = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/getTokenV2", exchange -> {
            int call = tokenCalls.incrementAndGet();
            respond(exchange, 200, "{\"code\":\"200\",\"data\":\"TOKEN-" + call + "\",\"success\":true}");
        });
        server.createContext("/collBasicInfo/pushProjectBasicDataV2", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (pushCalls.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"code\":\"TOKEN_EXPIRED\",\"message\":\"Token已过期\",\"data\":false,\"success\":false}");
            } else {
                respond(exchange, 200, "{\"code\":\"200\",\"data\":true,\"success\":true}");
            }
        });
        server.start();

        LaborPushResult result = gateway().push(config(), "P-HTTP-001", LaborPushOperation.PROJECT,
                List.of(Map.of("proCode", "P-HTTP-001")));

        assertThat(result.success()).isTrue();
        assertThat(tokenCalls).hasValue(2);
        assertThat(pushCalls).hasValue(2);
        assertThat(authorizations).containsExactly("TOKEN-1", "TOKEN-2");
    }

    private RealLaborPlatformGateway gateway() {
        return new RealLaborPlatformGateway(new IntegrationProperties(), new ObjectMapper().findAndRegisterModules());
    }

    private IntegrationConfig config() {
        IntegrationConfig config = new IntegrationConfig();
        config.setIntegrationType("LABOR_PLATFORM");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setEnabled(true);
        return config;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
