package com.labor.sync.matching;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.common.PageResponse;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.security.WorkspaceContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/matches")
@PreAuthorize("hasAuthority('match:view')")
public class MatchingController {
    private final HikAttendanceEventRepository eventRepository;
    private final PersonMatchHistoryRepository historyRepository;
    private final PersonMatchingService matchingService;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<MatchView>> list(@RequestParam(required = false) String proCode,
                                                     @RequestParam(required = false) MatchStatus status,
                                                     @RequestParam(defaultValue = "") String search,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        Specification<HikAttendanceEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            if (status != null) predicates.add(cb.equal(root.get("matchStatus"), status));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("personName"), like(search)),
                    cb.like(root.get("hikPersonId"), like(search)), cb.like(root.get("eventId"), like(search))));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "eventTime"));
        return ApiResponse.ok(PageResponse.from(eventRepository.findAll(spec, pageable), this::view));
    }

    @GetMapping("/{eventId}/history")
    public ApiResponse<List<HistoryView>> history(@PathVariable Long eventId) {
        requireEvent(eventId);
        return ApiResponse.ok(historyRepository.findByAttendanceEventIdOrderByCreatedAtDesc(eventId).stream()
                .map(HistoryView::from).toList());
    }

    @PostMapping("/{eventId}/confirm")
    @PreAuthorize("hasAuthority('match:confirm')")
    public ApiResponse<MatchView> confirm(@PathVariable Long eventId, @Valid @RequestBody ConfirmRequest request) {
        requireEvent(eventId);
        HikAttendanceEvent event = matchingService.confirm(eventId, request.personId(), request.reason());
        auditService.record("CONFIRM", "PERSON_MATCH", eventId, "personId=" + request.personId());
        return ApiResponse.ok(view(event));
    }

    @PostMapping("/{eventId}/release")
    @PreAuthorize("hasAuthority('match:confirm')")
    public ApiResponse<MatchView> release(@PathVariable Long eventId, @Valid @RequestBody ReasonRequest request) {
        requireEvent(eventId);
        HikAttendanceEvent event = matchingService.release(eventId, request.reason());
        auditService.record("RELEASE", "PERSON_MATCH", eventId, request.reason());
        return ApiResponse.ok(view(event));
    }

    @PostMapping("/{eventId}/rematch")
    @PreAuthorize("hasAuthority('match:confirm')")
    public ApiResponse<MatchView> rematch(@PathVariable Long eventId, @Valid @RequestBody ReasonRequest request) {
        requireEvent(eventId);
        HikAttendanceEvent event = matchingService.rematch(eventId, request.reason());
        auditService.record("REMATCH", "PERSON_MATCH", eventId, request.reason());
        return ApiResponse.ok(view(event));
    }

    @PostMapping("/batch/rematch")
    @PreAuthorize("hasAuthority('match:confirm')")
    public ApiResponse<PersonMatchingService.BatchMatchResult> batchRematch(
            @Valid @RequestBody BatchRematchRequest request) {
        PersonMatchingService.BatchMatchResult result = matchingService.batchRematch(
                request.eventIds(), WorkspaceContext.proCode(), request.reason());
        auditService.record("BATCH_REMATCH", "PERSON_MATCH_BATCH", "count=" + result.total(), request.reason());
        return ApiResponse.ok(result);
    }

    @PostMapping("/batch/confirm")
    @PreAuthorize("hasAuthority('match:confirm')")
    public ApiResponse<PersonMatchingService.BatchMatchResult> batchConfirm(
            @Valid @RequestBody BatchConfirmRequest request) {
        PersonMatchingService.BatchMatchResult result = matchingService.batchConfirm(
                request.eventIds(), request.personId(), WorkspaceContext.proCode(), request.reason());
        auditService.record("BATCH_CONFIRM", "PERSON_MATCH_BATCH",
                "person=" + request.personId() + ",count=" + result.total(), request.reason());
        return ApiResponse.ok(result);
    }

    private MatchView view(HikAttendanceEvent event) {
        return new MatchView(event.getId(), event.getEventId(), event.getProCode(), event.getPersonName(),
                event.getHikPersonId(), event.getIdcardType(), cryptoService.decrypt(event.getIdcardEncrypted()),
                event.getEventTime(), event.getDirection(), event.getMatchStatus(), event.getMatchMethod(),
                event.getMatchedPersonId(), event.getMatchReason());
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private HikAttendanceEvent requireEvent(Long id) {
        HikAttendanceEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException("HIK_EVENT_NOT_FOUND", "海康考勤事件不存在", HttpStatus.NOT_FOUND));
        if (!WorkspaceContext.proCode().equals(event.getProCode())) {
            throw new BusinessException("HIK_EVENT_NOT_FOUND", "海康考勤事件不存在", HttpStatus.NOT_FOUND);
        }
        return event;
    }

    public record ConfirmRequest(@NotNull Long personId, @NotBlank String reason) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    public record BatchRematchRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> eventIds,
                                      @NotBlank @Size(max = 500) String reason) {
    }

    public record BatchConfirmRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> eventIds,
                                      @NotNull Long personId,
                                      @NotBlank @Size(max = 500) String reason) {
    }

    public record MatchView(Long id, String eventId, String proCode, String hikPersonName, String hikPersonId,
                            String idcardType, String idcardNumber, Instant eventTime, String direction,
                            MatchStatus matchStatus, MatchMethod matchMethod, Long matchedPersonId, String matchReason) {
    }

    public record HistoryView(Long id, Long previousPersonId, Long matchedPersonId, MatchStatus previousStatus,
                              MatchStatus matchStatus, MatchMethod matchMethod, String actionType, String reason,
                              String operatedBy, Instant createdAt) {
        static HistoryView from(PersonMatchHistory item) {
            return new HistoryView(item.getId(), item.getPreviousPersonId(), item.getMatchedPersonId(),
                    item.getPreviousStatus(), item.getMatchStatus(), item.getMatchMethod(), item.getActionType(),
                    item.getReason(), item.getOperatedBy(), item.getCreatedAt());
        }
    }
}
