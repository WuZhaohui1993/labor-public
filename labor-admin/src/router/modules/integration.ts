const Layout = () => import("@/layout/index.vue");

export default {
  path: "/integration",
  name: "Integration",
  component: Layout,
  redirect: "/integration/config",
  meta: {
    icon: "ri/links-line",
    title: "接口配置",
    rank: 5,
    roles: ["SYSTEM_ADMIN", "SYNC_OPERATOR"]
  },
  children: [
    {
      path: "/integration/config",
      name: "IntegrationConfig",
      component: () => import("@/views/integration/index.vue"),
      meta: { icon: "ri/code-s-slash-line", title: "接口配置" }
    }
  ]
} satisfies RouteConfigsTable;
