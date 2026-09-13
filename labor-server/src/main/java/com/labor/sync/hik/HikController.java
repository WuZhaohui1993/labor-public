package com.labor.sync.hik;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.common.PageResponse;
import com.labor.sync.integration.HikOrganization;
import com.labor.sync.integration.HikvisionGateway;
import com.labor.sync.integration.IntegrationConfig;
import com.labor.sync.integration.IntegrationConfigRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.security.SecurityUtils;
import com.labor.sync.security.WorkspaceContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/hik")
@PreAuthorize("hasAuthority('hik:view')")
public class HikController {
    private final HikOrganizationMappingRepository mappingRepository;
    private final HikCollectionCursorRepository cursorRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final IntegrationConfigRepository configRepository;
    private final HikvisionGateway hikvisionGateway;
    private final HikCollectionService collectionService;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    @GetMapping("/organizations")
    public ApiResponse<List<MappingView>> mappings() {
        return ApiResponse.ok(mappingRepository.findByProCodeOrderByOrgIndexCodeAsc(WorkspaceContext.proCode()).stream()
                .map(this::mappingView).toList());
    }

    @GetMapping("/organization-options")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<List<OrganizationOptionView>> organizationOptions() {
        IntegrationConfig config = configRepository.findByProjectProCodeAndIntegrationType(
                        WorkspaceContext.proCode(), "HIKVISION")
                .orElseThrow(() -> new BusinessException("CONFIG_NOT_FOUND", "当前项目尚未初始化海康接口配置"));
        Set<String> mappedCodes = new HashSet<>(mappingRepository
                .findByProCodeOrderByOrgIndexCodeAsc(WorkspaceContext.proCode()).stream()
                .map(HikOrganizationMapping::getOrgIndexCode).toList());
        return ApiResponse.ok(hikvisionGateway.queryOrganizations(config).stream()
                .map(item -> OrganizationOptionView.from(item, mappedCodes.contains(item.orgIndexCode())))
                .toList());
    }

