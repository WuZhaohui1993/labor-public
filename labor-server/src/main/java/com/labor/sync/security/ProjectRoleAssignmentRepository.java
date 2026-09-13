package com.labor.sync.security;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRoleAssignmentRepository extends JpaRepository<ProjectRoleAssignment, ProjectRoleAssignmentId> {
    @EntityGraph(attributePaths = {"project", "role", "role.permissions"})
    List<ProjectRoleAssignment> findByUserUsernameIgnoreCaseOrderByProjectProjectNameAscRoleNameAsc(String username);

    @EntityGraph(attributePaths = {"project", "role", "role.permissions"})
    List<ProjectRoleAssignment> findByUserUsernameIgnoreCaseAndProjectProCode(String username, String proCode);

    @EntityGraph(attributePaths = {"project", "role", "role.permissions"})
    List<ProjectRoleAssignment> findByUserIdOrderByProjectProjectNameAscRoleNameAsc(Long userId);

    void deleteByUserId(Long userId);
    void deleteByUserIdAndProjectId(Long userId, Long projectId);
}
