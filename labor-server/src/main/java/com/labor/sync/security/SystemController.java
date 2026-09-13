package com.labor.sync.security;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.ProjectRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/system")
public class SystemController {
    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ProjectRoleAssignmentRepository assignmentRepository;
    private final ProjectRepository projectRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user')")
    @Transactional(readOnly = true)
    public ApiResponse<List<UserView>> users() {
        return ApiResponse.ok(userRepository.findAll().stream().map(this::userView).toList());
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('system:user')")
    @Transactional
    public ApiResponse<UserView> createUser(@Valid @RequestBody UserWrite request) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在");
        }
        AppUser user = new AppUser();
        apply(user, request, true);
        userRepository.save(user);
        applyProjectRoles(user, request);
        auditService.record("CREATE", "USER", user.getId(), null);
        return ApiResponse.ok(userView(user));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('system:user')")
    @Transactional
    public ApiResponse<UserView> updateUser(@PathVariable Long id, @Valid @RequestBody UserWrite request) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND));
        if (!user.getUsername().equalsIgnoreCase(request.username())
                && userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在");
        }
        boolean statusChanged = user.isEnabled() != request.enabled();
        boolean passwordChanged = request.password() != null && !request.password().isBlank();
        apply(user, request, false);
        if (statusChanged || passwordChanged) {
            user.setTokenVersion(user.getTokenVersion() + 1);
            refreshTokenRepository.revokeActiveByUserId(user.getId(), Instant.now());
        }
        applyProjectRoles(user, request);
        auditService.record("UPDATE", "USER", user.getId(), null);
        return ApiResponse.ok(userView(user));
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasAuthority('system:user')")
    @Transactional
    public ApiResponse<UserView> unlockUser(@PathVariable Long id) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND));
        user.setFailedLoginAttempts(0);
        user.setFailedLoginWindowStartedAt(null);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokenRepository.revokeActiveByUserId(user.getId(), Instant.now());
        auditService.record("UNLOCK_USER", "USER", user.getId(), "管理员手工解锁账号");
        return ApiResponse.ok(userView(user));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('system:user','system:role')")
    @Transactional(readOnly = true)
    public ApiResponse<List<RoleView>> roles() {
        return ApiResponse.ok(roleRepository.findAll().stream().map(RoleView::from).toList());
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role')")
    @Transactional
    public ApiResponse<RoleView> updatePermissions(@PathVariable Long id,
                                                    @RequestBody @NotEmpty Set<String> permissions) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "角色不存在", HttpStatus.NOT_FOUND));
        Set<Permission> resolved = new LinkedHashSet<>();
        permissions.forEach(code -> resolved.add(permissionRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("PERMISSION_NOT_FOUND", "权限不存在: " + code))));
        role.setPermissions(resolved);
        auditService.record("UPDATE_PERMISSIONS", "ROLE", role.getId(), null);
        return ApiResponse.ok(RoleView.from(role));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('system:user','system:role')")
    public ApiResponse<List<PermissionView>> permissions() {
        return ApiResponse.ok(permissionRepository.findAll().stream().map(PermissionView::from).toList());
    }

    private void apply(AppUser user, UserWrite request, boolean creating) {
        user.setUsername(request.username().trim());
        user.setDisplayName(request.displayName().trim());
        user.setEnabled(request.enabled());
        if (creating || request.password() != null && !request.password().isBlank()) {
            if (request.password() == null || request.password().length() < 8) {
                throw new BusinessException("PASSWORD_TOO_SHORT", "密码至少8位");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        Set<Role> roles = new LinkedHashSet<>();
        request.roleCodes().forEach(code -> roles.add(roleRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "角色不存在: " + code))));
        user.setRoles(roles);
    }

    private void applyProjectRoles(AppUser user, UserWrite request) {
        assignmentRepository.deleteByUserId(user.getId());
        List<ProjectRoleWrite> assignments = request.projectRoles();
        if (assignments == null) {
            assignments = projectRepository.findAll().stream()
                    .map(project -> new ProjectRoleWrite(project.getProCode(), request.roleCodes())).toList();
        }
        for (ProjectRoleWrite item : assignments) {
            LaborProject project = projectRepository.findByProCode(item.proCode())
                    .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目不存在: " + item.proCode()));
            for (String roleCode : item.roleCodes()) {
                Role role = roleRepository.findByCode(roleCode)
                        .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "角色不存在: " + roleCode));
                assignmentRepository.save(new ProjectRoleAssignment(user, project, role));
            }
        }
    }

    private UserView userView(AppUser user) {
        List<ProjectRoleView> projectRoles = assignmentRepository.findByUserIdOrderByProjectProjectNameAscRoleNameAsc(user.getId())
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        assignment -> assignment.getProject().getId(), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values().stream().map(items -> {
                    LaborProject project = items.get(0).getProject();
                    Set<String> roles = items.stream().map(item -> item.getRole().getCode())
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    return new ProjectRoleView(project.getId(), project.getProCode(), project.getProjectName(), roles);
                }).toList();
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.isEnabled(),
                user.getFailedLoginAttempts(), user.getLockedUntil(), user.getLastFailedLoginAt(),
                user.getRoles().stream().map(Role::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)), projectRoles);
    }

    public record UserWrite(@NotBlank @Size(max = 64) String username,
                            @NotBlank @Size(max = 100) String displayName,
                            String password,
                            boolean enabled,
                            @NotEmpty Set<String> roleCodes,
                            List<ProjectRoleWrite> projectRoles) {}

    public record ProjectRoleWrite(@NotBlank String proCode, @NotEmpty Set<String> roleCodes) {}

    public record UserView(Long id, String username, String displayName, boolean enabled,
                           int failedLoginAttempts, Instant lockedUntil, Instant lastFailedLoginAt,
                           Set<String> roles, List<ProjectRoleView> projectRoles) {}

    public record ProjectRoleView(Long projectId, String proCode, String projectName, Set<String> roles) {}

    public record RoleView(Long id, String code, String name, boolean builtin, Set<String> permissions) {
        static RoleView from(Role role) {
            return new RoleView(role.getId(), role.getCode(), role.getName(), role.isBuiltin(),
                    role.getPermissions().stream().map(Permission::getCode)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
    }

    public record PermissionView(Long id, String code, String name, String group) {
        static PermissionView from(Permission permission) {
            return new PermissionView(permission.getId(), permission.getCode(), permission.getName(), permission.getGroup());
        }
    }
}
