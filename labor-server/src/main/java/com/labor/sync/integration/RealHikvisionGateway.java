package com.labor.sync.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.integration.mock-enabled", havingValue = "false")
public class RealHikvisionGateway implements HikvisionGateway {
    private final HikvisionHttpSupport httpSupport;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;

    public RealHikvisionGateway(HikvisionHttpSupport httpSupport, IntegrationProperties properties, ObjectMapper objectMapper) {
        this.httpSupport = httpSupport;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConnectionResult testConnection(IntegrationConfig config) {
        int count = queryOrganizations(config).size();
        return new ConnectionResult(true, "海康接口签名与组织查询成功，共读取 " + count + " 个组织");
    }

    @Override
    @Cacheable(cacheNames = "hikOrganizations", key = "#config.id + ':' + #config.configVersion")
    public List<HikOrganization> queryOrganizations(IntegrationConfig config) {
        List<String> paths = new ArrayList<>();
        paths.add(properties.getHikvision().getOrganizationPath());
        if (!paths.contains("/artemis/api/resource/v1/org/advance/orgList")) {
            paths.add("/artemis/api/resource/v1/org/advance/orgList");
        }
        com.labor.sync.common.BusinessException lastFailure = null;
        for (String path : paths) {
            try {
                return queryOrganizations(config, path);
            } catch (com.labor.sync.common.BusinessException exception) {
                lastFailure = exception;
            }
        }
        throw new com.labor.sync.common.BusinessException("HIK_ORGANIZATION_QUERY_FAILED",
                "海康组织接口不可用，已尝试标准与兼容路径：" + (lastFailure == null ? "未知错误" : lastFailure.getMessage()));
    }

    private List<HikOrganization> queryOrganizations(IntegrationConfig config, String path) {
        Map<String, HikOrganization> organizations = new LinkedHashMap<>();
        int pageSize = Math.max(1, properties.getHikvision().getOrganizationPageSize());
        int maxPages = Math.max(1, properties.getHikvision().getOrganizationMaxPages());
        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pageNo", pageNo);
            body.put("pageSize", pageSize);
            JsonNode root = httpSupport.post(config, path, body,
                    "查询海康组织失败");
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) data = root;
            List<JsonNode> nodes = extractItems(data);
            for (JsonNode node : nodes) {
                HikOrganization organization = toOrganization(node);
                if (organization.orgIndexCode() != null) {
                    organizations.putIfAbsent(organization.orgIndexCode(), organization);
                }
            }
            long total = data.path("total").asLong(-1);
            int totalPages = data.path("totalPage").asInt(data.path("totalPages").asInt(-1));
            boolean last = total >= 0 ? (long) pageNo * pageSize >= total
                    : totalPages > 0 ? pageNo >= totalPages : nodes.size() < pageSize;
            if (last) return resolveOrganizationPaths(organizations);
        }
        throw new com.labor.sync.common.BusinessException("HIK_ORGANIZATION_PAGE_LIMIT",
                "海康组织分页超过安全上限，请调整组织分页配置");
    }

    @Override
    public HikEventPage queryAttendanceEvents(IntegrationConfig config, HikEventQuery query) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(properties.getHikvision().getTimePattern());
        String startTime = formatter.format(query.startTime().atOffset(ZoneOffset.ofHours(8)));
        String endTime = formatter.format(query.endTime().atOffset(ZoneOffset.ofHours(8)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageNo", query.pageNo());
        body.put("pageSize", query.pageSize());
        body.put("startTime", startTime);
        body.put("endTime", endTime);
        body.put("receiveStartTime", startTime);
        body.put("receiveEndTime", endTime);
        body.put("sort", "eventTime");
        body.put("order", "asc");
        JsonNode root = httpSupport.post(config, properties.getHikvision().getEventPath(), body, "查询海康门禁事件失败");
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) data = root;
        List<JsonNode> nodes = extractItems(data);
        Set<String> organizationScope = query.orgIndexCodes() == null
                ? Set.of() : new LinkedHashSet<>(query.orgIndexCodes());
        List<HikEventRecord> items = nodes.stream()
                .filter(node -> organizationScope.isEmpty()
                        || organizationScope.contains(firstText(node, "orgIndexCode", "organizationIndexCode")))
                .map(this::toRecord).toList();
        long total = data.path("total").asLong(-1);
        int totalPages = data.path("totalPage").asInt(-1);
        boolean last = total >= 0 ? (long) query.pageNo() * query.pageSize() >= total
                : totalPages > 0 ? query.pageNo() >= totalPages : nodes.size() < query.pageSize();
        return new HikEventPage(items, total, totalPages, last);
    }

    private List<JsonNode> extractItems(JsonNode data) {
        JsonNode list = data.path("list");
        if (!list.isArray()) list = data.path("rows");
        if (!list.isArray()) list = data.path("events");
        if (!list.isArray()) list = data.path("resourceInfos");
        if (!list.isArray() && data.isArray()) list = data;
        List<JsonNode> items = new ArrayList<>();
        if (list.isArray()) list.forEach(items::add);
        return items;
    }

    private HikEventRecord toRecord(JsonNode node) {
        String raw;
        try {
            raw = objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            raw = "{}";
        }
        return new HikEventRecord(
                firstText(node, "eventId", "eventIndexCode", "indexCode", "uuid"),
                firstText(node, "orgIndexCode", "organizationIndexCode"),
                firstText(node, "personId", "personIndexCode", "personCode"),
                firstText(node, "personName", "name"),
                normalizeIdcardType(firstNonBlank(firstText(node, "idcardType", "certificateType"),
                        firstText(node.path("personDetail"), "certType"))),
                firstNonBlank(firstText(node, "idcardNumber", "idCardNo", "certificateNo", "certNo"),
                        firstText(node.path("personDetail"), "certNo")),
                parseInstant(firstText(node, "eventTime", "happenTime", "receiveTime", "dateTime")),
                normalizeDirection(firstText(node, "direction", "inAndOutType", "eventDirection")),
                defaultValue(firstText(node, "checkType"), "ZHENGCHANG_KAOQINLEIBIE"),
                defaultValue(firstText(node, "checkWay"), "FACE_FANGSHI"),
                firstText(node, "doorName", "location", "checkLocation"),
                firstText(node, "longitude"), firstText(node, "latitude"),
                firstText(node, "doorIndexCode"), firstText(node, "devIndexCode", "deviceIndexCode", "deviceCode"), raw);
    }

    private HikOrganization toOrganization(JsonNode node) {
        String code = firstText(node, "orgIndexCode", "indexCode", "organizationIndexCode");
        String name = firstText(node, "orgName", "name", "organizationName");
        String parent = firstText(node, "parentOrgIndexCode", "parentIndexCode", "parentOrganizationIndexCode");
        String path = firstText(node, "orgPath", "path", "fullPathName", "orgFullName");
        int sortOrder = firstInt(node, "sort", "sortOrder", "order", "orderNum");
        return new HikOrganization(code, name == null ? code : name, parent, path, sortOrder);
    }

    private List<HikOrganization> resolveOrganizationPaths(Map<String, HikOrganization> organizations) {
        return organizations.values().stream().map(item -> {
            if (item.orgPath() != null && !item.orgPath().isBlank()) return item;
            List<String> names = new ArrayList<>();
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            HikOrganization current = item;
            while (current != null && current.orgIndexCode() != null && visited.add(current.orgIndexCode())) {
                names.add(0, current.orgName());
                current = current.parentOrgIndexCode() == null ? null : organizations.get(current.parentOrgIndexCode());
            }
            return new HikOrganization(item.orgIndexCode(), item.orgName(), item.parentOrgIndexCode(),
                    String.join("/", names), item.sortOrder());
        }).toList();
    }

    private int firstInt(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong() || value.isTextual() && value.asText().matches("-?\\d+")) {
                return value.asInt();
            }
        }
        return 0;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return Instant.now();
            }
        }
    }

    private String normalizeDirection(String value) {
        if (value == null) return "UNKNOWN";
        String normalized = value.trim().toUpperCase();
        if (List.of("1", "IN", "ENTER", "ENTRY", "JINCHANG_JINCHU").contains(normalized)) return "JINCHANG_JINCHU";
        if (List.of("0", "2", "OUT", "EXIT", "LEAVE", "TUICHANG_JINCHU").contains(normalized)) return "TUICHANG_JINCHU";
        return "UNKNOWN";
    }

    private String normalizeIdcardType(String value) {
        if (value == null || value.isBlank()) return "SHENFEN_ZHENGJIAN";
        return List.of("111", "1", "IDCARD", "IDENTITY_CARD", "SHENFEN_ZHENGJIAN")
                .contains(value.trim().toUpperCase()) ? "SHENFEN_ZHENGJIAN" : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
