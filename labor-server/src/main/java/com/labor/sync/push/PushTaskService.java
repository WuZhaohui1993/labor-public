package com.labor.sync.push;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.integration.IntegrationConfig;
import com.labor.sync.integration.IntegrationConfigRepository;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.integration.LaborPlatformGateway;
import com.labor.sync.integration.LaborPushResult;
import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.DomainOutbox;
import com.labor.sync.masterdata.DomainOutboxRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.masterdata.TeamRepository;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushTaskService {
    private static final String COMPANY_PERSON_PUSH_DISABLED_REASON = "参建企业已关闭人员及考勤推送";
    private static final List<PushTaskType> PIPELINE_ORDER = List.of(
            PushTaskType.PROJECT, PushTaskType.COMPANY, PushTaskType.TEAM,
            PushTaskType.PERSON, PushTaskType.ATTENDANCE);
    private static final Set<PushTaskStatus> DISPATCHABLE_STATUSES =
            Set.of(PushTaskStatus.PENDING, PushTaskStatus.FAILED);
    private static final Set<PushTaskStatus> BLOCKING_STATUSES = Set.of(
            PushTaskStatus.WAITING_CONFIRM, PushTaskStatus.PENDING, PushTaskStatus.RUNNING,
            PushTaskStatus.FAILED);
    private final PushTaskRepository taskRepository;
    private final DomainOutboxRepository outboxRepository;
    private final IntegrationCallLogRepository callLogRepository;
    private final IntegrationConfigRepository configRepository;
    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final PushPayloadService payloadService;
    private final LaborPlatformGateway gateway;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final PlatformTransactionManager transactionManager;
    private final ProjectSyncSettingRepository syncSettingRepository;

    @Scheduled(fixedDelayString = "${app.scheduling.outbox-delay-ms:5000}")
    @Transactional
    public void consumeOutbox() {
        Instant now = Instant.now();
        List<DomainOutbox> events = new ArrayList<>(outboxRepository
                .findTop50ByStatusAndNextAttemptAtIsNullOrderByCreatedAtAsc("PENDING"));
        if (events.size() < 50) {
            events.addAll(outboxRepository.findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc("PENDING", now));
        }
        events.stream().distinct().limit(properties.getWorker().getBatchSize()).forEach(this::consumeEvent);
    }

    public void dispatchDueRetries() {
        recoverStaleTasks();
        int limit = Math.max(1, properties.getWorker().getMaxTasksPerRun());
        taskRepository.findDispatchableExcludingType(PushTaskType.ATTENDANCE,
                        Set.of(PushTaskStatus.FAILED), Instant.now(), PageRequest.of(0, limit))
                .stream().map(PushTask::getProCode).distinct()
                .filter(this::automaticPushEnabled)
                .forEach(proCode -> {
                    try {
                        activateQueuedProject(proCode);
                        dispatchNonAttendanceProject(proCode);
                    } catch (Exception exception) {
                        log.error("push compensation failed proCode={} reason={}",
                                proCode, exception.getMessage(), exception);
                    }
                });
    }

    public void dispatch() {
        recoverStaleTasks();
        int processed = 0;
        int maxTasks = Math.max(properties.getWorker().getBatchSize(), properties.getWorker().getMaxTasksPerRun());
        while (processed < maxTasks) {
            int pageSize = Math.min(properties.getWorker().getBatchSize(), maxTasks - processed);
            List<PushTask> tasks = taskRepository.findDispatchable(
                    Set.of(PushTaskStatus.PENDING, PushTaskStatus.FAILED), Instant.now(),
                    PageRequest.of(0, pageSize));
            if (tasks.isEmpty()) break;
            executeBatch(tasks.stream().map(PushTask::getId).toList());
            processed += tasks.size();
            if (tasks.size() < pageSize) break;
        }
    }

    public void dispatchProject(String proCode) {
        recoverStaleTasks();
        for (PushTaskType taskType : PIPELINE_ORDER) {
            if (!dispatchStage(proCode, taskType)) break;
        }
    }

    private boolean dispatchNonAttendanceProject(String proCode) {
        for (PushTaskType taskType : PIPELINE_ORDER) {
            if (taskType == PushTaskType.ATTENDANCE) return true;
            if (!dispatchStage(proCode, taskType)) return false;
        }
        return true;
    }

    public boolean dispatchProjectDependencies(String proCode) {
        recoverStaleTasks();
        return dispatchNonAttendanceProject(proCode);
    }

    public WindowDispatchResult dispatchProjectWindow(String proCode, Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new BusinessException("PUSH_WINDOW_INVALID", "考勤推送窗口无效");
        }
        recoverStaleTasks();
        boolean dependenciesReady = dispatchNonAttendanceProject(proCode);
        if (dependenciesReady) dispatchAttendanceThrough(proCode, windowEnd);
        long remaining = taskRepository.countAttendanceThroughByStatus(proCode, PushTaskType.ATTENDANCE,
                windowEnd, Set.of(PushTaskStatus.WAITING_CONFIRM, PushTaskStatus.PENDING,
                        PushTaskStatus.RUNNING, PushTaskStatus.FAILED));
        return new WindowDispatchResult(proCode, windowStart, windowEnd, dependenciesReady,
                remaining, dependenciesReady && remaining == 0);
    }

    public WindowDispatchResult dispatchExactAttendanceWindow(String proCode, Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new BusinessException("PUSH_WINDOW_INVALID", "考勤推送窗口无效");
        }
        recoverStaleTasks();
        boolean dependenciesReady = dispatchNonAttendanceProject(proCode);
        if (dependenciesReady) dispatchAttendanceInWindow(proCode, windowStart, windowEnd);
        long remaining = taskRepository.countAttendanceInWindowByStatus(proCode, PushTaskType.ATTENDANCE,
                windowStart, windowEnd, Set.of(PushTaskStatus.WAITING_CONFIRM, PushTaskStatus.PENDING,
                        PushTaskStatus.RUNNING, PushTaskStatus.FAILED));
        return new WindowDispatchResult(proCode, windowStart, windowEnd, dependenciesReady,
                remaining, dependenciesReady && remaining == 0);
    }

    private void dispatchAttendanceThrough(String proCode, Instant windowEnd) {
        int pageSize = Math.max(1, properties.getWorker().getBatchSize());
        while (true) {
            List<PushTask> tasks = taskRepository.findDispatchableAttendanceThrough(proCode,
                    PushTaskType.ATTENDANCE, windowEnd, DISPATCHABLE_STATUSES, Instant.now(),
                    PageRequest.of(0, pageSize));
            if (tasks.isEmpty()) break;
            executeBatch(tasks.stream().map(PushTask::getId).toList());
            if (tasks.size() < pageSize) break;
        }
    }

    private void dispatchAttendanceInWindow(String proCode, Instant windowStart, Instant windowEnd) {
        int pageSize = Math.max(1, properties.getWorker().getBatchSize());
        while (true) {
            List<PushTask> tasks = taskRepository.findDispatchableAttendanceInWindow(proCode,
                    PushTaskType.ATTENDANCE, windowStart, windowEnd, DISPATCHABLE_STATUSES, Instant.now(),
                    PageRequest.of(0, pageSize));
            if (tasks.isEmpty()) break;
            executeBatch(tasks.stream().map(PushTask::getId).toList());
            if (tasks.size() < pageSize) break;
        }
    }

    private boolean dispatchStage(String proCode, PushTaskType taskType) {
        int pageSize = Math.max(1, properties.getWorker().getBatchSize());
        while (true) {
            List<PushTask> tasks = taskRepository.findDispatchableByProjectAndType(proCode, taskType,
                    DISPATCHABLE_STATUSES, Instant.now(), PageRequest.of(0, pageSize));
            if (tasks.isEmpty()) break;
            executeBatch(tasks.stream().map(PushTask::getId).toList());
            if (tasks.size() < pageSize) break;
        }
        return !taskRepository.existsByProCodeAndTaskTypeAndStatusInAndReplayJobIdIsNull(
                proCode, taskType, BLOCKING_STATUSES);
    }

    @Transactional
    public PushTask ensureAttendanceTask(HikAttendanceEvent event) {
        LaborPerson person = personRepository.findById(event.getMatchedPersonId())
                .orElseThrow(() -> new BusinessException("PERSON_NOT_FOUND", "匹配人员不存在"));
        PushTask personTask = ensurePersonTask(person, person.getDataVersionNo());
        int matchVersion = Math.max(1, event.getMatchVersion());
        String key = key(PushTaskType.ATTENDANCE, event.getId(), matchVersion);
        boolean pushEnabled = companyPersonPushEnabled(event.getProCode(), personTask.getCompanyCode());
        return taskRepository.findByIdempotencyKey(key)
                .map(task -> alignCompanyPersonPushSetting(
                        refreshDependency(task, personTask.getIdempotencyKey()), pushEnabled))
                .orElseGet(() -> createTask(PushTaskType.ATTENDANCE, "ATTENDANCE",
                        String.valueOf(event.getId()), event.getProCode(), event.getEventId(), matchVersion,
                        key, personTask.getIdempotencyKey(), personTask.getCompanyCode(), event.getEventTime(),
                        50, false));
    }

    @Transactional
    public AttendanceReconciliationResult reconcileHikAttendance(HikAttendanceEvent hikEvent) {
        if (hikEvent.getAttendanceSource() != AttendanceSource.HIKVISION
                || hikEvent.getMatchedPersonId() == null || hikEvent.getEventTime() == null) {
            return AttendanceReconciliationResult.none();
        }
        ZoneId zone = zoneId(syncSettingRepository.findByProjectProCode(hikEvent.getProCode())
                .map(com.labor.sync.workspace.ProjectSyncSetting::getZoneId).orElse("Asia/Shanghai"));
        var localTime = hikEvent.getEventTime().atZone(zone);
        LocalDate date = localTime.toLocalDate();
        boolean morning = localTime.toLocalTime().isBefore(LocalTime.NOON);
        Instant windowStart = (morning ? date.atStartOfDay(zone) : date.atTime(LocalTime.NOON).atZone(zone)).toInstant();
        Instant windowEnd = (morning ? date.atTime(LocalTime.NOON).atZone(zone)
                : date.plusDays(1).atStartOfDay(zone)).toInstant();
        List<HikAttendanceEvent> completedEvents = eventRepository
                .findByProCodeAndMatchedPersonIdAndAttendanceSourceAndEventTimeGreaterThanEqualAndEventTimeLessThan(
                        hikEvent.getProCode(), hikEvent.getMatchedPersonId(), AttendanceSource.AUTO_COMPLETED,
                        windowStart, windowEnd);
        if (completedEvents.isEmpty()) return AttendanceReconciliationResult.none();

        List<PushTask> completedTasks = completedEvents.stream().flatMap(event -> taskRepository
                        .findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
                                PushTaskType.ATTENDANCE, String.valueOf(event.getId())).stream())
                .toList();
        boolean completionMayBeExternal = completedTasks.stream().anyMatch(task ->
                Set.of(PushTaskStatus.SUCCESS, PushTaskStatus.RUNNING).contains(task.getStatus()));
        int paused = 0;
        if (completionMayBeExternal) {
            paused = pauseForReconciliation(hikEvent,
                    "同一人员同一半日的补全考勤已经推送，为避免接收端重复，真实海康记录暂不自动外发");
        } else {
            for (HikAttendanceEvent completedEvent : completedEvents) {
                paused += pauseForReconciliation(completedEvent,
                        "同一人员同一半日已采集到真实海康考勤，未推送的补全记录已停止外发");
            }
        }
        return new AttendanceReconciliationResult(completedEvents.size(), completionMayBeExternal,
                completionMayBeExternal ? paused : 0, completionMayBeExternal ? 0 : paused);
    }

    private int pauseForReconciliation(HikAttendanceEvent event, String reason) {
        int paused = 0;
        for (PushTask task : taskRepository.findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
                PushTaskType.ATTENDANCE, String.valueOf(event.getId()))) {
            if (!Set.of(PushTaskStatus.WAITING_CONFIRM, PushTaskStatus.PENDING, PushTaskStatus.FAILED)
                    .contains(task.getStatus())) continue;
            task.setStatus(PushTaskStatus.PAUSED);
            task.setNextRetryAt(null);
            task.setManualReason(abbreviate(reason, 500));
            taskRepository.save(task);
            paused++;
        }
        return paused;
    }

    @Transactional
    public void pauseAttendanceTask(HikAttendanceEvent event, String reason) {
        taskRepository.findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
                PushTaskType.ATTENDANCE, String.valueOf(event.getId())).forEach(task -> {
            if (task.getStatus() != PushTaskStatus.SUCCESS) {
                task.setStatus(PushTaskStatus.PAUSED);
                task.setManualReason(abbreviate(reason, 500));
                taskRepository.save(task);
            }
        });
    }

    @Transactional
    public void applyCompanyPersonPushSetting(LaborCompany company) {
        taskRepository.findByProCodeAndCompanyCodeAndReplayJobIdIsNull(
                        company.getProCode(), company.getCollCropCode()).stream()
                .filter(task -> Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE)
                        .contains(task.getTaskType()))
                .forEach(task -> alignCompanyPersonPushSetting(task, company.isPersonPushEnabled()));

        if (!company.isPersonPushEnabled()) return;
        Set<String> teamIds = teamRepository
                .findByProCodeAndStatusNotOrderByTeamNameAsc(company.getProCode(), MasterDataStatus.DISABLED)
                .stream().filter(team -> company.getCollCropCode().equals(team.getCollCropCode()))
                .map(LaborTeam::getTeamId).collect(java.util.stream.Collectors.toSet());
        List<LaborPerson> persons = personRepository
                .findByProCodeAndStatusNotOrderByNameAsc(company.getProCode(), MasterDataStatus.DISABLED)
                .stream().filter(person -> teamIds.contains(person.getTeamId())).toList();
        persons.forEach(person -> ensurePersonTask(person, person.getDataVersionNo()));
        Set<Long> personIds = persons.stream().map(LaborPerson::getId)
                .collect(java.util.stream.Collectors.toSet());
        eventRepository.findByProCodeAndMatchStatusOrderByEventTimeAsc(
                        company.getProCode(), com.labor.sync.hik.MatchStatus.MATCHED)
                .stream().filter(event -> personIds.contains(event.getMatchedPersonId()))
                .forEach(this::ensureAttendanceTask);
    }

    @Transactional
    public RebuildResult rebuildProject(String proCode) {
        return rebuildReplaySources(proCode, null);
    }

    @Transactional
    public RebuildResult rebuildReplaySources(String proCode, PushTaskType taskType) {
        LaborProject project = projectRepository.findByProCode(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        int before = Math.toIntExact(taskRepository.countByProCodeAndReplayJobIdIsNull(proCode));
        if (taskType == null || taskType == PushTaskType.PROJECT) {
            ensureProjectTask(project, project.getDataVersionNo(), project.getStatus() == MasterDataStatus.DISABLED);
        }
        if (taskType == null || taskType == PushTaskType.COMPANY) {
            companyRepository.findAll().stream().filter(item -> item.getProCode().equals(proCode))
                    .forEach(item -> ensureCompanyTask(item, item.getDataVersionNo(),
                            item.getStatus() == MasterDataStatus.DISABLED));
        }
        if (taskType == null || taskType == PushTaskType.TEAM) {
            teamRepository.findAll().stream().filter(item -> item.getProCode().equals(proCode))
                    .forEach(item -> ensureTeamTask(item, item.getDataVersionNo(),
                            item.getStatus() == MasterDataStatus.DISABLED));
        }
        if (taskType == null || taskType == PushTaskType.PERSON) {
            personRepository.findAll().stream().filter(item -> item.getProCode().equals(proCode))
                    .forEach(item -> ensurePersonTask(item, item.getDataVersionNo(),
                            item.getStatus() == MasterDataStatus.DISABLED));
        }
        if (taskType == null || taskType == PushTaskType.ATTENDANCE) {
            eventRepository.findByProCodeAndMatchStatusOrderByEventTimeAsc(
                            proCode, com.labor.sync.hik.MatchStatus.MATCHED)
                    .forEach(this::ensureAttendanceTask);
        }
        int after = Math.toIntExact(taskRepository.countByProCodeAndReplayJobIdIsNull(proCode));
        return new RebuildResult(proCode, after - before, after);
    }

    @Transactional
    public StartResult startProject(String proCode) {
        var setting = syncSettingRepository.findByProjectProCode(proCode)
                .orElseThrow(() -> new BusinessException("WORKSPACE_SETTING_NOT_FOUND", "项目同步设置不存在"));
        setting.setSyncStarted(true);
        syncSettingRepository.save(setting);
        int activated = taskRepository.activateByProjectAndStatus(proCode, PushTaskStatus.WAITING_CONFIRM,
                PushTaskStatus.PENDING, "项目同步已启动，等待定时任务执行");
        int total = Math.toIntExact(taskRepository.countByProCodeAndReplayJobIdIsNull(proCode));
        return new StartResult(proCode, 0, total, activated);
    }

    @Transactional
    public StartResult refreshStartedProject(String proCode) {
        var setting = syncSettingRepository.findByProjectProCode(proCode)
                .orElseThrow(() -> new BusinessException("WORKSPACE_SETTING_NOT_FOUND", "项目同步设置不存在"));
        if (!setting.isSyncStarted()) {
            throw new BusinessException("PROJECT_SYNC_NOT_STARTED", "项目尚未授权启动同步");
        }
        return rebuildAndActivate(proCode, "项目已授权同步，等待定时任务执行");
    }

    @Transactional
    public StartResult activateQueuedProject(String proCode) {
        var setting = syncSettingRepository.findByProjectProCode(proCode)
                .orElseThrow(() -> new BusinessException("WORKSPACE_SETTING_NOT_FOUND", "项目同步设置不存在"));
        if (!setting.isSyncStarted()) {
            throw new BusinessException("PROJECT_SYNC_NOT_STARTED", "项目尚未授权启动同步");
        }
        int activated = taskRepository.activateByProjectAndStatus(proCode, PushTaskStatus.WAITING_CONFIRM,
                PushTaskStatus.PENDING, "项目已授权同步，等待半日推送时段执行");
        int total = Math.toIntExact(taskRepository.countByProCodeAndReplayJobIdIsNull(proCode));
        return new StartResult(proCode, 0, total, activated);
    }

    private StartResult rebuildAndActivate(String proCode, String reason) {
        RebuildResult rebuilt = rebuildProject(proCode);
        int activated = 0;
        for (PushTask task : taskRepository.findByProCodeAndStatusAndReplayJobIdIsNull(
                proCode, PushTaskStatus.WAITING_CONFIRM)) {
            if (task.getTaskType() == PushTaskType.ATTENDANCE || disabledMasterData(task)) continue;
            task.setStatus(PushTaskStatus.PENDING);
            task.setNextRetryAt(null);
            task.setManualReason(reason);
            taskRepository.save(task);
            activated++;
        }
        return new StartResult(proCode, rebuilt.createdTasks(), rebuilt.totalTasks(), activated);
    }

    public BatchExecutionResult execute(Long taskId) {
        return executeBatch(List.of(taskId));
    }

    public BatchExecutionResult executeBatch(List<Long> taskIds) {
        List<Long> ids = distinctIds(taskIds, 1000);
        List<PushTask> ordered = taskRepository.findAllById(ids).stream()
                .sorted(Comparator.comparingInt(PushTask::getPriority).thenComparing(PushTask::getCreatedAt))
                .toList();
        if (ordered.size() != ids.size()) {
            throw new BusinessException("PUSH_TASK_NOT_FOUND", "部分推送任务不存在");
        }
        Map<BatchKey, List<Long>> groups = new LinkedHashMap<>();
        ordered.forEach(task -> groups.computeIfAbsent(new BatchKey(task.getProCode(), task.getTaskType()),
                ignored -> new ArrayList<>()).add(task.getId()));
        BatchAccumulator accumulator = new BatchAccumulator();
        groups.values().forEach(group -> executeHomogeneousBatch(group, accumulator));
        List<PushTask> current = taskRepository.findAllById(ids);
        int succeeded = (int) current.stream().filter(task -> task.getStatus() == PushTaskStatus.SUCCESS).count();
        int failed = (int) current.stream().filter(task -> Set.of(PushTaskStatus.FAILED, PushTaskStatus.PAUSED)
                .contains(task.getStatus())).count();
        return new BatchExecutionResult(ids.size(), accumulator.claimed.size(), succeeded, failed,
                ids.size() - accumulator.claimed.size(), accumulator.httpCalls);
    }

    private void executeHomogeneousBatch(List<Long> taskIds, BatchAccumulator accumulator) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Long> claimedIds = new ArrayList<>();
        for (Long taskId : taskIds) {
            Boolean claimed = tx.execute(status -> claim(taskId));
            if (Boolean.TRUE.equals(claimed)) {
                claimedIds.add(taskId);
                accumulator.claimed.add(taskId);
            }
        }
        if (claimedIds.isEmpty()) return;

        List<ExecutionContext> contexts = new ArrayList<>();
        for (Long taskId : claimedIds) {
            long started = System.nanoTime();
            try {
                ExecutionContext context = tx.execute(status -> prepare(taskId));
                if (context != null) contexts.add(context);
            } catch (Exception exception) {
                tx.executeWithoutResult(status -> fail(taskId, exception,
                        (System.nanoTime() - started) / 1_000_000));
            }
        }
        int batchSize = Math.max(1, properties.getWorker().getBatchSize());
        for (int start = 0; start < contexts.size(); start += batchSize) {
            pushBatch(contexts.subList(start, Math.min(contexts.size(), start + batchSize)), accumulator);
        }
    }

    private void pushBatch(List<ExecutionContext> contexts, BatchAccumulator accumulator) {
        List<Map<String, Object>> requestPayload = contexts.stream()
                .flatMap(context -> context.payload().payload().stream()).toList();
        ExecutionContext first = contexts.get(0);
        long started = System.nanoTime();
        LaborPushResult result;
        try {
            accumulator.httpCalls++;
            result = gateway.push(first.config(), first.task().getProCode(),
                    first.payload().operation(), requestPayload);
        } catch (Exception exception) {
            result = new LaborPushResult(false, "PUSH_EXECUTION_ERROR", exception.getMessage(), null, "{}");
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        if (!result.success() && contexts.size() > 1 && shouldSplit(result)) {
            LaborPushResult attemptResult = result;
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    saveBatchCall(contexts, requestPayload, attemptResult, durationMs, null));
            int middle = contexts.size() / 2;
            pushBatch(contexts.subList(0, middle), accumulator);
            pushBatch(contexts.subList(middle, contexts.size()), accumulator);
            return;
        }
        LaborPushResult finalResult = result;
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                finishBatch(contexts, requestPayload, finalResult, durationMs));
    }

    @Transactional
    public PushTask retry(Long id, String reason) {
        PushTask task = find(id);
        validateRetry(task);
        resetForRetry(task, reason);
        return taskRepository.save(task);
    }

    @Transactional
    public List<PushTask> retryBatch(List<Long> taskIds, String proCode, String reason) {
        List<Long> ids = distinctIds(taskIds, 200);
        List<PushTask> tasks = taskRepository.findAllById(ids);
        if (tasks.size() != ids.size()) throw new BusinessException("PUSH_TASK_NOT_FOUND", "部分推送任务不存在");
        if (tasks.stream().anyMatch(task -> !proCode.equals(task.getProCode()))) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "只能执行当前项目工作区的任务");
        }
        tasks.forEach(this::validateRetry);
        tasks.forEach(task -> resetForRetry(task, reason));
        return taskRepository.saveAll(tasks);
    }

    @Transactional
    public void prepareProjectImmediate(String proCode) {
        taskRepository.findByProCodeAndReplayJobIdIsNull(proCode).forEach(task -> {
            if (task.getStatus() == PushTaskStatus.FAILED && !disabledMasterData(task)) {
                task.setStatus(PushTaskStatus.PENDING);
                task.setLastErrorCode(null);
                task.setLastErrorMessage(null);
            }
            if (task.getStatus() == PushTaskStatus.PENDING || task.getStatus() == PushTaskStatus.FAILED) {
                task.setNextRetryAt(null);
                task.setManualReason("人工立即执行本项目");
                taskRepository.save(task);
            }
        });
    }

    @Transactional(readOnly = true)
    public ProjectExecutionResult projectExecutionResult(String proCode) {
        List<PushTask> tasks = taskRepository.findByProCodeAndReplayJobIdIsNull(proCode);
        return new ProjectExecutionResult(proCode, tasks.size(), count(tasks, PushTaskStatus.SUCCESS),
                count(tasks, PushTaskStatus.FAILED), count(tasks, PushTaskStatus.PAUSED),
                count(tasks, PushTaskStatus.PENDING), count(tasks, PushTaskStatus.WAITING_CONFIRM));
    }

    @Transactional(readOnly = true)
    public TaskSummary taskSummary(String proCode) {
        Map<PushTaskType, Map<PushTaskStatus, Long>> counts = new EnumMap<>(PushTaskType.class);
        taskRepository.summarizeByProject(proCode).forEach(row -> {
            PushTaskType type = (PushTaskType) row[0];
            PushTaskStatus status = (PushTaskStatus) row[1];
            long value = ((Number) row[2]).longValue();
            counts.computeIfAbsent(type, ignored -> new EnumMap<>(PushTaskStatus.class)).put(status, value);
        });
        int batchSize = Math.max(1, properties.getWorker().getBatchSize());
        List<TaskGroupSummary> groups = PIPELINE_ORDER.stream().map(type -> {
            Map<PushTaskStatus, Long> byStatus = counts.getOrDefault(type, Map.of());
            long waitingConfirm = byStatus.getOrDefault(PushTaskStatus.WAITING_CONFIRM, 0L);
            long pending = byStatus.getOrDefault(PushTaskStatus.PENDING, 0L);
            long running = byStatus.getOrDefault(PushTaskStatus.RUNNING, 0L);
            long success = byStatus.getOrDefault(PushTaskStatus.SUCCESS, 0L);
            long failed = byStatus.getOrDefault(PushTaskStatus.FAILED, 0L);
            long paused = byStatus.getOrDefault(PushTaskStatus.PAUSED, 0L);
            long ignored = byStatus.getOrDefault(PushTaskStatus.IGNORED, 0L);
            long total = waitingConfirm + pending + running + success + failed + paused + ignored;
            long dispatchable = pending + failed;
            int estimatedBatches = dispatchable == 0 ? 0 : (int) ((dispatchable + batchSize - 1) / batchSize);
            return new TaskGroupSummary(type, total, waitingConfirm, pending, running, success, failed,
                    paused, ignored, estimatedBatches);
        }).toList();
        long total = groups.stream().mapToLong(TaskGroupSummary::totalCount).sum();
        return new TaskSummary(total, batchSize, groups);
    }

    private void validateRetry(PushTask task) {
        if (task.getStatus() == PushTaskStatus.RUNNING) throw new BusinessException("TASK_RUNNING", "任务正在执行");
        if (task.getStatus() == PushTaskStatus.SUCCESS) throw new BusinessException("TASK_COMPLETED", "成功任务不能重复执行");
        if (task.getStatus() == PushTaskStatus.WAITING_CONFIRM) {
            throw new BusinessException("PROJECT_SYNC_NOT_STARTED", "请先按项目启动基础信息同步");
        }
        if (!companyPersonPushAllowed(task)) {
            throw new BusinessException("COMPANY_PERSON_PUSH_DISABLED", COMPANY_PERSON_PUSH_DISABLED_REASON);
        }
        if (disabledMasterData(task)) {
            throw new BusinessException("DISABLED_RULE_UNCONFIRMED", "外部平台停用规则尚未确认，该任务不能直接执行");
        }
    }

    private void resetForRetry(PushTask task, String reason) {
        task.setStatus(PushTaskStatus.PENDING);
        task.setNextRetryAt(Instant.now());
        task.setManualReason(abbreviate(reason, 500));
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
    }

    @Transactional
    public PushTask pause(Long id, String reason) {
        PushTask task = find(id);
        if (task.getStatus() == PushTaskStatus.RUNNING) throw new BusinessException("TASK_RUNNING", "任务正在执行");
        if (task.getStatus() == PushTaskStatus.SUCCESS) throw new BusinessException("TASK_COMPLETED", "成功任务不能暂停");
        task.setStatus(PushTaskStatus.PAUSED);
        task.setManualReason(requiredReason(reason));
        return taskRepository.save(task);
    }

    @Transactional
    public PushTask ignore(Long id, String reason) {
        PushTask task = find(id);
        if (task.getStatus() == PushTaskStatus.RUNNING) throw new BusinessException("TASK_RUNNING", "任务正在执行");
        if (task.getStatus() == PushTaskStatus.SUCCESS) throw new BusinessException("TASK_COMPLETED", "成功任务不能忽略");
        task.setStatus(PushTaskStatus.IGNORED);
        task.setCompletedAt(Instant.now());
        task.setManualReason(requiredReason(reason));
        return taskRepository.save(task);
    }

    private void consumeEvent(DomainOutbox event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayloadJson(), new TypeReference<>() { });
            String type = String.valueOf(payload.get("entityType"));
            Long id = Long.valueOf(String.valueOf(payload.get("entityId")));
            int version = Integer.parseInt(String.valueOf(payload.getOrDefault("versionNo", "1")));
            ensureMasterTask(type, id, version, "MASTER_DATA_DISABLED".equals(event.getEventType()));
            event.setStatus("PROCESSED");
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
        } catch (Exception exception) {
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setLastError(abbreviate(exception.getMessage(), 1000));
            event.setNextAttemptAt(Instant.now().plus(backoff(event.getAttemptCount()), ChronoUnit.SECONDS));
            log.warn("consume outbox failed id={} reason={}", event.getId(), exception.getMessage());
        }
        outboxRepository.save(event);
    }

    private PushTask ensureMasterTask(String type, Long id, int version, boolean disabled) {
        PushTask task;
        int currentVersion;
        switch (type) {
            case "PROJECT" -> {
                LaborProject item = projectRepository.findById(id).orElseThrow(() -> missing("项目"));
                currentVersion = item.getDataVersionNo();
                task = ensureProjectTask(item, version, disabled);
            }
            case "COMPANY" -> {
                LaborCompany item = companyRepository.findById(id).orElseThrow(() -> missing("参建企业"));
                currentVersion = item.getDataVersionNo();
                task = ensureCompanyTask(item, version, disabled);
            }
            case "TEAM" -> {
                LaborTeam item = teamRepository.findById(id).orElseThrow(() -> missing("施工队"));
                currentVersion = item.getDataVersionNo();
                task = ensureTeamTask(item, version, disabled);
            }
            case "PERSON" -> {
                LaborPerson item = personRepository.findById(id).orElseThrow(() -> missing("人员"));
                currentVersion = item.getDataVersionNo();
                task = ensurePersonTask(item, version, disabled);
            }
            default -> throw new BusinessException("OUTBOX_TYPE_INVALID", "不支持的领域事件类型：" + type);
        }
        ignoreSuperseded(task.getTaskType(), task.getAggregateId(), currentVersion);
        return task;
    }

    private PushTask ensureProjectTask(LaborProject item, int version, boolean disabled) {
        String key = key(PushTaskType.PROJECT, item.getId(), version);
        return taskRepository.findByIdempotencyKey(key).orElseGet(() -> createTask(PushTaskType.PROJECT, "PROJECT",
                String.valueOf(item.getId()), item.getProCode(), item.getProCode(), version, key, null,
                null, null, 10, disabled));
    }

    private PushTask ensureCompanyTask(LaborCompany item, int version, boolean disabled) {
        LaborProject project = projectRepository.findByProCode(item.getProCode()).orElseThrow(() -> missing("上级项目"));
        PushTask dependency = ensureProjectTask(project, project.getDataVersionNo(), project.getStatus() == MasterDataStatus.DISABLED);
        String key = key(PushTaskType.COMPANY, item.getId(), version);
        return taskRepository.findByIdempotencyKey(key)
                .map(task -> refreshDependency(task, dependency.getIdempotencyKey()))
                .orElseGet(() -> createTask(PushTaskType.COMPANY, "COMPANY",
                        String.valueOf(item.getId()), item.getProCode(), item.getProCode() + "|" + item.getCollCropCode(), version,
                        key, dependency.getIdempotencyKey(), item.getCollCropCode(), null, 20, disabled));
    }

    private PushTask ensureTeamTask(LaborTeam item, int version, boolean disabled) {
        LaborCompany company = companyRepository.findByProCodeAndCollCropCode(item.getProCode(), item.getCollCropCode())
                .orElseThrow(() -> missing("上级参建企业"));
        PushTask dependency = ensureCompanyTask(company, company.getDataVersionNo(), company.getStatus() == MasterDataStatus.DISABLED);
        String key = key(PushTaskType.TEAM, item.getId(), version);
        return taskRepository.findByIdempotencyKey(key)
                .map(task -> refreshDependency(task, dependency.getIdempotencyKey()))
                .orElseGet(() -> createTask(PushTaskType.TEAM, "TEAM",
                        String.valueOf(item.getId()), item.getProCode(), item.getProCode() + "|" + item.getTeamId(), version,
                        key, dependency.getIdempotencyKey(), item.getCollCropCode(), null, 30, disabled));
    }

    private PushTask ensurePersonTask(LaborPerson item, int version) {
        return ensurePersonTask(item, version, item.getStatus() == MasterDataStatus.DISABLED);
    }

    private PushTask ensurePersonTask(LaborPerson item, int version, boolean disabled) {
        LaborTeam team = teamRepository.findByProCodeAndTeamId(item.getProCode(), item.getTeamId())
                .orElseThrow(() -> missing("上级施工队"));
        PushTask dependency = ensureTeamTask(team, team.getDataVersionNo(), team.getStatus() == MasterDataStatus.DISABLED);
        String key = key(PushTaskType.PERSON, item.getId(), version);
        boolean pushEnabled = companyPersonPushEnabled(item.getProCode(), team.getCollCropCode());
        return taskRepository.findByIdempotencyKey(key)
                .map(task -> alignCompanyPersonPushSetting(
                        refreshDependency(task, dependency.getIdempotencyKey()), pushEnabled))
                .orElseGet(() -> createTask(PushTaskType.PERSON, "PERSON",
                        String.valueOf(item.getId()), item.getProCode(), item.getProCode() + "|" + item.getIdcardHash(), version,
                        key, dependency.getIdempotencyKey(), team.getCollCropCode(), null, 40, disabled));
    }

    private PushTask createTask(PushTaskType type, String aggregateType, String aggregateId, String proCode,
                                String businessKey, int version, String idempotencyKey, String dependencyKey,
                                String companyCode, Instant businessInstant, int priority, boolean disabled) {
        PushTask task = new PushTask();
        task.setTaskType(type);
        task.setAggregateType(aggregateType);
        task.setAggregateId(aggregateId);
        task.setProCode(proCode);
        task.setBusinessKey(businessKey);
        task.setCompanyCode(companyCode);
        task.setDataVersionNo(version);
        task.setIdempotencyKey(idempotencyKey);
        task.setDependencyKey(dependencyKey);
        task.setPriority(priority);
        task.setMaxRetries(properties.getWorker().getMaxRetries());
        var setting = syncSettingRepository.findByProjectProCode(proCode);
        boolean syncStarted = setting.map(com.labor.sync.workspace.ProjectSyncSetting::isSyncStarted).orElse(false);
        ZoneId zone = zoneId(setting.map(com.labor.sync.workspace.ProjectSyncSetting::getZoneId)
                .orElse("Asia/Shanghai"));
        Instant effectiveBusinessInstant = businessInstant == null ? Instant.now() : businessInstant;
        task.setBusinessDate(LocalDate.ofInstant(effectiveBusinessInstant, zone));
        task.setBusinessAt(effectiveBusinessInstant);
        boolean companyPersonPushEnabled = !Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE).contains(type)
                || companyPersonPushEnabled(proCode, companyCode);
        task.setStatus(disabled ? PushTaskStatus.PAUSED
                : !companyPersonPushEnabled ? PushTaskStatus.IGNORED
                : type == PushTaskType.ATTENDANCE || syncStarted
                ? PushTaskStatus.PENDING : PushTaskStatus.WAITING_CONFIRM);
        task.setManualReason(disabled ? "外部接口文档未定义删除或停用规则，等待联调确认"
                : !companyPersonPushEnabled ? COMPANY_PERSON_PUSH_DISABLED_REASON
                : type == PushTaskType.ATTENDANCE ? null
                : syncStarted ? "项目已授权同步，等待每日推送" : "等待按项目启动同步");
        taskRepository.save(task);
        snapshotPayload(task);
        return task;
    }

    private ZoneId zoneId(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value);
        } catch (DateTimeException exception) {
            log.warn("invalid project zone id for push task metadata, fallback to Asia/Shanghai zoneId={}", value);
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private PushTask refreshDependency(PushTask task, String dependencyKey) {
        if (Set.of(PushTaskStatus.SUCCESS, PushTaskStatus.IGNORED).contains(task.getStatus())
                || java.util.Objects.equals(task.getDependencyKey(), dependencyKey)) return task;
        task.setDependencyKey(dependencyKey);
        if (task.getStatus() == PushTaskStatus.PENDING) task.setNextRetryAt(null);
        return taskRepository.save(task);
    }

    private PushTask alignCompanyPersonPushSetting(PushTask task, boolean enabled) {
        if (!Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE).contains(task.getTaskType())) return task;
        if (!enabled) {
            if (Set.of(PushTaskStatus.WAITING_CONFIRM, PushTaskStatus.PENDING, PushTaskStatus.FAILED)
                    .contains(task.getStatus())) {
                task.setStatus(PushTaskStatus.IGNORED);
                task.setCompletedAt(Instant.now());
                task.setNextRetryAt(null);
                task.setManualReason(COMPANY_PERSON_PUSH_DISABLED_REASON);
                return taskRepository.save(task);
            }
            return task;
        }
        if (task.getStatus() != PushTaskStatus.IGNORED
                || !COMPANY_PERSON_PUSH_DISABLED_REASON.equals(task.getManualReason())) return task;
        boolean syncStarted = syncSettingRepository.findByProjectProCode(task.getProCode())
                .map(com.labor.sync.workspace.ProjectSyncSetting::isSyncStarted).orElse(false);
        task.setStatus(task.getTaskType() == PushTaskType.ATTENDANCE || syncStarted
                ? PushTaskStatus.PENDING : PushTaskStatus.WAITING_CONFIRM);
        task.setCompletedAt(null);
        task.setNextRetryAt(null);
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setManualReason(task.getTaskType() == PushTaskType.ATTENDANCE ? null
                : syncStarted ? "参建企业已开启人员推送，等待每日推送" : "等待按项目启动同步");
        return taskRepository.save(task);
    }

    private boolean companyPersonPushEnabled(String proCode, String companyCode) {
        if (companyCode == null || companyCode.isBlank()) return false;
        return companyRepository.findByProCodeAndCollCropCode(proCode, companyCode)
                .map(LaborCompany::isPersonPushEnabled).orElse(false);
    }

    private boolean companyPersonPushAllowed(PushTask task) {
        return !Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE).contains(task.getTaskType())
                || companyPersonPushEnabled(task.getProCode(), task.getCompanyCode());
    }

    private void snapshotPayload(PushTask task) {
        try {
            PushPayloadService.PayloadBundle payload = payloadService.build(task);
            task.setPayloadEncrypted(cryptoService.encrypt(objectMapper.writeValueAsString(payload.payload())));
            taskRepository.save(task);
        } catch (BusinessException exception) {
            log.info("push task payload snapshot deferred id={} code={}", task.getId(), exception.getCode());
        } catch (Exception exception) {
            throw new IllegalStateException("推送任务快照生成失败", exception);
        }
    }

    private PushPayloadService.PayloadBundle payload(PushTask task) {
        if (task.getPayloadEncrypted() == null || task.getPayloadEncrypted().isBlank()) {
            return payloadService.build(task);
        }
        try {
            List<Map<String, Object>> values = objectMapper.readValue(cryptoService.decrypt(task.getPayloadEncrypted()),
                    new TypeReference<>() { });
            return new PushPayloadService.PayloadBundle(operation(task.getTaskType()), values);
        } catch (Exception exception) {
            throw new BusinessException("TASK_PAYLOAD_INVALID", "推送任务数据快照无法解析");
        }
    }

    private com.labor.sync.integration.LaborPushOperation operation(PushTaskType type) {
        return switch (type) {
            case PROJECT -> com.labor.sync.integration.LaborPushOperation.PROJECT;
            case COMPANY -> com.labor.sync.integration.LaborPushOperation.COMPANY;
            case TEAM -> com.labor.sync.integration.LaborPushOperation.TEAM;
            case PERSON -> com.labor.sync.integration.LaborPushOperation.PERSON;
            case ATTENDANCE -> com.labor.sync.integration.LaborPushOperation.ATTENDANCE;
        };
    }

    private void ignoreSuperseded(PushTaskType type, String aggregateId, int currentVersion) {
        taskRepository.findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
                type, aggregateId).forEach(candidate -> {
            if (candidate.getDataVersionNo() >= currentVersion
                    || Set.of(PushTaskStatus.SUCCESS, PushTaskStatus.IGNORED, PushTaskStatus.RUNNING)
                    .contains(candidate.getStatus())) return;
            candidate.setStatus(PushTaskStatus.IGNORED);
            candidate.setCompletedAt(Instant.now());
            candidate.setNextRetryAt(null);
            candidate.setManualReason("已被第 " + currentVersion + " 版数据替代，不重复推送旧快照");
            taskRepository.save(candidate);
        });
    }

    private boolean disabledMasterData(PushTask task) {
        long id;
        try {
            id = Long.parseLong(task.getAggregateId());
        } catch (NumberFormatException exception) {
            return false;
        }
        return switch (task.getTaskType()) {
            case PROJECT -> projectRepository.findById(id)
                    .map(item -> item.getStatus() == MasterDataStatus.DISABLED).orElse(false);
            case COMPANY -> companyRepository.findById(id)
                    .map(item -> item.getStatus() == MasterDataStatus.DISABLED).orElse(false);
            case TEAM -> teamRepository.findById(id)
                    .map(item -> item.getStatus() == MasterDataStatus.DISABLED).orElse(false);
            case PERSON -> personRepository.findById(id)
                    .map(item -> item.getStatus() == MasterDataStatus.DISABLED).orElse(false);
            case ATTENDANCE -> false;
        };
    }

    private boolean claim(Long taskId) {
        PushTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !Set.of(PushTaskStatus.PENDING, PushTaskStatus.FAILED).contains(task.getStatus())) return false;
        if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(Instant.now())) return false;
        if (!companyPersonPushAllowed(task)) {
            alignCompanyPersonPushSetting(task, false);
            return false;
        }
        if (!dependencyReady(task)) {
            task.setNextRetryAt(Instant.now().plusSeconds(10));
            taskRepository.save(task);
            return false;
        }
        task.setStatus(PushTaskStatus.RUNNING);
        task.setStartedAt(Instant.now());
        task.setRequestedAt(Instant.now());
        taskRepository.save(task);
        return true;
    }

    private boolean dependencyReady(PushTask task) {
        if (task.getDependencyKey() == null || task.getDependencyKey().isBlank()) return true;
        return taskRepository.findByIdempotencyKey(task.getDependencyKey())
                .map(dependency -> dependency.getStatus() == PushTaskStatus.SUCCESS)
                .orElse(false);
    }

    private boolean automaticPushEnabled(String proCode) {
        return syncSettingRepository.findByProjectProCode(proCode)
                .map(setting -> setting.isPushEnabled() && setting.isSyncStarted())
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isDependencyReady(PushTask task) {
        return dependencyReady(task);
    }

    private ExecutionContext prepare(Long taskId) {
        PushTask task = find(taskId);
        IntegrationConfig config = configRepository.findByProjectProCodeAndIntegrationType(task.getProCode(), "LABOR_PLATFORM")
                .orElseThrow(() -> new BusinessException("CONFIG_NOT_FOUND", "劳务平台接口配置不存在"));
        if (!properties.isMockEnabled() && !config.isEnabled()) {
            throw new BusinessException("INTEGRATION_DISABLED", "劳务平台接口尚未启用");
        }
        return new ExecutionContext(task, config, payload(task));
    }

    private void finishBatch(List<ExecutionContext> contexts, List<Map<String, Object>> requestPayload,
                             LaborPushResult result, long durationMs) {
        saveBatchCall(contexts, requestPayload, result, durationMs, null);
        for (ExecutionContext context : contexts) {
            PushTask task = find(context.task().getId());
            task.setRemoteCode(result.code());
            task.setRemoteMessage(abbreviate(result.message(), 1000));
            if (result.success()) {
                task.setStatus(PushTaskStatus.SUCCESS);
                task.setCompletedAt(Instant.now());
                task.setNextRetryAt(null);
                task.setLastErrorCode(null);
                task.setLastErrorMessage(null);
            } else {
                markFailure(task, result.code(), result.message());
            }
            taskRepository.save(task);
        }
    }

    private void fail(Long taskId, Exception exception, long durationMs) {
        PushTask task = find(taskId);
        String code = exception instanceof BusinessException business ? business.getCode() : "PUSH_EXECUTION_ERROR";
        LaborPushResult result = new LaborPushResult(false, code, exception.getMessage(), null, "{}");
        saveCall(task, List.of(), result, durationMs, exception);
        markFailure(task, code, exception.getMessage());
        taskRepository.save(task);
    }

    private void markFailure(PushTask task, String code, String message) {
        int retries = task.getRetryCount() + 1;
        task.setRetryCount(retries);
        task.setLastErrorCode(abbreviate(code, 100));
        task.setLastErrorMessage(abbreviate(message, 1000));
        boolean permanent = code != null && (code.startsWith("ATTENDANCE_") || "TASK_DATA_NOT_FOUND".equals(code));
        task.setStatus(permanent || retries >= task.getMaxRetries() ? PushTaskStatus.PAUSED : PushTaskStatus.FAILED);
        task.setNextRetryAt(permanent || retries >= task.getMaxRetries() ? null
                : Instant.now().plus(backoff(retries), ChronoUnit.SECONDS));
        if (permanent) task.setManualReason("数据前置校验失败，等待人工修正");
        else if (retries >= task.getMaxRetries()) task.setManualReason("达到最大重试次数，等待人工处理");
    }

    private void saveCall(PushTask task, List<Map<String, Object>> payload, LaborPushResult result,
                          long durationMs, Exception exception) {
        saveBatchCall(List.of(new ExecutionContext(task, null, null)), payload, result, durationMs, exception);
    }

    private void saveBatchCall(List<ExecutionContext> contexts, List<Map<String, Object>> payload,
                               LaborPushResult result, long durationMs, Exception exception) {
        PushTask first = contexts.get(0).task();
        IntegrationCallLog call = new IntegrationCallLog();
        projectRepository.findByProCode(first.getProCode()).ifPresent(call::setProject);
        call.setIntegrationType("LABOR_PLATFORM");
        call.setOperationType(first.getTaskType().name());
        call.setTaskId(contexts.size() == 1 ? first.getId() : null);
        call.setReplayJobId(first.getReplayJobId());
        call.setBatchSize(contexts.size());
        call.setRequestPath(path(first.getTaskType()));
        call.setRequestSummaryJson(json(payload));
        call.setHttpStatus(result.httpStatus());
        call.setResponseSummaryJson(result.responseSummary() == null ? "{}" : result.responseSummary());
        call.setSuccess(result.success());
        call.setDurationMs(durationMs);
        call.setErrorCode(result.success() ? null : abbreviate(result.code(), 100));
        call.setErrorMessage(result.success() ? null : abbreviate(exception == null ? result.message() : exception.getMessage(), 1000));
        callLogRepository.save(call);
    }

    private boolean shouldSplit(LaborPushResult result) {
        if (result.code() != null && Set.of("NETWORK_ERROR", "LABOR_TOKEN_FAILED", "LABOR_TOKEN_EMPTY",
                "TOKEN_EXPIRED", "TOKEN_INVALID", "401", "403").contains(result.code().trim().toUpperCase())) {
            return false;
        }
        return result.httpStatus() == null || result.httpStatus() < 500;
    }

    private int count(List<PushTask> tasks, PushTaskStatus status) {
        return (int) tasks.stream().filter(task -> task.getStatus() == status).count();
    }

    private List<Long> distinctIds(List<Long> taskIds, int maxSize) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BusinessException("PUSH_TASKS_REQUIRED", "请选择需要执行的推送任务");
        }
        List<Long> ids = taskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > maxSize) {
            throw new BusinessException("PUSH_BATCH_SIZE_INVALID", "单次任务数量必须在1至" + maxSize + "条之间");
        }
        return ids;
    }

    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void recoverStaleTasks() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> taskRepository.findByStatusAndStartedAtBeforeAndReplayJobIdIsNull(
                PushTaskStatus.RUNNING, Instant.now().minus(10, ChronoUnit.MINUTES)).forEach(task -> {
            task.setStatus(PushTaskStatus.FAILED);
            task.setLastErrorCode("WORKER_INTERRUPTED");
            task.setLastErrorMessage("执行进程中断，任务已恢复等待重试");
            task.setNextRetryAt(Instant.now());
            taskRepository.save(task);
        }));
    }

    private PushTask find(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new BusinessException("PUSH_TASK_NOT_FOUND", "推送任务不存在"));
    }

    private String key(PushTaskType type, Long id, int version) {
        return type.name() + ":" + id + ":V" + version;
    }

    private long backoff(int attempt) {
        long base = Math.max(1, properties.getWorker().getRetryBaseSeconds());
        return Math.min(3600, base * (1L << Math.min(10, Math.max(0, attempt - 1))));
    }

    private String path(PushTaskType type) {
        return switch (type) {
            case PROJECT -> properties.getLaborPlatform().getProjectPath();
            case COMPANY -> properties.getLaborPlatform().getCompanyPath();
            case TEAM -> properties.getLaborPlatform().getTeamPath();
            case PERSON -> properties.getLaborPlatform().getPersonPath();
            case ATTENDANCE -> properties.getLaborPlatform().getAttendancePath();
        };
    }

    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "必须填写操作原因");
        return abbreviate(reason.trim(), 500);
    }

    private String abbreviate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private BusinessException missing(String name) {
        return new BusinessException("TASK_DATA_NOT_FOUND", name + "数据不存在");
    }

    private record ExecutionContext(PushTask task, IntegrationConfig config,
                                    PushPayloadService.PayloadBundle payload) {
    }

    private record BatchKey(String proCode, PushTaskType taskType) {
    }

    private static final class BatchAccumulator {
        private final Set<Long> claimed = new LinkedHashSet<>();
        private int httpCalls;

    }

    public record RebuildResult(String proCode, int createdTasks, int totalTasks) {
    }

    public record StartResult(String proCode, int createdTasks, int totalTasks, int activatedTasks) {
    }

    public record BatchExecutionResult(int requested, int claimed, int succeeded, int failed,
                                       int deferred, int httpCalls) {
    }

    public record ProjectExecutionResult(String proCode, int total, int succeeded, int failed, int paused,
                                         int pending, int waitingConfirm) {
    }

    public record WindowDispatchResult(String proCode, Instant windowStart, Instant windowEnd,
                                       boolean dependenciesReady, long remainingAttendance,
                                       boolean completed) {
    }

    public record AttendanceReconciliationResult(int completionEventCount,
                                                  boolean completionAlreadyExternal,
                                                  int pausedHikTaskCount,
                                                  int pausedCompletionTaskCount) {
        static AttendanceReconciliationResult none() {
            return new AttendanceReconciliationResult(0, false, 0, 0);
        }
    }

    public record TaskSummary(long totalCount, int batchSize, List<TaskGroupSummary> groups) {
    }

    public record TaskGroupSummary(PushTaskType taskType, long totalCount, long waitingConfirmCount,
                                   long pendingCount, long runningCount, long successCount, long failedCount,
                                   long pausedCount, long ignoredCount, int estimatedBatchCount) {
    }
}
