package com.labor.sync.audit;

import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.PageResponse;
import com.labor.sync.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/audit-logs")
public class AuditController {
    private final AuditLogRepository repository;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public ApiResponse<PageResponse<AuditLogView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Specification<AuditLog> spec = (root, query, cb) -> cb.equal(root.get("project").get("id"), WorkspaceContext.projectId());
        var result = repository.findAll(spec,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.ok(PageResponse.from(result, AuditLogView::from));
    }

    public record AuditLogView(Long id, String actor, String action, String resourceType,
                               String resourceId, String reason, String result, String traceId,
                               String clientIp, Instant createdAt) {
        static AuditLogView from(AuditLog log) {
            return new AuditLogView(log.getId(), log.getActor(), log.getAction(), log.getResourceType(),
                    log.getResourceId(), log.getReason(), log.getResult(), log.getTraceId(),
                    log.getClientIp(), log.getCreatedAt());
        }
    }
}
