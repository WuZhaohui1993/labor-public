const Layout = () => import("@/layout/index.vue");

export default {
  path: "/sync",
  name: "SyncManagement",
  component: Layout,
  redirect: "/sync/hik",
  meta: {
    icon: "ri/exchange-box-line",
    title: "同步管理",
    rank: 4,
    roles: ["SYSTEM_ADMIN", "SYNC_OPERATOR"]
  },
  children: [
    {
      path: "/sync/hik",
      name: "HikCollection",
      component: () => import("@/views/hik/index.vue"),
      meta: { icon: "ri/camera-lens-line", title: "海康考勤采集" }
    },
    {
      path: "/sync/attendance-statistics",
      name: "AttendanceStatistics",
      component: () => import("@/views/attendance-statistics/index.vue"),
      meta: { icon: "ri/calendar-check-line", title: "考勤统计" }
    },
    {
      path: "/sync/matches",
      name: "PersonMatching",
      component: () => import("@/views/matching/index.vue"),
      meta: { icon: "ri/user-search-line", title: "人员匹配" }
    },
    {
      path: "/sync/tasks",
      name: "PushTasks",
      component: () => import("@/views/push/index.vue"),
      meta: { icon: "ri/send-plane-line", title: "信息推送任务" }
    },
    {
      path: "/sync/logs",
      name: "IntegrationCallLogs",
      component: () => import("@/views/push-logs/index.vue"),
      meta: { icon: "ri/file-search-line", title: "接口调用日志" }
    }
  ]
} satisfies RouteConfigsTable;
