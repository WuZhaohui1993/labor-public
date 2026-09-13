package com.labor.sync.bootstrap;

import com.labor.sync.integration.ProjectIntegrationProvisioner;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.security.AppUser;
import com.labor.sync.security.AppUserRepository;
import com.labor.sync.security.Permission;
import com.labor.sync.security.PermissionRepository;
import com.labor.sync.security.Role;
import com.labor.sync.security.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class BootstrapData implements ApplicationRunner {
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final AppUserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectIntegrationProvisioner integrationProvisioner;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.bootstrap-admin-password}")
    private String bootstrapPassword;

    private static final List<PermissionSeed> PERMISSIONS = List.of(
            new PermissionSeed("dashboard:view", "查看工作台", "dashboard"),
            new PermissionSeed("import:view", "查看导入批次", "import"),
            new PermissionSeed("import:upload", "上传导入文件", "import"),
            new PermissionSeed("import:submit", "提交数据复核", "import"),
            new PermissionSeed("import:review", "复核导入批次", "import"),
            new PermissionSeed("import:publish", "发布导入批次", "import"),
            new PermissionSeed("import:export", "导出错误清单", "import"),
            new PermissionSeed("master:view", "查看基础台账", "master"),
            new PermissionSeed("master:edit", "维护基础台账", "master"),
            new PermissionSeed("hik:view", "查看海康采集", "hik"),
            new PermissionSeed("hik:operate", "执行海康补查", "hik"),
            new PermissionSeed("match:view", "查看匹配结果", "match"),
            new PermissionSeed("match:confirm", "确认人员匹配", "match"),
            new PermissionSeed("push:view", "查看推送任务", "push"),
            new PermissionSeed("push:operate", "执行任务补偿", "push"),
            new PermissionSeed("system:user", "管理系统用户", "system"),
            new PermissionSeed("system:role", "管理角色权限", "system"),
            new PermissionSeed("system:menu", "管理系统菜单", "system"),
            new PermissionSeed("integration:view", "查看接口配置", "integration"),
            new PermissionSeed("integration:edit", "修改接口配置", "integration"),
            new PermissionSeed("integration:test", "执行连通测试", "integration"),
            new PermissionSeed("audit:view", "查看审计日志", "audit")
    );

    private static final Map<String, RoleSeed> ROLES = Map.of(
            "SYSTEM_ADMIN", new RoleSeed("系统管理员", Set.of("*")),
            "DATA_ADMIN", new RoleSeed("数据管理员", Set.of("dashboard:view", "import:view", "import:upload", "import:submit", "import:export", "master:view", "master:edit")),
            "DATA_REVIEWER", new RoleSeed("数据复核员", Set.of("dashboard:view", "import:view", "import:review", "import:publish", "import:export", "master:view")),
            "SYNC_OPERATOR", new RoleSeed("同步运维员", Set.of("dashboard:view", "master:view", "hik:view", "hik:operate", "match:view", "match:confirm", "push:view", "push:operate", "integration:view", "integration:test")),
            "AUDITOR", new RoleSeed("审计查看员", Set.of("dashboard:view", "import:view", "master:view", "audit:view"))
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        PERMISSIONS.forEach(seed -> permissionRepository.findByCode(seed.code()).orElseGet(() -> {
            Permission permission = new Permission();
            permission.setCode(seed.code());
            permission.setName(seed.name());
            permission.setGroup(seed.group());
            return permissionRepository.save(permission);
        }));
        List<Permission> allPermissions = permissionRepository.findAll();
        ROLES.forEach((code, seed) -> {
            Role role = roleRepository.findByCode(code).orElseGet(() -> {
                Role created = new Role();
                created.setCode(code);
                created.setName(seed.name());
                created.setBuiltin(true);
                return created;
            });
            Set<Permission> rolePermissions = new LinkedHashSet<>();
            allPermissions.stream()
                    .filter(permission -> seed.permissions().contains("*") || seed.permissions().contains(permission.getCode()))
                    .forEach(rolePermissions::add);
            role.setPermissions(rolePermissions);
            roleRepository.save(role);
        });
        if (!userRepository.existsByUsernameIgnoreCase("admin")) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setDisplayName("系统管理员");
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin.setEnabled(true);
            admin.getRoles().add(roleRepository.findByCode("SYSTEM_ADMIN").orElseThrow());
            userRepository.save(admin);
        }
        projectRepository.findAll().forEach(integrationProvisioner::provision);
    }

    private record PermissionSeed(String code, String name, String group) {}
    private record RoleSeed(String name, Set<String> permissions) {}
}
