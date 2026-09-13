package com.labor.sync.security;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@Embeddable
public class ProjectRoleAssignmentId implements Serializable {
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "project_id")
    private Long projectId;
    @Column(name = "role_id")
    private Long roleId;

    public ProjectRoleAssignmentId(Long userId, Long projectId, Long roleId) {
        this.userId = userId;
        this.projectId = projectId;
        this.roleId = roleId;
    }
}
