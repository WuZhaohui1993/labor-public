package com.labor.sync.security;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SystemMenuService {
    private static final Pattern ROUTE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> COMPONENTS = Set.of(
            "welcome/index",
            "imports/index",
            "basic-data/projects",
            "basic-data/companies",
            "basic-data/teams",
            "basic-data/persons",
            "hik/index",
            "attendance-statistics/index",
            "matching/index",
            "push/index",
            "push-logs/index",
            "integration/index",
            "system/users",
            "system/menus",
            "system/audit"
    );

    private final SystemMenuRepository repository;
    private final PermissionRepository permissionRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<MenuView> list() {
        return repository.findAllByOrderBySortNoAscIdAsc().stream().map(MenuView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<NavigationView> navigation(Set<String> permissions, boolean unrestricted) {
        List<SystemMenu> all = repository.findAllByOrderBySortNoAscIdAsc();
        Map<Long, NavigationNode> enabled = new LinkedHashMap<>();
        all.stream()
                .filter(SystemMenu::isEnabled)
                .filter(SystemMenu::isVisible)
                .filter(menu -> unrestricted || menu.getPermissionCode() == null
                        || permissions.contains(menu.getPermissionCode()))
                .forEach(menu -> enabled.put(menu.getId(), new NavigationNode(menu)));
        List<NavigationNode> roots = new ArrayList<>();
        enabled.values().forEach(node -> {
            Long parentId = node.menu.getParent() == null ? null : node.menu.getParent().getId();
            if (parentId == null) {
                roots.add(node);
            } else if (enabled.containsKey(parentId)) {
                enabled.get(parentId).children.add(node);
            }
        });
        Comparator<NavigationNode> comparator = Comparator
                .comparingInt((NavigationNode node) -> node.menu.getSortNo())
                .thenComparing(node -> node.menu.getId());
        roots.sort(comparator);
        return roots.stream().map(node -> node.view(comparator))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public MenuView create(MenuWrite request) {
        SystemMenu menu = new SystemMenu();
        menu.setBuiltin(false);
        apply(menu, request, null);
        repository.save(menu);
        auditService.record("CREATE", "MENU", menu.getId(), menu.getTitle());
        return MenuView.from(menu);
    }

    @Transactional
    public MenuView update(Long id, MenuWrite request) {
        SystemMenu menu = find(id);
        if (menu.isBuiltin()) {
            immutable("菜单类型", menu.getMenuType().name(), request.menuType().name());
            immutable("路由名称", menu.getRouteName(), request.routeName());
            immutable("路由路径", menu.getRoutePath(), request.routePath());
            immutable("组件路径", menu.getComponentPath(), request.componentPath());
            Long parentId = menu.getParent() == null ? null : menu.getParent().getId();
            if (!java.util.Objects.equals(parentId, normalizeParentId(request.parentId()))) {
                throw new BusinessException("BUILTIN_MENU_PARENT_IMMUTABLE", "内置菜单不能调整上级菜单");
            }
        }
        apply(menu, request, id);
        auditService.record("UPDATE", "MENU", menu.getId(), menu.getTitle());
        return MenuView.from(menu);
    }

    @Transactional
    public void delete(Long id) {
        SystemMenu menu = find(id);
        if (menu.isBuiltin()) {
            throw new BusinessException("BUILTIN_MENU_DELETE_FORBIDDEN", "内置菜单不能删除，可改为隐藏或停用");
        }
        if (repository.existsByParentId(id)) {
            throw new BusinessException("MENU_CHILDREN_EXIST", "菜单下仍有子菜单，请先移动或删除子菜单");
        }
        repository.delete(menu);
        auditService.record("DELETE", "MENU", id, menu.getTitle());
    }

    private void apply(SystemMenu menu, MenuWrite request, Long currentId) {
        String title = required(request.title());
        String routeName = required(request.routeName());
        String routePath = required(request.routePath());
        if (!ROUTE_NAME.matcher(routeName).matches()) {
            throw new BusinessException("MENU_ROUTE_NAME_INVALID", "路由名称必须以字母开头，且只能包含字母、数字和下划线");
        }
        if (!routePath.startsWith("/")) {
            throw new BusinessException("MENU_ROUTE_PATH_INVALID", "路由路径必须以 / 开头");
        }
        if (currentId == null && "/".equals(routePath)) {
            throw new BusinessException("MENU_ROUTE_PATH_INVALID", "根路径不能重复创建");
        }
        ensureUnique(routeName, routePath, currentId);

        SystemMenu parent = resolveParent(request.parentId(), currentId);
        if (request.menuType() == MenuType.PAGE) {
            String component = required(request.componentPath());
            if (!COMPONENTS.contains(component)) {
                throw new BusinessException("MENU_COMPONENT_UNREGISTERED", "组件未在管理端注册，请从组件列表选择");
            }
            menu.setComponentPath(component);
        } else {
            menu.setComponentPath(null);
        }
        String permissionCode = optional(request.permissionCode());
        if (permissionCode != null && permissionRepository.findByCode(permissionCode).isEmpty()) {
            throw new BusinessException("PERMISSION_NOT_FOUND", "权限标识不存在: " + permissionCode);
        }

        menu.setParent(parent);
        menu.setMenuType(request.menuType());
        menu.setTitle(title);
        menu.setRouteName(routeName);
        menu.setRoutePath(routePath);
        menu.setRedirectPath(optional(request.redirectPath()));
        menu.setPermissionCode(permissionCode);
        menu.setIcon(optional(request.icon()));
        menu.setSortNo(request.sortNo());
        menu.setVisible(request.visible());
        menu.setEnabled(request.enabled());
        menu.setKeepAlive(request.keepAlive());
    }

    private SystemMenu resolveParent(Long parentId, Long currentId) {
        Long normalized = normalizeParentId(parentId);
        if (normalized == null) return null;
        if (normalized.equals(currentId)) {
            throw new BusinessException("MENU_PARENT_INVALID", "上级菜单不能选择当前菜单");
        }
        SystemMenu parent = find(normalized);
        if (parent.getMenuType() != MenuType.DIRECTORY) {
            throw new BusinessException("MENU_PARENT_INVALID", "上级菜单必须是目录");
        }
        SystemMenu cursor = parent;
        while (cursor != null) {
            if (cursor.getId().equals(currentId)) {
                throw new BusinessException("MENU_PARENT_INVALID", "不能把菜单移动到自己的下级");
            }
            cursor = cursor.getParent();
        }
        return parent;
    }

    private void ensureUnique(String routeName, String routePath, Long currentId) {
        boolean nameExists = currentId == null
                ? repository.existsByRouteNameIgnoreCase(routeName)
                : repository.existsByRouteNameIgnoreCaseAndIdNot(routeName, currentId);
        if (nameExists) throw new BusinessException("MENU_ROUTE_NAME_EXISTS", "路由名称已存在");
        boolean pathExists = currentId == null
                ? repository.existsByRoutePath(routePath)
                : repository.existsByRoutePathAndIdNot(routePath, currentId);
        if (pathExists) throw new BusinessException("MENU_ROUTE_PATH_EXISTS", "路由路径已存在");
    }

    private void immutable(String field, String before, String after) {
        if (!java.util.Objects.equals(optional(before), optional(after))) {
            throw new BusinessException("BUILTIN_MENU_ROUTE_IMMUTABLE", "内置菜单的" + field + "不能修改");
        }
    }

    private Long normalizeParentId(Long parentId) {
        return parentId == null || parentId <= 0 ? null : parentId;
    }

    private SystemMenu find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("MENU_NOT_FOUND", "菜单不存在", HttpStatus.NOT_FOUND));
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("MENU_FIELD_REQUIRED", "菜单必填信息不完整");
        return value.trim();
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public interface MenuWrite {
        Long parentId();
        MenuType menuType();
        String title();
        String routeName();
        String routePath();
        String componentPath();
        String redirectPath();
        String permissionCode();
        String icon();
        int sortNo();
        boolean visible();
        boolean enabled();
        boolean keepAlive();
    }

    public record MenuView(Long id, Long parentId, MenuType menuType, String title, String routeName,
                           String routePath, String componentPath, String redirectPath, String permissionCode,
                           String icon, int sortNo, boolean visible, boolean enabled, boolean keepAlive,
                           boolean builtin) {
        static MenuView from(SystemMenu menu) {
            return new MenuView(menu.getId(), menu.getParent() == null ? null : menu.getParent().getId(),
                    menu.getMenuType(), menu.getTitle(), menu.getRouteName(), menu.getRoutePath(),
                    menu.getComponentPath(), menu.getRedirectPath(), menu.getPermissionCode(), menu.getIcon(),
                    menu.getSortNo(), menu.isVisible(), menu.isEnabled(), menu.isKeepAlive(), menu.isBuiltin());
        }
    }

    public record NavigationView(Long id, String path, String name, String component, String redirect,
                                 NavigationMeta meta, List<NavigationView> children) {
    }

    public record NavigationMeta(String title, String icon, int rank, boolean showLink, boolean keepAlive,
                                 String permission) {
    }

    private static final class NavigationNode {
        private final SystemMenu menu;
        private final List<NavigationNode> children = new ArrayList<>();

        private NavigationNode(SystemMenu menu) {
            this.menu = menu;
        }

        private NavigationView view(Comparator<NavigationNode> comparator) {
            children.sort(comparator);
            List<NavigationView> resolvedChildren = children.stream()
                    .map(child -> child.view(comparator))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (menu.getMenuType() == MenuType.DIRECTORY && resolvedChildren.isEmpty()) return null;
            List<NavigationView> childViews = resolvedChildren.isEmpty() ? null : resolvedChildren;
            NavigationMeta meta = new NavigationMeta(menu.getTitle(), menu.getIcon(), menu.getSortNo(),
                    menu.isVisible(), menu.isKeepAlive(), menu.getPermissionCode());
            return new NavigationView(menu.getId(), menu.getRoutePath(), menu.getRouteName(),
                    menu.getComponentPath(), menu.getRedirectPath(), meta, childViews);
        }
    }
}
