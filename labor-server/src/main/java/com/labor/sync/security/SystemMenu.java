package com.labor.sync.security;

import com.labor.sync.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_menu")
public class SystemMenu extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private SystemMenu parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "menu_type", nullable = false, length = 16)
    private MenuType menuType;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "route_name", nullable = false, unique = true, length = 100)
    private String routeName;

    @Column(name = "route_path", nullable = false, unique = true, length = 200)
    private String routePath;

    @Column(name = "component_path", length = 200)
    private String componentPath;

    @Column(name = "redirect_path", length = 200)
    private String redirectPath;

    @Column(name = "permission_code", length = 100)
    private String permissionCode;

    @Column(length = 100)
    private String icon;

    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    @Column(nullable = false)
    private boolean visible = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "keep_alive", nullable = false)
    private boolean keepAlive;

    @Column(nullable = false)
    private boolean builtin;
}
