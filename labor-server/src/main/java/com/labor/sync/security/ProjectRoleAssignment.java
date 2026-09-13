package com.labor.sync.security;

import com.labor.sync.masterdata.LaborProject;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_user_project_role")
public class ProjectRoleAssignment {
    @EmbeddedId
    private ProjectRoleAssignmentId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private LaborProject project;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    public ProjectRoleAssignment(AppUser user, LaborProject project, Role role) {
        this.id = new ProjectRoleAssignmentId(user.getId(), project.getId(), role.getId());
        this.user = user;
        this.project = project;
        this.role = role;
    }
}
