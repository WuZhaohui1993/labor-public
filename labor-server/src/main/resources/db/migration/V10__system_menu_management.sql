CREATE TABLE sys_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL,
    menu_type VARCHAR(16) NOT NULL,
    title VARCHAR(100) NOT NULL,
    route_name VARCHAR(100) NOT NULL,
    route_path VARCHAR(200) NOT NULL,
    component_path VARCHAR(200) NULL,
    redirect_path VARCHAR(200) NULL,
    permission_code VARCHAR(100) NULL,
    icon VARCHAR(100) NULL,
    sort_no INT NOT NULL DEFAULT 100,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    keep_alive BOOLEAN NOT NULL DEFAULT FALSE,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_menu_route_name (route_name),
    UNIQUE KEY uk_sys_menu_route_path (route_path),
    KEY idx_sys_menu_parent_sort (parent_id, sort_no),
    KEY idx_sys_menu_enabled (enabled, visible),
    CONSTRAINT fk_sys_menu_parent FOREIGN KEY (parent_id) REFERENCES sys_menu (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO sys_permission (code, name, permission_group)
VALUES ('system:menu', '管理系统菜单', 'system');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'system:menu'
WHERE role.code = 'SYSTEM_ADMIN';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, redirect_path,
     permission_code, icon, sort_no, visible, enabled, keep_alive, builtin)
VALUES
    (NULL, 'DIRECTORY', '工作台', 'Home', '/', NULL, '/welcome', NULL,
     'ep/home-filled', 1, TRUE, TRUE, FALSE, TRUE),
    (NULL, 'DIRECTORY', '导入与复核', 'Imports', '/imports', NULL, '/imports/center', NULL,
     'ri/file-excel-2-line', 2, TRUE, TRUE, FALSE, TRUE),
    (NULL, 'DIRECTORY', '基础信息', 'BasicData', '/basic-data', NULL, '/basic-data/companies', NULL,
     'ri/database-2-line', 3, TRUE, TRUE, FALSE, TRUE),
    (NULL, 'DIRECTORY', '同步管理', 'SyncManagement', '/sync', NULL, '/sync/hik', NULL,
     'ri/exchange-box-line', 4, TRUE, TRUE, FALSE, TRUE),
    (NULL, 'DIRECTORY', '接口配置', 'Integration', '/integration', NULL, '/integration/config', NULL,
     'ri/links-line', 5, TRUE, TRUE, FALSE, TRUE),
    (NULL, 'DIRECTORY', '系统管理', 'System', '/system', NULL, '/system/users', NULL,
     'ri/settings-3-line', 6, TRUE, TRUE, FALSE, TRUE);

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '工作台', 'Welcome', '/welcome', 'welcome/index', 'dashboard:view',
       'ri/dashboard-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'Home';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '导入中心', 'ImportCenter', '/imports/center', 'imports/index', 'import:view',
       'ri/upload-cloud-2-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'Imports';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '项目管理', 'ProjectManagement', '/basic-data/projects', 'basic-data/projects', 'system:menu',
       'ri/file-list-3-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'BasicData';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '参建企业管理', 'CompanyManagement', '/basic-data/companies', 'basic-data/companies', 'master:view',
       'ri/links-fill', 2, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'BasicData';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '施工队管理', 'TeamManagement', '/basic-data/teams', 'basic-data/teams', 'master:view',
       'ri/user-settings-line', 3, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'BasicData';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '人员管理', 'PersonManagement', '/basic-data/persons', 'basic-data/persons', 'master:view',
       'ri/admin-line', 4, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'BasicData';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '海康考勤采集', 'HikCollection', '/sync/hik', 'hik/index', 'hik:view',
       'ri/camera-lens-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'SyncManagement';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '人员匹配', 'PersonMatching', '/sync/matches', 'matching/index', 'match:view',
       'ri/user-search-line', 2, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'SyncManagement';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '信息推送任务', 'PushTasks', '/sync/tasks', 'push/index', 'push:view',
       'ri/send-plane-line', 3, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'SyncManagement';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '接口调用日志', 'IntegrationCallLogs', '/sync/logs', 'push-logs/index', 'push:view',
       'ri/file-search-line', 4, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'SyncManagement';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '接口配置', 'IntegrationConfig', '/integration/config', 'integration/index', 'integration:view',
       'ri/code-s-slash-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'Integration';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '用户与角色', 'SystemUsers', '/system/users', 'system/users', 'system:user',
       'ri/user-settings-line', 1, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'System';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '菜单管理', 'SystemMenus', '/system/menus', 'system/menus', 'system:menu',
       'ri/menu-2-line', 2, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'System';

INSERT INTO sys_menu
    (parent_id, menu_type, title, route_name, route_path, component_path, permission_code,
     icon, sort_no, visible, enabled, keep_alive, builtin)
SELECT id, 'PAGE', '操作审计', 'AuditLogs', '/system/audit', 'system/audit', 'audit:view',
       'ri/file-list-3-line', 3, TRUE, TRUE, FALSE, TRUE
FROM sys_menu WHERE route_name = 'System';
