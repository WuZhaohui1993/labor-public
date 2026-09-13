package com.labor.sync.audit;

import com.labor.sync.security.SecurityUtils;
import com.labor.sync.security.WorkspaceContext;
import com.labor.sync.masterdata.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;
    private final ObjectProvider<HttpServletRequest> requestProvider;
    private final ProjectRepository projectRepository;

    public void record(String action, String resourceType, Object resourceId, String reason) {
        record(action, resourceType, resourceId, reason, "SUCCESS", SecurityUtils.currentUsername(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticationFailure(String username, String reason, String clientIp) {
        record("LOGIN_FAILURE", "AUTH", username, reason, "FAILED", username == null || username.isBlank() ? "anonymous" : username, clientIp);
    }

    private void record(String action, String resourceType, Object resourceId, String reason,
                        String result, String actor, String explicitClientIp) {
        AuditLog log = new AuditLog();
        WorkspaceContext.WorkspaceRef workspace = WorkspaceContext.current();
        if (workspace != null) log.setProject(projectRepository.getReferenceById(workspace.projectId()));
        log.setActor(actor);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        log.setReason(reason);
        log.setResult(result);
        log.setTraceId(MDC.get("traceId") == null ? "system" : MDC.get("traceId"));
        HttpServletRequest request = requestProvider.getIfAvailable();
        log.setClientIp(explicitClientIp != null ? explicitClientIp : request == null ? null : clientIp(request));
        repository.save(log);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
    }
}
