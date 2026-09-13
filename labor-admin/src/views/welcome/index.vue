<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { getDashboard, type DashboardData } from "@/api/labor/dashboard";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";

defineOptions({ name: "Welcome" });

const router = useRouter();
const workspaceStore = useWorkspaceStoreHook();
const currentWorkspace = computed(() => workspaceStore.current);
const loading = ref(false);
const data = ref<DashboardData>({
  projects: 0,
  companies: 0,
  teams: 0,
  persons: 0,
  waitingReview: 0,
  waitingPublish: 0,
  unmatchedAttendance: 0,
  conflictMatches: 0,
  pendingPush: 0,
  failedPush: 0,
  pausedPush: 0,
  recentBatches: []
});
const metrics = computed(() => [
  ["参建企业", data.value.companies, "家"],
  ["施工队", data.value.teams, "支"],
  ["实名人员", data.value.persons, "人"],
  ["待推送任务", data.value.pendingPush, "条"]
]);

async function load() {
  loading.value = true;
  try {
    data.value = (await getDashboard()).data;
  } catch (error: any) {
    message(error?.response?.data?.message || "工作台数据加载失败", {
      type: "error"
    });
  } finally {
    loading.value = false;
  }
}

function statusLabel(status: string) {
  return (
    {
      UPLOADED: "待提交",
      WAITING_REVIEW: "待复核",
      APPROVED: "待发布",
      REJECTED: "已驳回",
      PUBLISHED: "已发布"
    }[status] || status
  );
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="labor-page labor-dashboard">
    <header class="labor-page__header">
      <div>
        <h1>{{ currentWorkspace?.projectName || "项目工作台" }}</h1>
        <p class="labor-page__desc">
          {{ currentWorkspace?.proCode }} · 从基础信息到考勤推送的当前状态。
        </p>
      </div>
      <el-button type="primary" @click="router.push('/imports/center')">
        新建导入批次
      </el-button>
    </header>

    <section class="metric-line" aria-label="基础信息统计">
      <div v-for="item in metrics" :key="item[0]" class="metric-line__item">
        <span>{{ item[0] }}</span>
        <strong>{{ item[1] }}</strong>
        <small>{{ item[2] }}</small>
      </div>
    </section>

    <section class="dashboard-work">
      <div class="dashboard-work__main">
        <div class="section-heading">
          <div>
            <h2>最近导入</h2>
            <p>最近五个批次及其校验状态。</p>
          </div>
          <el-button text type="primary" @click="router.push('/imports/center')"
            >查看全部</el-button
          >
        </div>
        <div class="labor-table-wrap">
          <el-table :data="data.recentBatches" empty-text="暂无导入批次">
            <el-table-column
              prop="fileName"
              label="文件"
              min-width="240"
              show-overflow-tooltip
            />
            <el-table-column prop="totalCount" label="数据量" width="100" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <span class="labor-status">{{ statusLabel(row.status) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="错误" width="90">
              <template #default="{ row }">
                <span :class="{ 'text-danger': row.errorCount > 0 }">{{
                  row.errorCount
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="190">
              <template #default="{ row }">
                {{ formatDateTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <aside class="dashboard-queue">
        <div class="section-heading">
          <div>
            <h2>待处理</h2>
            <p>需要人工动作的任务。</p>
          </div>
        </div>
        <button class="queue-row" @click="router.push('/imports/center')">
          <span><i class="queue-dot queue-dot--review" />待复核批次</span>
          <strong>{{ data.waitingReview }}</strong>
        </button>
        <button class="queue-row" @click="router.push('/imports/center')">
          <span><i class="queue-dot queue-dot--publish" />待发布批次</span>
          <strong>{{ data.waitingPublish }}</strong>
        </button>
        <button class="queue-row" @click="router.push('/sync/matches')">
          <span><i class="queue-dot queue-dot--match" />人员匹配待处理</span>
          <strong>{{ data.unmatchedAttendance + data.conflictMatches }}</strong>
        </button>
        <button class="queue-row" @click="router.push('/sync/tasks')">
          <span><i class="queue-dot queue-dot--push" />推送任务待处理</span>
          <strong>{{
            data.pendingPush + data.failedPush + data.pausedPush
          }}</strong>
        </button>
        <div class="workflow-line">
          <span class="is-active">基础信息</span><i />
          <span class="is-active">海康采集</span><i />
          <span class="is-active">人员匹配</span><i />
          <span class="is-active">信息推送</span>
        </div>
        <p class="workflow-note">
          四段链路均按当前项目独立执行，接口配置、采集游标和推送计划互不影响。
        </p>
      </aside>
    </section>
  </div>
</template>

<style scoped lang="scss">
.metric-line {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin: 30px 0 36px;
  border-top: 1px solid var(--labor-line);
  border-bottom: 1px solid var(--labor-line);
}

.metric-line__item {
  padding: 25px 26px;
  border-right: 1px solid var(--labor-line);
}

.metric-line__item:last-child {
  border-right: 0;
}

.metric-line__item span {
  display: block;
  margin-bottom: 13px;
  font-size: 13px;
  color: var(--labor-muted);
}

.metric-line__item strong {
  font-size: 34px;
  font-weight: 620;
  letter-spacing: -0.04em;
}

.metric-line__item small {
  margin-left: 7px;
  color: var(--labor-muted);
}

.dashboard-work {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 42px;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-heading h2 {
  margin: 0 0 5px;
  font-size: 17px;
}

.section-heading p {
  margin: 0;
  font-size: 13px;
  color: var(--labor-muted);
}

.dashboard-queue {
  padding-left: 34px;
  border-left: 1px solid var(--labor-line);
}

.queue-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 17px 0;
  color: inherit;
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--labor-line);
}

.queue-row span {
  display: flex;
  gap: 10px;
  align-items: center;
}

.queue-row strong {
  font-size: 22px;
  color: var(--labor-accent);
}

.queue-dot {
  width: 8px;
  height: 8px;
  background: #d5a849;
  border-radius: 50%;
}

.queue-dot--publish {
  background: #3b8187;
}

.queue-dot--match {
  background: #a66f19;
}

.queue-dot--push {
  background: #b44747;
}

.workflow-line {
  display: flex;
  align-items: center;
  margin-top: 34px;
  font-size: 11px;
  color: #99a6a9;
}

.workflow-line span {
  white-space: nowrap;
}

.workflow-line .is-active {
  font-weight: 700;
  color: var(--labor-accent);
}

.workflow-line i {
  width: 16px;
  height: 1px;
  margin: 0 5px;
  background: var(--labor-line);
}

.workflow-note {
  margin: 15px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--labor-muted);
}

.text-danger {
  color: #c34f4f;
}

@media (width <= 1050px) {
  .dashboard-work {
    grid-template-columns: 1fr;
  }

  .dashboard-queue {
    padding: 0;
    border: 0;
  }
}

@media (width <= 760px) {
  .metric-line {
    grid-template-columns: repeat(2, 1fr);
  }

  .metric-line__item:nth-child(2) {
    border-right: 0;
  }

  .metric-line__item:nth-child(-n + 2) {
    border-bottom: 1px solid var(--labor-line);
  }
}
</style>
