package com.labor.sync.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class WorkspaceContextFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Project-Code";
    private static final String API_PREFIX = "/api/labor/admin";
    private static final Set<String> WORKSPACE_PREFIXES = Set.of(
            "/dashboard", "/imports", "/companies", "/teams", "/persons",
            "/integration-configs", "/workspace-settings", "/hik", "/attendance-statistics",
            "/matches", "/push", "/audit-logs"
    );

    private final WorkspaceAccessService accessService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!requiresWorkspace(request)) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        String proCode = request.getHeader(HEADER);
        if (proCode == null || proCode.isBlank()) {
            writeError(response, new BusinessException("WORKSPACE_REQUIRED", "请先选择项目工作区"));
            return;
        }
        try {
            WorkspaceAccessService.WorkspaceAccess access = accessService.resolve(authentication.getName(), proCode.trim());
            Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
            access.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            access.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
            var scoped = new UsernamePasswordAuthenticationToken(
                    authentication.getPrincipal(), authentication.getCredentials(), authorities);
            scoped.setDetails(authentication.getDetails());
            SecurityContextHolder.getContext().setAuthentication(scoped);
            WorkspaceContext.set(new WorkspaceContext.WorkspaceRef(access.project().getId(),
                    access.project().getProCode(), access.project().getProjectName()));
            chain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeError(response, exception);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private boolean requiresWorkspace(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith(API_PREFIX)) return false;
        String path = uri.substring(API_PREFIX.length());
        return WORKSPACE_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private void writeError(HttpServletResponse response, BusinessException exception) throws IOException {
        response.setStatus(exception.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(exception.getCode(), exception.getMessage()));
    }
}
