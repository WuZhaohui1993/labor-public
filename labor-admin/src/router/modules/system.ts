const Layout = () => import("@/layout/index.vue");

export default {
  path: "/system",
  name: "System",
  component: Layout,
  redirect: "/system/users",
  meta: {
    icon: "ri/settings-3-line",
    title: "系统管理",
    rank: 6,
    roles: ["SYSTEM_ADMIN", "AUDITOR"]
  },
  children: [
    {
      path: "/system/users",
      name: "SystemUsers",
      component: () => import("@/views/system/users.vue"),
      meta: {
        icon: "ri/user-settings-line",
        title: "用户与角色",
        roles: ["SYSTEM_ADMIN"]
      }
    },
    {
      path: "/system/audit",
      name: "AuditLogs",
      component: () => import("@/views/system/audit.vue"),
      meta: {
        icon: "ri/file-list-3-line",
        title: "操作审计",
        roles: ["SYSTEM_ADMIN", "AUDITOR"]
      }
    },
    {
      path: "/system/menus",
      name: "SystemMenus",
      component: () => import("@/views/system/menus.vue"),
      meta: {
        icon: "ri/menu-2-line",
        title: "菜单管理",
        roles: ["SYSTEM_ADMIN"]
      }
    }
  ]
} satisfies RouteConfigsTable;