    @PostMapping("/organizations")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<MappingView> createMapping(@Valid @RequestBody MappingRequest request) {
        requireWorkspace(request.proCode());
        if (projectRepository.findByProCode(request.proCode()).isEmpty()) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在");
        }
        if (mappingRepository.findByProCodeAndOrgIndexCode(request.proCode(), request.orgIndexCode()).isPresent()) {
            throw new BusinessException("HIK_MAPPING_EXISTS", "该项目与海康组织的映射已存在");
        }
        HikOrganizationMapping mapping = new HikOrganizationMapping();
        apply(mapping, request);
        mappingRepository.save(mapping);
        auditService.record("CREATE", "HIK_ORGANIZATION_MAPPING", mapping.getId(), mapping.getProCode());
        return ApiResponse.ok(mappingView(mapping));
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<MappingView> updateMapping(@PathVariable Long id, @Valid @RequestBody MappingRequest request) {
        HikOrganizationMapping mapping = mappingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("HIK_MAPPING_NOT_FOUND", "海康组织映射不存在"));
        requireWorkspace(mapping.getProCode());
        requireWorkspace(request.proCode());
        if (!mapping.getProCode().equals(request.proCode()) || !mapping.getOrgIndexCode().equals(request.orgIndexCode())) {
            throw new BusinessException("BUSINESS_KEY_IMMUTABLE", "项目编码和海康组织编码创建后不能修改");
        }
        apply(mapping, request);
        mappingRepository.save(mapping);
        auditService.record("UPDATE", "HIK_ORGANIZATION_MAPPING", mapping.getId(), mapping.getProCode());
        return ApiResponse.ok(mappingView(mapping));
    }

    @DeleteMapping("/organizations/{id}")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<Void> disableMapping(@PathVariable Long id) {
        HikOrganizationMapping mapping = mappingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("HIK_MAPPING_NOT_FOUND", "海康组织映射不存在"));
        requireWorkspace(mapping.getProCode());
        mapping.setEnabled(false);
        mappingRepository.save(mapping);
        auditService.record("DISABLE", "HIK_ORGANIZATION_MAPPING", mapping.getId(), mapping.getProCode());
        return ApiResponse.ok();
    }

    @PostMapping("/collections")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<HikCollectionService.CollectionResult> collect(@Valid @RequestBody CollectionRequest request) {
        HikOrganizationMapping mapping = mappingRepository.findById(request.mappingId())
                .orElseThrow(() -> new BusinessException("HIK_MAPPING_NOT_FOUND", "海康组织映射不存在"));
        requireWorkspace(mapping.getProCode());
        HikCollectionService.CollectionResult result = collectionService.collectMapping(
                request.mappingId(), request.startTime(), request.endTime(), request.advanceCursor());
        auditService.record("COLLECT", "HIK_ATTENDANCE", request.mappingId(),
                "新增 " + result.inserted() + "，补全 " + result.enriched() + "，重复 " + result.duplicates());
        return ApiResponse.ok(result);
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<EventView>> events(@RequestParam(required = false) String proCode,
                                                        @RequestParam(required = false) MatchStatus matchStatus,
                                                        @RequestParam(defaultValue = "") String search,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        Specification<HikAttendanceEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            if (matchStatus != null) predicates.add(cb.equal(root.get("matchStatus"), matchStatus));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("personName"), like(search)),
                    cb.like(root.get("hikPersonId"), like(search)), cb.like(root.get("eventId"), like(search))));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "eventTime"));
        return ApiResponse.ok(PageResponse.from(eventRepository.findAll(spec, pageable), this::eventView));
    }

    @PutMapping("/events/{id}/direction")
    @PreAuthorize("hasAuthority('hik:operate')")
    public ApiResponse<EventView> updateDirection(@PathVariable Long id, @Valid @RequestBody DirectionRequest request) {
        if (!List.of("JINCHANG_JINCHU", "TUICHANG_JINCHU").contains(request.direction())) {
            throw new BusinessException("DIRECTION_INVALID", "进出方向不在接口字典中");
        }
        HikAttendanceEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException("HIK_EVENT_NOT_FOUND", "海康考勤事件不存在"));
        requireWorkspace(event.getProCode());
        event.setDirection(request.direction());
        eventRepository.save(event);
        auditService.record("UPDATE_DIRECTION", "HIK_ATTENDANCE", id, request.direction());
        return ApiResponse.ok(eventView(event));
    }

    private void apply(HikOrganizationMapping mapping, MappingRequest request) {
        mapping.setProCode(request.proCode().trim());
        if ("MANUAL".equals(request.mappingSource())) {
            boolean changesManualIdentity = mapping.getId() == null || !"MANUAL".equals(mapping.getMappingSource())
                    || !Objects.equals(mapping.getOrgIndexCode(), request.orgIndexCode().trim())
                    || !Objects.equals(trim(mapping.getOrgName()), trim(request.orgName()))
                    || !Objects.equals(trim(mapping.getOrgPath()), trim(request.orgPath()));
            if (changesManualIdentity && !SecurityUtils.hasRole("SYSTEM_ADMIN")) {
                throw new BusinessException("HIK_MANUAL_MAPPING_FORBIDDEN", "只有系统管理员可以手工录入海康组织编码",
                        HttpStatus.FORBIDDEN);
            }
            mapping.setOrgIndexCode(request.orgIndexCode().trim());
            mapping.setOrgName(trim(request.orgName()));
            mapping.setOrgPath(trim(request.orgPath()));
        } else {
            IntegrationConfig config = configRepository.findByProjectProCodeAndIntegrationType(
                            request.proCode().trim(), "HIKVISION")
                    .orElseThrow(() -> new BusinessException("CONFIG_NOT_FOUND", "当前项目尚未初始化海康接口配置"));
            HikOrganization organization = hikvisionGateway.queryOrganizations(config).stream()
                    .filter(item -> request.orgIndexCode().trim().equals(item.orgIndexCode()))
                    .findFirst().orElseThrow(() -> new BusinessException("HIK_ORGANIZATION_NOT_FOUND", "所选海康组织已不存在，请刷新后重选"));
            mapping.setOrgIndexCode(organization.orgIndexCode());
            mapping.setOrgName(organization.orgName());
            mapping.setOrgPath(organization.orgPath());
        }
        mapping.setMappingSource(request.mappingSource());
        mapping.setIncludeChildren(request.includeChildren());
        mapping.setEnabled(request.enabled());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MappingView mappingView(HikOrganizationMapping mapping) {
        HikCollectionCursor cursor = cursorRepository.findByMappingId(mapping.getId()).orElse(null);
        return new MappingView(mapping.getId(), mapping.getProCode(), mapping.getOrgIndexCode(), mapping.getOrgName(),
                mapping.getOrgPath(), mapping.getMappingSource(), mapping.isIncludeChildren(), mapping.isEnabled(),
                cursor == null ? null : cursor.getLastEventTime(),
                cursor == null ? null : cursor.getLastSuccessAt(), cursor == null ? null : cursor.getLastError());
    }

    private EventView eventView(HikAttendanceEvent event) {
        String idNumber = cryptoService.decrypt(event.getIdcardEncrypted());
        return new EventView(event.getId(), event.getEventId(), event.getProCode(), event.getOrgIndexCode(),
                event.getHikPersonId(), event.getPersonName(), event.getIdcardType(), idNumber,
                event.getEventTime(), event.getDirection(), event.getCheckType(), event.getCheckWay(),
                event.getCheckLocation(), event.getAttendanceSource(),
                event.getCompletionRun() == null ? null : event.getCompletionRun().getId(),
                event.getMatchStatus(), event.getMatchMethod(), event.getMatchedPersonId(),
                event.getMatchReason(), event.getReceivedAt());
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private void requireWorkspace(String proCode) {
        if (!WorkspaceContext.proCode().equals(proCode)) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "数据不属于当前项目工作区", HttpStatus.NOT_FOUND);
        }
    }

    public record MappingRequest(@NotBlank @Size(max = 100) String proCode,
                                 @NotBlank @Size(max = 100) String orgIndexCode,
                                 @Size(max = 200) String orgName,
                                 @Size(max = 1000) String orgPath,
                                 @NotBlank @Pattern(regexp = "HIKVISION|MANUAL") String mappingSource,
                                 boolean includeChildren,
                                 boolean enabled) {
    }

    public record CollectionRequest(@NotNull Long mappingId, Instant startTime, Instant endTime, boolean advanceCursor) {
    }

    public record DirectionRequest(@NotBlank String direction) {
    }

    public record MappingView(Long id, String proCode, String orgIndexCode, String orgName, String orgPath,
                              String mappingSource, boolean includeChildren, boolean enabled,
                              Instant lastEventTime, Instant lastSuccessAt, String lastError) {
    }

    public record OrganizationOptionView(String orgIndexCode, String orgName, String parentOrgIndexCode,
                                         String orgPath, int sortOrder, boolean mapped) {
        static OrganizationOptionView from(HikOrganization item, boolean mapped) {
            return new OrganizationOptionView(item.orgIndexCode(), item.orgName(), item.parentOrgIndexCode(),
                    item.orgPath(), item.sortOrder(), mapped);
        }
    }

    public record EventView(Long id, String eventId, String proCode, String orgIndexCode, String hikPersonId,
                            String personName, String idcardType, String idcardNumber, Instant eventTime,
                            String direction, String checkType, String checkWay, String checkLocation,
                            AttendanceSource attendanceSource, Long completionRunId,
                            MatchStatus matchStatus, MatchMethod matchMethod, Long matchedPersonId,
                            String matchReason, Instant receivedAt) {
    }
}
