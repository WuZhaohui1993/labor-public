const Layout = () => import("@/layout/index.vue");

export default {
  path: "/basic-data",
  name: "BasicData",
  component: Layout,
  redirect: "/basic-data/companies",
  meta: {
    icon: "ri/database-2-line",
    title: "基础信息",
    rank: 3,
    roles: [
      "SYSTEM_ADMIN",
      "DATA_ADMIN",
      "DATA_REVIEWER",
      "SYNC_OPERATOR",
      "AUDITOR"
    ]
  },
  children: [
    {
      path: "/basic-data/projects",
      name: "ProjectManagement",
      component: () => import("@/views/basic-data/projects.vue"),
      meta: {
        icon: "ri/file-list-3-line",
        title: "项目管理",
        roles: ["SYSTEM_ADMIN"]
      }
    },
    {
      path: "/basic-data/companies",
      name: "CompanyManagement",
      component: () => import("@/views/basic-data/companies.vue"),
      meta: { icon: "ri/links-fill", title: "参建企业管理" }
    },
    {
      path: "/basic-data/teams",
      name: "TeamManagement",
      component: () => import("@/views/basic-data/teams.vue"),
      meta: { icon: "ri/user-settings-line", title: "施工队管理" }
    },
    {
      path: "/basic-data/persons",
      name: "PersonManagement",
      component: () => import("@/views/basic-data/persons.vue"),
      meta: { icon: "ri/admin-line", title: "人员管理" }
    }
  ]
} satisfies RouteConfigsTable;
