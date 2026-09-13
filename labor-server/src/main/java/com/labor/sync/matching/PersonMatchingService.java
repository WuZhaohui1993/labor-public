package com.labor.sync.matching;

import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.push.PushTaskService;
import com.labor.sync.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PersonMatchingService {
    private final HikAttendanceEventRepository eventRepository;
    private final PersonRepository personRepository;
    private final PersonMatchHistoryRepository historyRepository;
    private final PushTaskService pushTaskService;
    private final CryptoService cryptoService;

    @Transactional
    public HikAttendanceEvent autoMatch(Long eventId) {
        HikAttendanceEvent event = findEvent(eventId);
        if (event.getMatchStatus() == MatchStatus.MATCHED || event.getMatchStatus() == MatchStatus.RELEASED) return event;
        if (!List.of("JINCHANG_JINCHU", "TUICHANG_JINCHU").contains(event.getDirection())) {
            return apply(event, null, MatchStatus.CONFLICT, null, "AUTO_CONFLICT", "海康进出方向无法识别，需要人工确认");
        }

        if (event.getIdcardHash() != null && !event.getIdcardHash().isBlank()) {
            LaborPerson person = personRepository.findByProCodeAndIdcardHash(event.getProCode(), event.getIdcardHash()).orElse(null);
            if (person != null && person.getStatus() != MasterDataStatus.DISABLED) {
                return matched(event, person, MatchMethod.CERTIFICATE, "证件号码匹配");
            }
            List<LaborPerson> legacyCandidates = legacyCertificateCandidates(event);
            if (legacyCandidates.size() == 1) {
                LaborPerson legacyPerson = legacyCandidates.get(0);
                legacyPerson.setIdcardHash(event.getIdcardHash());
                personRepository.saveAndFlush(legacyPerson);
                return matched(event, legacyPerson, MatchMethod.CERTIFICATE, "证件号码匹配（已修复历史摘要）");
            }
            if (legacyCandidates.size() > 1) {
                return apply(event, null, MatchStatus.CONFLICT, null, "AUTO_CONFLICT",
                        "同项目存在多个证件号码相同的人员，需要人工核对");
            }
        }

        if (event.getHikPersonId() != null && !event.getHikPersonId().isBlank()) {
            List<LaborPerson> candidates = personRepository.findByProCodeAndHikPersonIdAndStatusNot(
                    event.getProCode(), event.getHikPersonId(), MasterDataStatus.DISABLED);
            if (candidates.size() == 1) {
                return matched(event, candidates.get(0), MatchMethod.HIK_PERSON_ID, "海康人员编号匹配");
            }
            if (candidates.size() > 1) {
                return apply(event, null, MatchStatus.CONFLICT, null, "AUTO_CONFLICT",
                        "同项目存在多个相同海康人员编号，需要人工选择");
            }
        }
        return apply(event, null, MatchStatus.UNMATCHED, null, "AUTO_UNMATCHED",
                "未找到同项目的证件号码或海康人员编号候选；姓名不参与自动匹配");
    }

    @Transactional
    public HikAttendanceEvent confirm(Long eventId, Long personId, String reason) {
        HikAttendanceEvent event = findEvent(eventId);
        LaborPerson person = personRepository.findById(personId)
                .orElseThrow(() -> new BusinessException("PERSON_NOT_FOUND", "人员不存在"));
        if (!person.getProCode().equals(event.getProCode())) {
            throw new BusinessException("MATCH_PROJECT_MISMATCH", "只能匹配同一项目内的人员");
        }
        if (person.getStatus() == MasterDataStatus.DISABLED) {
            throw new BusinessException("PERSON_DISABLED", "已停用人员不能用于匹配");
        }
        if (!List.of("JINCHANG_JINCHU", "TUICHANG_JINCHU").contains(event.getDirection())) {
            throw new BusinessException("ATTENDANCE_DIRECTION_INVALID", "请先确认考勤进出方向");
        }
        return matched(event, person, MatchMethod.MANUAL, requiredReason(reason));
    }

    @Transactional
    public HikAttendanceEvent release(Long eventId, String reason) {
        HikAttendanceEvent event = findEvent(eventId);
        if (event.getMatchStatus() != MatchStatus.MATCHED) {
            throw new BusinessException("MATCH_NOT_ACTIVE", "当前事件没有可解除的匹配关系");
        }
        pushTaskService.pauseAttendanceTask(event, "人员匹配已解除：" + requiredReason(reason));
        return apply(event, null, MatchStatus.RELEASED, null, "MANUAL_RELEASE", reason);
    }

    @Transactional
    public HikAttendanceEvent rematch(Long eventId, String reason) {
        HikAttendanceEvent event = findEvent(eventId);
        if (event.getMatchStatus() == MatchStatus.MATCHED) {
            pushTaskService.pauseAttendanceTask(event, "重新匹配：" + requiredReason(reason));
        }
        apply(event, null, MatchStatus.UNMATCHED, null, "MANUAL_REMATCH", requiredReason(reason));
        return autoMatch(eventId);
    }

    @Transactional
    public BatchMatchResult batchRematch(List<Long> eventIds, String proCode, String reason) {
        String operationReason = requiredReason(reason);
        List<HikAttendanceEvent> events = batchEvents(eventIds, proCode);
        if (events.stream().anyMatch(event -> event.getMatchStatus() == MatchStatus.MATCHED)) {
            throw new BusinessException("BATCH_MATCHED_EVENT_INCLUDED", "批量自动匹配不能包含已匹配记录，请先解除后再操作");
        }
        List<HikAttendanceEvent> results = events.stream()
                .map(event -> rematch(event.getId(), operationReason))
                .toList();
        return summarize(results);
    }

    @Transactional
    public BatchMatchResult batchConfirm(List<Long> eventIds, Long personId, String proCode, String reason) {
        String operationReason = requiredReason(reason);
        List<HikAttendanceEvent> events = batchEvents(eventIds, proCode);
        if (events.stream().anyMatch(event -> event.getMatchStatus() == MatchStatus.MATCHED)) {
            throw new BusinessException("BATCH_MATCHED_EVENT_INCLUDED", "批量人工确认不能包含已匹配记录");
        }
        Set<String> hikPersonIds = new LinkedHashSet<>();
        Set<String> idcardHashes = new LinkedHashSet<>();
        events.forEach(event -> {
            if (event.getHikPersonId() != null && !event.getHikPersonId().isBlank()) {
                hikPersonIds.add(event.getHikPersonId().trim());
            }
            if (event.getIdcardHash() != null && !event.getIdcardHash().isBlank()) {
                idcardHashes.add(event.getIdcardHash());
            }
        });
        if (hikPersonIds.size() != 1 || events.stream().anyMatch(event -> event.getHikPersonId() == null
                || event.getHikPersonId().isBlank())) {
            throw new BusinessException("BATCH_HIK_PERSON_MISMATCH", "批量人工确认仅支持同一海康人员编号的记录");
        }
        if (idcardHashes.size() > 1) {
            throw new BusinessException("BATCH_CERTIFICATE_CONFLICT", "同一海康人员编号出现多个证件号码，请逐条核对");
        }
        LaborPerson person = personRepository.findById(personId)
                .orElseThrow(() -> new BusinessException("PERSON_NOT_FOUND", "人员不存在"));
        validatePerson(person, proCode);
        List<HikAttendanceEvent> results = events.stream()
                .map(event -> {
                    if (!List.of("JINCHANG_JINCHU", "TUICHANG_JINCHU").contains(event.getDirection())) {
                        throw new BusinessException("ATTENDANCE_DIRECTION_INVALID", "所选记录存在未确认的进出方向");
                    }
                    return matched(event, person, MatchMethod.MANUAL, operationReason);
                }).toList();
        return summarize(results);
    }

    @Transactional
    public BatchMatchResult rematchUnmatched(String proCode, int maxItems) {
        int limit = Math.min(10000, Math.max(1, maxItems));
        List<HikAttendanceEvent> results = eventRepository
                .findByProCodeAndMatchStatusOrderByEventTimeAsc(
                        proCode, MatchStatus.UNMATCHED, PageRequest.of(0, limit))
                .stream().map(event -> autoMatch(event.getId())).toList();
        return summarize(results);
    }

    private HikAttendanceEvent matched(HikAttendanceEvent event, LaborPerson person, MatchMethod method, String reason) {
        HikAttendanceEvent saved = apply(event, person.getId(), MatchStatus.MATCHED, method,
                method == MatchMethod.MANUAL ? "MANUAL_CONFIRM" : "AUTO_MATCH", reason);
        pushTaskService.ensureAttendanceTask(saved);
        pushTaskService.reconcileHikAttendance(saved);
        return saved;
    }

    private List<LaborPerson> legacyCertificateCandidates(HikAttendanceEvent event) {
        if (event.getPersonName() == null || event.getPersonName().isBlank()
                || event.getIdcardEncrypted() == null || event.getIdcardEncrypted().isBlank()) {
            return List.of();
        }
        String eventIdcard = normalizeIdcard(cryptoService.decrypt(event.getIdcardEncrypted()));
        if (eventIdcard == null) return List.of();
        return personRepository.findByProCodeAndNameAndStatusNot(
                        event.getProCode(), event.getPersonName().trim(), MasterDataStatus.DISABLED).stream()
                .filter(person -> java.util.Objects.equals(event.getIdcardType(), person.getIdcardType()))
                .filter(person -> eventIdcard.equals(normalizeIdcard(cryptoService.decrypt(person.getIdcardEncrypted()))))
                .toList();
    }

    private String normalizeIdcard(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private List<HikAttendanceEvent> batchEvents(List<Long> eventIds, String proCode) {
        if (eventIds == null || eventIds.isEmpty()) {
            throw new BusinessException("BATCH_EVENTS_REQUIRED", "请选择需要处理的匹配记录");
        }
        List<Long> ids = eventIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 200) {
            throw new BusinessException("BATCH_SIZE_INVALID", "单次批量处理数量必须在1至200条之间");
        }
        List<HikAttendanceEvent> events = eventRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(HikAttendanceEvent::getId)).toList();
        if (events.size() != ids.size()) {
            throw new BusinessException("HIK_EVENT_NOT_FOUND", "部分海康考勤事件不存在");
        }
        if (events.stream().anyMatch(event -> !proCode.equals(event.getProCode()))) {
            throw new BusinessException("MATCH_PROJECT_MISMATCH", "只能批量处理当前项目内的记录");
        }
        return events;
    }

    private void validatePerson(LaborPerson person, String proCode) {
        if (!person.getProCode().equals(proCode)) {
            throw new BusinessException("MATCH_PROJECT_MISMATCH", "只能匹配同一项目内的人员");
        }
        if (person.getStatus() == MasterDataStatus.DISABLED) {
            throw new BusinessException("PERSON_DISABLED", "已停用人员不能用于匹配");
        }
    }

    private BatchMatchResult summarize(List<HikAttendanceEvent> events) {
        return new BatchMatchResult(events.size(),
                (int) events.stream().filter(event -> event.getMatchStatus() == MatchStatus.MATCHED).count(),
                (int) events.stream().filter(event -> event.getMatchStatus() == MatchStatus.UNMATCHED).count(),
                (int) events.stream().filter(event -> event.getMatchStatus() == MatchStatus.CONFLICT).count(),
                (int) events.stream().filter(event -> event.getMatchStatus() == MatchStatus.RELEASED).count());
    }

    private HikAttendanceEvent apply(HikAttendanceEvent event, Long personId, MatchStatus status,
                                     MatchMethod method, String action, String reason) {
        PersonMatchHistory history = new PersonMatchHistory();
        history.setAttendanceEventId(event.getId());
        history.setPreviousPersonId(event.getMatchedPersonId());
        history.setPreviousStatus(event.getMatchStatus());
        history.setMatchedPersonId(personId);
        history.setMatchStatus(status);
        history.setMatchMethod(method);
        history.setActionType(action);
        history.setReason(reason);
        history.setOperatedBy(SecurityUtils.currentUsername());

        event.setMatchedPersonId(personId);
        event.setMatchStatus(status);
        event.setMatchMethod(method);
        event.setMatchReason(reason);
        event.setMatchVersion(event.getMatchVersion() + 1);
        eventRepository.save(event);
        historyRepository.save(history);
        return event;
    }

    private HikAttendanceEvent findEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException("HIK_EVENT_NOT_FOUND", "海康考勤事件不存在"));
    }

    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "必须填写操作原因");
        return reason.trim();
    }

    public record BatchMatchResult(int total, int matched, int unmatched, int conflicts, int released) {
    }
}
