package com.labor.sync.hik;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.integration.HikEventPage;
import com.labor.sync.integration.HikEventQuery;
import com.labor.sync.integration.HikEventRecord;
import com.labor.sync.integration.HikOrganization;
import com.labor.sync.integration.HikvisionGateway;
import com.labor.sync.integration.IntegrationConfig;
import com.labor.sync.integration.IntegrationConfigRepository;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.matching.PersonMatchingService;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import com.labor.sync.push.IntegrationCallLog;
import com.labor.sync.push.IntegrationCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class HikCollectionService {
    private static final ZoneId DEFAULT_PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final HikOrganizationMappingRepository mappingRepository;
    private final HikCollectionCursorRepository cursorRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final IntegrationConfigRepository configRepository;
    private final IntegrationCallLogRepository callLogRepository;
    private final HikvisionGateway gateway;
    private final IntegrationProperties properties;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final PersonMatchingService matchingService;
    private final ProjectSyncSettingRepository syncSettingRepository;
    private final ProjectRepository projectRepository;
    private final PlatformTransactionManager transactionManager;
    private final ConcurrentMap<String, ReentrantLock> projectCollectionLocks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${app.scheduling.hik-collection-delay-ms:3600000}")
    public void scheduledCollect() {
        for (var setting : syncSettingRepository.findByHikCollectionEnabledTrueOrderByProjectProCodeAsc()) {
            // 已启动自动推送的项目由每小时40分的推送编排统一采集，避免重复调用海康。
            if (setting.isPushEnabled() && setting.isSyncStarted()) continue;
            String proCode = setting.getProject().getProCode();
            try {
                collectProject(proCode);
            } catch (RuntimeException exception) {
                log.warn("hik collection failed proCode={} reason={}", proCode, exception.getMessage());
            }
        }
    }

    public ProjectCollectionResult collectProject(String proCode) {
        ReentrantLock lock = projectCollectionLocks.computeIfAbsent(proCode, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ProjectCollectionResult result = new TransactionTemplate(transactionManager).execute(status ->
                    collectProjectLocked(proCode));
            if (result == null) throw new IllegalStateException("海康项目采集事务未返回结果");
            return result;
        } catch (RuntimeException exception) {
            recordProjectCollectionFailure(proCode, exception);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    public ProjectCollectionResult collectProjectRange(String proCode, Instant requestedStart, Instant requestedEnd) {
        ReentrantLock lock = projectCollectionLocks.computeIfAbsent(proCode, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ProjectCollectionResult result = new TransactionTemplate(transactionManager).execute(status ->
                    collectProjectRangeLocked(proCode, requestedStart, requestedEnd));
            if (result == null) throw new IllegalStateException("海康项目补查事务未返回结果");
            return result;
        } catch (RuntimeException exception) {
            recordProjectCollectionFailure(proCode, exception);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    private ProjectCollectionResult collectProjectLocked(String proCode) {
        List<HikOrganizationMapping> mappings = mappingRepository.findByProCodeOrderByOrgIndexCodeAsc(proCode)
                .stream().filter(HikOrganizationMapping::isEnabled).toList();
        if (mappings.isEmpty()) return new ProjectCollectionResult(proCode, 0, 0, 0, 0, 0, 0, 0);
        projectRepository.findByProCodeForUpdate(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        IntegrationConfig config = integrationConfig(proCode);
        Instant end = Instant.now();
        Map<Long, HikCollectionCursor> cursors = new LinkedHashMap<>();
        Instant start = null;
        for (HikOrganizationMapping mapping : mappings) {
            HikCollectionCursor cursor = cursorRepository.findByMappingId(mapping.getId())
                    .orElseGet(() -> newCursor(mapping));
            cursors.put(mapping.getId(), cursor);
            Instant mappingStart = cursor.getLastEventTime() == null
                    ? startOfProjectDay(proCode, end)
                    : cursor.getLastEventTime().minus(Math.max(0, cursor.getOverlapSeconds()), ChronoUnit.SECONDS);
            if (start == null || mappingStart.isBefore(start)) start = mappingStart;
        }
        if (start == null || !start.isBefore(end)) {
            throw new BusinessException("HIK_TIME_RANGE_INVALID", "采集开始时间必须早于结束时间");
        }

        List<HikOrganization> catalog = mappings.stream().anyMatch(HikOrganizationMapping::isIncludeChildren)
                ? gateway.queryOrganizations(config) : List.of();
        Map<String, HikOrganizationMapping> routing = new LinkedHashMap<>();
        for (HikOrganizationMapping mapping : mappings) {
            for (String code : organizationCodes(mapping, catalog)) routing.putIfAbsent(code, mapping);
        }
        CollectionAccumulator result = collectRange(config, proCode, routing, start, end);
        Instant completedAt = Instant.now();
        for (HikOrganizationMapping mapping : mappings) {
            HikCollectionCursor cursor = cursors.get(mapping.getId());
            LatestEvent latest = result.latestByMapping().get(mapping.getId());
            cursor.setLastEventTime(end);
            if (latest != null) cursor.setLastEventId(latest.eventId());
            cursor.setLastSuccessAt(completedAt);
            cursor.setLastError(null);
            cursorRepository.save(cursor);
        }
        saveCallLog(config, start, end, result.pages(), mappings.size(), routing.size(), result,
                true, null, result.durationMs());
        return new ProjectCollectionResult(proCode, mappings.size(), mappings.size(), 0,
                result.queried(), result.inserted(), result.enriched(), result.duplicates());
    }

    private ProjectCollectionResult collectProjectRangeLocked(String proCode, Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessException("HIK_TIME_RANGE_INVALID", "采集开始时间必须早于结束时间");
        }
        List<HikOrganizationMapping> mappings = mappingRepository.findByProCodeOrderByOrgIndexCodeAsc(proCode)
                .stream().filter(HikOrganizationMapping::isEnabled).toList();
        if (mappings.isEmpty()) return new ProjectCollectionResult(proCode, 0, 0, 0, 0, 0, 0, 0);
        projectRepository.findByProCodeForUpdate(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        IntegrationConfig config = integrationConfig(proCode);
        List<HikOrganization> catalog = mappings.stream().anyMatch(HikOrganizationMapping::isIncludeChildren)
                ? gateway.queryOrganizations(config) : List.of();
        Map<String, HikOrganizationMapping> routing = new LinkedHashMap<>();
        for (HikOrganizationMapping mapping : mappings) {
            for (String code : organizationCodes(mapping, catalog)) routing.putIfAbsent(code, mapping);
        }
        CollectionAccumulator result = collectRange(config, proCode, routing, start, end);
        saveCallLog(config, start, end, result.pages(), mappings.size(), routing.size(), result,
                true, null, result.durationMs());
        return new ProjectCollectionResult(proCode, mappings.size(), mappings.size(), 0,
                result.queried(), result.inserted(), result.enriched(), result.duplicates());
    }

    public CollectionResult collectMapping(Long mappingId, Instant requestedStart, Instant requestedEnd, boolean advanceCursor) {
        String proCode = mappingRepository.findById(mappingId)
                .map(HikOrganizationMapping::getProCode)
                .orElseThrow(() -> new BusinessException("HIK_MAPPING_NOT_FOUND", "海康组织映射不存在"));
        ReentrantLock lock = projectCollectionLocks.computeIfAbsent(proCode, ignored -> new ReentrantLock());
        lock.lock();
        try {
            CollectionResult result = new TransactionTemplate(transactionManager).execute(status ->
                    collectMappingLocked(mappingId, requestedStart, requestedEnd, advanceCursor));
            if (result == null) throw new IllegalStateException("海康采集事务未返回结果");
            return result;
        } catch (RuntimeException exception) {
            recordCollectionFailure(mappingId, exception);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    private CollectionResult collectMappingLocked(Long mappingId, Instant requestedStart, Instant requestedEnd,
                                                   boolean advanceCursor) {
        HikOrganizationMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new BusinessException("HIK_MAPPING_NOT_FOUND", "海康组织映射不存在"));
        projectRepository.findByProCodeForUpdate(mapping.getProCode())
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        if (!mapping.isEnabled()) throw new BusinessException("HIK_MAPPING_DISABLED", "海康组织映射未启用");
        IntegrationConfig config = integrationConfig(mapping.getProCode());

        HikCollectionCursor cursor = cursorRepository.findByMappingId(mappingId).orElseGet(() -> newCursor(mapping));
        Instant end = requestedEnd == null ? Instant.now() : requestedEnd;
        Instant start = requestedStart;
        if (start == null) {
            start = cursor.getLastEventTime() == null
                    ? startOfProjectDay(mapping.getProCode(), end)
                    : cursor.getLastEventTime().minus(Math.max(0, cursor.getOverlapSeconds()), ChronoUnit.SECONDS);
        }
        if (!start.isBefore(end)) throw new BusinessException("HIK_TIME_RANGE_INVALID", "采集开始时间必须早于结束时间");
        List<String> organizationCodes = organizationCodes(mapping, config);

        Map<String, HikOrganizationMapping> routing = new LinkedHashMap<>();
        organizationCodes.forEach(code -> routing.put(code, mapping));
        CollectionAccumulator result = collectRange(config, mapping.getProCode(), routing, start, end);
        if (advanceCursor) {
            LatestEvent latest = result.latestByMapping().get(mappingId);
            cursor.setLastEventTime(end);
            if (latest != null) cursor.setLastEventId(latest.eventId());
            cursor.setLastSuccessAt(Instant.now());
            cursor.setLastError(null);
            cursorRepository.save(cursor);
        }
        saveCallLog(config, start, end, result.pages(), 1, organizationCodes.size(), result,
                true, null, result.durationMs());
        return new CollectionResult(mappingId, start, end, result.queried(), result.inserted(), result.enriched(),
                result.duplicates(),
                advanceCursor ? cursor.getLastEventTime() : null);
    }

    private CollectionAccumulator collectRange(IntegrationConfig config, String proCode,
                                               Map<String, HikOrganizationMapping> routing,
                                               Instant start, Instant end) {
        int queried = 0;
        int inserted = 0;
        int enriched = 0;
        int duplicates = 0;
        int pages = 0;
        Map<Long, LatestEvent> latestByMapping = new LinkedHashMap<>();
        int pageSize = Math.min(1000, Math.max(1, properties.getHikvision().getPageSize()));
        long started = System.nanoTime();
        for (int pageNo = 1; pageNo <= properties.getHikvision().getMaxPages(); pageNo++) {
            HikEventPage page = gateway.queryAttendanceEvents(config,
                    new HikEventQuery(proCode, List.copyOf(routing.keySet()), start, end, pageNo, pageSize));
            pages++;
            List<HikEventRecord> scopedItems = page.items().stream()
                    .filter(record -> record.orgIndexCode() != null
                            && routing.containsKey(record.orgIndexCode().trim()))
                    .toList();
            queried += scopedItems.size();
            for (HikEventRecord record : scopedItems) {
                if (record.eventId() == null || record.eventId().isBlank() || record.eventTime() == null) continue;
                HikOrganizationMapping mapping = routing.get(record.orgIndexCode().trim());
                HikAttendanceEvent existing = eventRepository
                        .findByEventIdAndProCode(record.eventId(), proCode).orElse(null);
                if (existing != null) {
                    duplicates++;
                    EnrichmentResult enrichment = enrichExistingEvent(existing, record);
                    if (enrichment.changed()) {
                        enriched++;
                        if (enrichment.identityChanged()
                                && existing.getMatchStatus() != MatchStatus.MATCHED
                                && existing.getMatchStatus() != MatchStatus.RELEASED) {
                            matchingService.autoMatch(existing.getId());
                        }
                    }
                } else {
                    HikAttendanceEvent event = saveEvent(mapping, record);
                    inserted++;
                    matchingService.autoMatch(event.getId());
                }
                LatestEvent current = latestByMapping.get(mapping.getId());
                if (current == null || record.eventTime().isAfter(current.eventTime())
                        || record.eventTime().equals(current.eventTime())
                        && record.eventId().compareTo(defaultString(current.eventId())) > 0) {
                    latestByMapping.put(mapping.getId(), new LatestEvent(record.eventTime(), record.eventId()));
                }
            }
            if (page.lastPage()) break;
            if (pageNo == properties.getHikvision().getMaxPages()) {
                throw new BusinessException("HIK_PAGE_LIMIT", "海康事件分页超过安全上限，请缩小补查时间范围");
            }
        }
        return new CollectionAccumulator(queried, inserted, enriched, duplicates, pages,
                (System.nanoTime() - started) / 1_000_000, latestByMapping);
    }

    private IntegrationConfig integrationConfig(String proCode) {
        IntegrationConfig config = configRepository.findByProjectProCodeAndIntegrationType(proCode, "HIKVISION")
                .orElseThrow(() -> new BusinessException("CONFIG_NOT_FOUND", "海康接口配置不存在"));
        if (!properties.isMockEnabled() && !config.isEnabled()) {
            throw new BusinessException("INTEGRATION_DISABLED", "海康接口尚未启用");
        }
        return config;
    }

    private void recordCollectionFailure(Long mappingId, RuntimeException exception) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    mappingRepository.findById(mappingId).ifPresent(mapping -> {
                        HikCollectionCursor cursor = cursorRepository.findByMappingId(mappingId)
                                .orElseGet(() -> newCursor(mapping));
                        cursor.setLastError(abbreviate(exception.getMessage(), 1000));
                        cursorRepository.save(cursor);
                        configRepository.findByProjectProCodeAndIntegrationType(mapping.getProCode(), "HIKVISION")
                                .ifPresent(config -> saveCallLog(config, null, null, 0, 1, 0,
                                        CollectionAccumulator.empty(), false, exception.getMessage(), 0));
                    }));
        } catch (RuntimeException persistenceException) {
            log.warn("failed to persist hik collection error mappingId={} reason={}",
                    mappingId, persistenceException.getMessage());
        }
    }

    private void recordProjectCollectionFailure(String proCode, RuntimeException exception) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<HikOrganizationMapping> mappings = mappingRepository.findByProCodeOrderByOrgIndexCodeAsc(proCode)
                        .stream().filter(HikOrganizationMapping::isEnabled).toList();
                for (HikOrganizationMapping mapping : mappings) {
                    HikCollectionCursor cursor = cursorRepository.findByMappingId(mapping.getId())
                            .orElseGet(() -> newCursor(mapping));
                    cursor.setLastError(abbreviate(exception.getMessage(), 1000));
                    cursorRepository.save(cursor);
                }
                configRepository.findByProjectProCodeAndIntegrationType(proCode, "HIKVISION")
                        .ifPresent(config -> saveCallLog(config, null, null, 0, mappings.size(), 0,
                                CollectionAccumulator.empty(), false, exception.getMessage(), 0));
            });
        } catch (RuntimeException persistenceException) {
            log.warn("failed to persist hik project collection error proCode={} reason={}",
                    proCode, persistenceException.getMessage());
        }
    }

    private List<String> organizationCodes(HikOrganizationMapping mapping, IntegrationConfig config) {
        return organizationCodes(mapping, mapping.isIncludeChildren() ? gateway.queryOrganizations(config) : List.of());
    }

    private List<String> organizationCodes(HikOrganizationMapping mapping, List<HikOrganization> organizations) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(mapping.getOrgIndexCode());
        if (mapping.isIncludeChildren()) {
            boolean changed;
            do {
                changed = false;
                for (HikOrganization organization : organizations) {
                    if (organization.orgIndexCode() != null && organization.parentOrgIndexCode() != null
                            && codes.contains(organization.parentOrgIndexCode())) {
                        changed |= codes.add(organization.orgIndexCode());
                    }
                }
            } while (changed);
        }
        if (codes.size() > 1000) {
            throw new BusinessException("HIK_ORGANIZATION_SCOPE_TOO_LARGE", "所选组织及下级组织超过1000个，请拆分组织映射");
        }
        return List.copyOf(codes);
    }

    private Instant startOfProjectDay(String proCode, Instant referenceTime) {
        String configuredZoneId = syncSettingRepository.findByProjectProCode(proCode)
                .map(setting -> setting.getZoneId())
                .filter(zoneId -> zoneId != null && !zoneId.isBlank())
                .orElse(DEFAULT_PROJECT_ZONE.getId());
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(configuredZoneId);
        } catch (DateTimeException exception) {
            log.warn("invalid project zone id, fallback to default proCode={} zoneId={}", proCode, configuredZoneId);
            zoneId = DEFAULT_PROJECT_ZONE;
        }
        return referenceTime.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant();
    }

    private HikAttendanceEvent saveEvent(HikOrganizationMapping mapping, HikEventRecord record) {
        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId(record.eventId().trim());
        event.setProCode(mapping.getProCode());
        event.setOrgIndexCode(trim(record.orgIndexCode()));
        event.setHikPersonId(trim(record.hikPersonId()));
        event.setPersonName(trim(record.personName()));
        event.setIdcardType(trim(record.idcardType()));
        String idNumber = trim(record.idcardNumber());
        event.setIdcardEncrypted(cryptoService.encrypt(idNumber));
        if (idNumber != null) {
            event.setIdcardHash(cryptoService.hmac(defaultValue(event.getIdcardType(), "SHENFEN_ZHENGJIAN") + ":" + idNumber));
        }
        event.setEventTime(record.eventTime());
        event.setDirection(defaultValue(trim(record.direction()), "UNKNOWN"));
        event.setCheckType(defaultValue(trim(record.checkType()), "ZHENGCHANG_KAOQINLEIBIE"));
        event.setCheckWay(defaultValue(trim(record.checkWay()), "FACE_FANGSHI"));
        event.setCheckLocation(trim(record.checkLocation()));
        event.setLongitude(trim(record.longitude()));
        event.setLatitude(trim(record.latitude()));
        event.setDoorIndexCode(trim(record.doorIndexCode()));
        event.setDeviceIndexCode(trim(record.deviceIndexCode()));
        event.setRawPayloadEncrypted(cryptoService.encrypt(defaultValue(record.rawJson(), "{}")));
        event.setMatchStatus(MatchStatus.UNMATCHED);
        event.setMatchReason("等待自动匹配");
        return eventRepository.saveAndFlush(event);
    }

    private EnrichmentResult enrichExistingEvent(HikAttendanceEvent event, HikEventRecord record) {
        boolean changed = false;
        boolean identityChanged = false;
        String incomingPersonName = trim(record.personName());
        if (trim(event.getPersonName()) == null && incomingPersonName != null) {
            event.setPersonName(incomingPersonName);
            changed = true;
        }
        String incomingHikPersonId = trim(record.hikPersonId());
        if (trim(event.getHikPersonId()) == null && incomingHikPersonId != null) {
            event.setHikPersonId(incomingHikPersonId);
            changed = true;
            identityChanged = true;
        }
        String incomingIdNumber = trim(record.idcardNumber());
        if (trim(event.getIdcardEncrypted()) == null && incomingIdNumber != null) {
            String idcardType = defaultValue(trim(record.idcardType()),
                    defaultValue(trim(event.getIdcardType()), "SHENFEN_ZHENGJIAN"));
            event.setIdcardType(idcardType);
            event.setIdcardEncrypted(cryptoService.encrypt(incomingIdNumber));
            event.setIdcardHash(cryptoService.hmac(idcardType + ":" + incomingIdNumber));
            changed = true;
            identityChanged = true;
        }
        String incomingLocation = trim(record.checkLocation());
        if (trim(event.getCheckLocation()) == null && incomingLocation != null) {
            event.setCheckLocation(incomingLocation);
            changed = true;
        }
        String incomingDoor = trim(record.doorIndexCode());
        if (trim(event.getDoorIndexCode()) == null && incomingDoor != null) {
            event.setDoorIndexCode(incomingDoor);
            changed = true;
        }
        String incomingDevice = trim(record.deviceIndexCode());
        if (trim(event.getDeviceIndexCode()) == null && incomingDevice != null) {
            event.setDeviceIndexCode(incomingDevice);
            changed = true;
        }
        String incomingDirection = trim(record.direction());
        if ((trim(event.getDirection()) == null || "UNKNOWN".equals(event.getDirection()))
                && incomingDirection != null && !"UNKNOWN".equals(incomingDirection)) {
            event.setDirection(incomingDirection);
            changed = true;
        }
        if (changed) {
            event.setRawPayloadEncrypted(cryptoService.encrypt(defaultValue(record.rawJson(), "{}")));
            eventRepository.saveAndFlush(event);
        }
        return new EnrichmentResult(changed, identityChanged);
    }

    private HikCollectionCursor newCursor(HikOrganizationMapping mapping) {
        HikCollectionCursor cursor = new HikCollectionCursor();
        cursor.setMapping(mapping);
        cursor.setOverlapSeconds(properties.getHikvision().getOverlapSeconds());
        return cursor;
    }

    private void saveCallLog(IntegrationConfig config, Instant start, Instant end, int pages, int mappingCount,
                             int organizationCount, CollectionAccumulator result, boolean success,
                             String error, long durationMs) {
        // 空增量轮次只推进游标，不写成功日志；否则每个项目每小时都会产生一条无业务变化的记录。
        if (success && result.inserted() == 0 && result.enriched() == 0) return;
        IntegrationCallLog call = new IntegrationCallLog();
        call.setProject(config.getProject());
        call.setIntegrationType("HIKVISION");
        call.setOperationType("QUERY_ATTENDANCE_EVENTS");
        call.setRequestPath(properties.getHikvision().getEventPath());
        try {
            call.setRequestSummaryJson(objectMapper.writeValueAsString(
                    new QuerySummary(start, end, pages, mappingCount, organizationCount)));
            call.setResponseSummaryJson(objectMapper.writeValueAsString(
                    new ResponseSummary(result.queried(), result.inserted(), result.enriched(), result.duplicates())));
        } catch (Exception ignored) {
            call.setRequestSummaryJson("{}");
            call.setResponseSummaryJson("{}");
        }
        call.setSuccess(success);
        call.setDurationMs(durationMs);
        call.setErrorCode(success ? null : "HIKVISION_QUERY_FAILED");
        call.setErrorMessage(success ? null : abbreviate(error, 1000));
        callLogRepository.save(call);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private record QuerySummary(Instant startTime, Instant endTime, int pageCount,
                                int mappingCount, int organizationCount) {
    }

    private record ResponseSummary(int queried, int inserted, int enriched, int duplicates) {
    }

    private record EnrichmentResult(boolean changed, boolean identityChanged) {
    }

    private record LatestEvent(Instant eventTime, String eventId) {
    }

    private record CollectionAccumulator(int queried, int inserted, int enriched, int duplicates,
                                         int pages, long durationMs, Map<Long, LatestEvent> latestByMapping) {
        static CollectionAccumulator empty() {
            return new CollectionAccumulator(0, 0, 0, 0, 0, 0, Map.of());
        }
    }

    public record CollectionResult(Long mappingId, Instant startTime, Instant endTime, int queried,
                                   int inserted, int enriched, int duplicates, Instant cursorTime) {
    }

    public record ProjectCollectionResult(String proCode, int mappings, int succeeded, int failed,
                                          int queried, int inserted, int enriched, int duplicates) {
    }
}
