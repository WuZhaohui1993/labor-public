package com.labor.sync.security;

import com.labor.sync.common.BusinessException;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkspaceAccessService {
    private final AppUserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRoleAssignmentRepository assignmentRepository;

    @Transactional(readOnly = true)
    public WorkspaceAccess resolve(String username, String proCode) {
        AppUser user = findUser(username);
        LaborProject project = projectRepository.findByProCode(proCode)
                .orElseThrow(() -> new BusinessException("WORKSPACE_NOT_FOUND", "项目工作区不存在", HttpStatus.NOT_FOUND));
        if (project.getStatus() == MasterDataStatus.DISABLED) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "项目工作区不存在", HttpStatus.NOT_FOUND);
        }
        if (isSystemAdmin(user)) {
            return access(project, user.getRoles());
        }
        Set<Role> roles = new LinkedHashSet<>();
        assignmentRepository.findByUserUsernameIgnoreCaseAndProjectProCode(username, proCode)
                .forEach(assignment -> roles.add(assignment.getRole()));
        if (roles.isEmpty()) {
            throw new BusinessException("WORKSPACE_ACCESS_DENIED", "无权访问该项目工作区", HttpStatus.FORBIDDEN);
        }
        return access(project, roles);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceView> available(String username) {
        AppUser user = findUser(username);
        if (isSystemAdmin(user)) {
            Set<Role> roles = new LinkedHashSet<>(user.getRoles());
            return projectRepository.findAll().stream()
                    .filter(project -> project.getStatus() != MasterDataStatus.DISABLED)
                    .sorted(Comparator.comparing(LaborProject::getProjectName).thenComparing(LaborProject::getProCode))
                    .map(project -> view(access(project, roles)))
                    .toList();
        }
        Map<Long, WorkspaceAccumulator> grouped = new LinkedHashMap<>();
        assignmentRepository.findByUserUsernameIgnoreCaseOrderByProjectProjectNameAscRoleNameAsc(username)
                .stream()
                .filter(assignment -> assignment.getProject().getStatus() != MasterDataStatus.DISABLED)
                .forEach(assignment -> grouped
                        .computeIfAbsent(assignment.getProject().getId(), ignored -> new WorkspaceAccumulator(assignment.getProject()))
                        .roles.add(assignment.getRole()));
        return grouped.values().stream().map(item -> view(access(item.project, item.roles))).toList();
    }

    @Transactional(readOnly = true)
    public Set<Role> allAssignedRoles(String username) {
        Set<Role> roles = new LinkedHashSet<>();
        assignmentRepository.findByUserUsernameIgnoreCaseOrderByProjectProjectNameAscRoleNameAsc(username)
                .forEach(assignment -> roles.add(assignment.getRole()));
        return roles;
    }

    public boolean isSystemAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(role -> "SYSTEM_ADMIN".equals(role.getCode()));
    }

    private AppUser findUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.UNAUTHORIZED));
    }

    private WorkspaceAccess access(LaborProject project, Set<Role> roles) {
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        roles.stream().sorted(Comparator.comparing(Role::getCode)).forEach(role -> {
            roleCodes.add(role.getCode());
            role.getPermissions().stream().sorted(Comparator.comparing(Permission::getCode))
                    .forEach(permission -> permissions.add(permission.getCode()));
        });
        return new WorkspaceAccess(project, roleCodes, permissions);
    }

    private WorkspaceView view(WorkspaceAccess access) {
        LaborProject project = access.project();
        return new WorkspaceView(project.getId(), project.getProCode(), project.getProjectName(), project.getStatus().name(),
                new ArrayList<>(access.roles()), new ArrayList<>(access.permissions()));
    }

    private static class WorkspaceAccumulator {
        private final LaborProject project;
        private final Set<Role> roles = new LinkedHashSet<>();

        private WorkspaceAccumulator(LaborProject project) {
            this.project = project;
        }
    }

    public record WorkspaceAccess(LaborProject project, Set<String> roles, Set<String> permissions) {
    }

    public record WorkspaceView(Long id, String proCode, String projectName, String status,
                                List<String> roles, List<String> permissions) {
    }
}
