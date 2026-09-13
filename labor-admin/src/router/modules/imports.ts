const Layout = () => import("@/layout/index.vue");

export default {
  path: "/imports",
  name: "Imports",
  component: Layout,
  redirect: "/imports/center",
  meta: {
    icon: "ri/file-excel-2-line",
    title: "导入与复核",
    rank: 2,
    roles: ["SYSTEM_ADMIN", "DATA_ADMIN", "DATA_REVIEWER", "AUDITOR"]
  },
  children: [
    {
      path: "/imports/center",
      name: "ImportCenter",
      component: () => import("@/views/imports/index.vue"),
      meta: { icon: "ri/upload-cloud-2-line", title: "导入中心" }
    }
  ]
} satisfies RouteConfigsTable;
