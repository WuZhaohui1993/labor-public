<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";
import {
  executePushTask,
  executePushTasks,
  executeProjectPush,
  createReplay,
  getPushTask,
  getPushTasks,
  getPushTaskSummary,
  getReplayJob,
  getReplayJobs,
  ignorePushTask,
  previewReplay,
  pausePushTask,
  retryReplayFailed,
  retryPushTask,
  startProjectPush,
  type BatchExecutionResult,
  type PushTask,
  type PushTaskDetail,
  type PushTaskSummary,
  type PushTaskStatus,
  type PushTaskType,
  type ReplayJob,
  type ReplayPreview
} from "@/api/labor/push";
import { getCompanies, type CompanyItem } from "@/api/labor/masterData";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "PushTasks" });

const loading = ref(false);
const summaryLoading = ref(false);
const detailLoading = ref(false);
const workspaceStore = useWorkspaceStoreHook();
const currentWorkspace = computed(() => workspaceStore.current);
const rows = ref<PushTask[]>([]);
const total = ref(0);
const taskSummary = ref<PushTaskSummary>();
const viewMode = ref<"summary" | "details">("summary");
const query = reactive({
  page: 0,
  size: 20,
  taskType: "" as PushTaskType | "",
  status: "" as PushTaskStatus | "",
  search: ""
});

function changePageSize(size: number) {
  query.size = size;
  load(true);
}
const detailDrawer = ref(false);
const selected = ref<PushTask>();
const selectedDetail = ref<PushTaskDetail>();
const selectedRows = ref<PushTask[]>([]);
const rebuildDialog = ref(false);
const starting = ref(false);
const replayLoading = ref(false);
const replayPreviewLoading = ref(false);
const replayHistoryLoading = ref(false);
const replayDialog = ref(false);
const replayHistoryDialog = ref(false);
const replayProgressDialog = ref(false);
const replayPreviewResult = ref<ReplayPreview>();
const activeReplay = ref<ReplayJob>();
const replayJobs = ref<ReplayJob[]>([]);
const companyOptions = ref<CompanyItem[]>([]);
const replayForm = reactive({
  projectCode: "",
  taskType: "" as PushTaskType | "",
  companyCode: "",
  dateRange: [] as string[],
  refreshSources: true,
  reason: ""
});

const typeLabels: Record<string, string> = {
  PROJECT: "项目",
  COMPANY: "参建企业",
  TEAM: "施工队",
  PERSON: "人员",
  ATTENDANCE: "考勤"
};
const statusLabels: Record<string, string> = {
  WAITING_CONFIRM: "待确认",
  PENDING: "待执行",
  RUNNING: "执行中",
  SUCCESS: "成功",
  FAILED: "自动补偿中",
  PAUSED: "已暂停",
  IGNORED: "已忽略"
};
const replayStatusLabels: Record<string, string> = {
  CREATED: "准备中",
  RUNNING: "执行中",
  SUCCESS: "已完成",
  PARTIAL_SUCCESS: "部分成功",
  FAILED: "失败"
};

async function load(reset = false) {
  if (reset) query.page = 0;
  loading.value = true;
  try {
    const result = (await getPushTasks(query)).data;
    rows.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    fail(error, "推送任务加载失败");
  } finally {
    loading.value = false;
  }
}

async function loadSummary() {
  summaryLoading.value = true;
  try {
    taskSummary.value = (await getPushTaskSummary()).data;
  } catch (error: any) {
    fail(error, "任务批次概览加载失败");
  } finally {
    summaryLoading.value = false;
  }
}

async function reloadData(reset = false) {
  await Promise.all([load(reset), loadSummary()]);
}

function showTypeDetails(taskType?: PushTaskType) {
  query.taskType = taskType || "";
  query.status = "";
  viewMode.value = "details";
  void load(true);
}

watch(viewMode, mode => {
  if (mode === "summary") selectedRows.value = [];
});

async function execute(item: PushTask) {
  await ElMessageBox.confirm(
    "任务将立即按依赖关系检查并执行，确认继续吗？",
    "立即执行",
    {
      confirmButtonText: "立即执行",
      cancelButtonText: "取消",
      type: "warning"
    }
  );
  try {
    const result = (await executePushTask(item.id)).data;
    showExecutionResult(result);
    await reloadData();
  } catch (error: any) {
    fail(error, "任务执行失败");
  }
}

async function executeSelected() {
  const executable = selectedRows.value.filter(item =>
    ["PENDING", "FAILED", "PAUSED"].includes(item.status)
  );
  if (!executable.length || executable.length !== selectedRows.value.length) {
    message("请选择可立即执行的任务", { type: "warning" });
    return;
  }
  const reason = await askReason(
    "批量立即执行",
    `将执行选中的 ${selectedRows.value.length} 条任务，请填写原因`
  );
  if (!reason) return;
  try {
    const result = (
      await executePushTasks(
        executable.map(item => item.id),
        reason
      )
    ).data;
    showExecutionResult(result);
    await reloadData();
  } catch (error: any) {
    fail(error, "批量执行失败");
  }
}

async function replaySelected() {
  const successful = selectedRows.value.filter(
    item => item.status === "SUCCESS"
  );
  if (!successful.length || successful.length !== selectedRows.value.length) {
    message("请选择已成功的任务进行补推", { type: "warning" });
    return;
  }
  const reason = await askReason(
    "批量重新推送",
    `将使用 ${successful.length} 条任务的原始报文快照重新发送，请填写原因`
  );
  if (!reason) return;
  replayLoading.value = true;
  try {
    const preview = (
      await previewReplay({
        projectWide: false,
        projectCode: currentWorkspace.value?.proCode,
        taskIds: successful.map(item => item.id)
      })
    ).data;
    await confirmReplayPreview(preview);
    const job = (
      await createReplay(
        {
          projectWide: false,
          projectCode: currentWorkspace.value?.proCode,
          taskIds: successful.map(item => item.id)
        },
        reason
      )
    ).data;
    openReplayProgress(job);
  } catch (error: any) {
    fail(error, "批量重新推送失败");
  } finally {
    replayLoading.value = false;
  }
}

async function executeProject() {
  if (!currentWorkspace.value?.proCode) {
    message("请先选择项目工作区", { type: "warning" });
    return;
  }
  await ElMessageBox.confirm(
    "系统将立即按项目、企业、施工队、人员、考勤的依赖顺序批量推送，确认继续吗？",
    "立即执行本项目",
    {
      confirmButtonText: "立即执行",
      cancelButtonText: "取消",
      type: "warning"
    }
  );
  loading.value = true;
  try {
    const result = (await executeProjectPush(currentWorkspace.value.proCode))
      .data;
    const type =
      result.failed || result.paused || result.pending ? "warning" : "success";
    message(
      `项目执行完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条，暂停 ${result.paused} 条，等待 ${result.pending} 条`,
      { type }
    );
    await reloadData(true);
  } catch (error: any) {
    fail(error, "项目立即执行失败");
  } finally {
    loading.value = false;
  }
}

async function replayTask(item: PushTask) {
  const reason = await askReason(
    "重新推送成功任务",
    "将使用该任务已保存的原始报文重新发送，请填写原因"
  );
  if (!reason) return;
  replayLoading.value = true;
  try {
    const preview = (
      await previewReplay({
        projectWide: false,
        projectCode: currentWorkspace.value?.proCode,
        taskIds: [item.id]
      })
    ).data;
    await confirmReplayPreview(preview);
    const job = (
      await createReplay(
        {
          projectWide: false,
          projectCode: currentWorkspace.value?.proCode,
          taskIds: [item.id]
        },
        reason
      )
    ).data;
    openReplayProgress(job);
  } catch (error: any) {
    fail(error, "重新推送失败");
  } finally {
    replayLoading.value = false;
  }
}

async function replayProject() {
  if (!currentWorkspace.value?.proCode) {
    message("请先选择项目工作区", { type: "warning" });
    return;
  }
  replayForm.projectCode = currentWorkspace.value.proCode;
  replayForm.taskType = "";
  replayForm.companyCode = "";
  replayForm.dateRange = [];
  replayForm.refreshSources = true;
  replayForm.reason = "";
  replayPreviewResult.value = undefined;
  replayDialog.value = true;
  await loadReplayCompanies();
}

async function pollReplay(jobId: number) {
  const maxAttempts = 300;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    try {
      const job = (await getReplayJob(jobId)).data;
      activeReplay.value = job;
      if (["SUCCESS", "PARTIAL_SUCCESS", "FAILED"].includes(job.status)) {
        const type = job.status === "SUCCESS" ? "success" : "warning";
        message(
          `补推作业完成：成功 ${job.successCount} 条，失败 ${job.failedCount} 条，待处理 ${job.pendingCount} 条`,
          { type }
        );
        await reloadData(true);
        return;
      }
    } catch {
      return;
    }
  }
  message("补推作业仍在后台准备或执行，请到补推记录查看最新状态", {
    type: "warning"
  });
}

async function loadReplayCompanies() {
  try {
    companyOptions.value = (
      await getCompanies({ page: 0, size: 100, search: "" })
    ).data.items;
  } catch (error: any) {
    fail(error, "参建企业加载失败");
  }
}

function replaySelection() {
  return {
    projectWide: true,
    projectCode: replayForm.projectCode || currentWorkspace.value?.proCode,
    taskType: replayForm.taskType || undefined,
    companyCode: replayForm.companyCode || undefined,
    startDate: replayForm.dateRange[0] || undefined,
    endDate: replayForm.dateRange[1] || undefined,
    refreshSources: replayForm.refreshSources
  };
}

function clearReplayPreview() {
  replayPreviewResult.value = undefined;
}

function onReplayTaskTypeChange() {
  if (replayForm.taskType === "PROJECT") replayForm.companyCode = "";
  clearReplayPreview();
}

async function previewFilteredReplay() {
  if (!replayForm.projectCode) {
    message("请先选择项目工作区", { type: "warning" });
    return;
  }
  replayPreviewLoading.value = true;
  try {
    replayPreviewResult.value = (await previewReplay(replaySelection())).data;
  } catch (error: any) {
    replayPreviewResult.value = undefined;
    fail(error, "补推预览失败");
  } finally {
    replayPreviewLoading.value = false;
  }
}

async function submitFilteredReplay() {
  if (!replayPreviewResult.value) {
    await previewFilteredReplay();
    if (!replayPreviewResult.value) return;
  }
  if (!replayForm.reason.trim()) {
    message("请填写补推原因", { type: "warning" });
    return;
  }
  replayLoading.value = true;
  try {
    const job = (
      await createReplay(replaySelection(), replayForm.reason.trim())
    ).data;
    replayDialog.value = false;
    openReplayProgress(job);
  } catch (error: any) {
    await handleReplayCreateError(error, "创建补推作业失败");
  } finally {
    replayLoading.value = false;
  }
}

async function confirmReplayPreview(preview: ReplayPreview) {
  const filters = [preview.projectName];
  if (preview.taskType) filters.push(typeLabels[preview.taskType]);
  if (preview.companyName) filters.push(preview.companyName);
  if (preview.startDate)
    filters.push(`${preview.startDate} 至 ${preview.endDate}`);
  const warning = preview.warning ? `\n\n${preview.warning}` : "";
  await ElMessageBox.confirm(
    `${filters.join(" / ")}\n预计补推 ${preview.total} 条，约 ${preview.batchCount} 个批次。${warning}\n\n是否继续？`,
    "确认补推",
    {
      confirmButtonText: "开始补推",
      cancelButtonText: "取消",
      type: preview.warning ? "warning" : "info"
    }
  );
}

function openReplayProgress(job: ReplayJob) {
  activeReplay.value = job;
  replayProgressDialog.value = true;
  message(`补推作业 ${job.jobNo} 已创建，正在后台准备并执行`, {
    type: "success"
  });
  void pollReplay(job.id);
}

async function openReplayHistory() {
  replayHistoryDialog.value = true;
  await loadReplayJobs();
}

async function loadReplayJobs() {
  replayHistoryLoading.value = true;
  try {
    replayJobs.value = (await getReplayJobs()).data;
  } catch (error: any) {
    fail(error, "补推记录加载失败");
  } finally {
    replayHistoryLoading.value = false;
  }
}

function showReplayProgress(job: ReplayJob) {
  activeReplay.value = job;
  replayHistoryDialog.value = false;
  replayProgressDialog.value = true;
  if (["CREATED", "RUNNING"].includes(job.status)) void pollReplay(job.id);
}

async function retryReplay(job: ReplayJob) {
  const reason = await askReason("重试补推失败项", "请填写本次重试原因");
  if (!reason) return;
  replayLoading.value = true;
  try {
    const next = (await retryReplayFailed(job.id, reason)).data;
    openReplayProgress(next);
  } catch (error: any) {
    fail(error, "补推失败项重试失败");
  } finally {
    replayLoading.value = false;
  }
}

function showExecutionResult(result: BatchExecutionResult) {
  if (result.deferred) {
    message(
      `已执行 ${result.claimed} 条，${result.deferred} 条等待上级任务完成`,
      { type: "warning" }
    );
  } else if (result.failed) {
    message(`执行完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条`, {
      type: "warning"
    });
  } else {
    message(
      `执行成功 ${result.succeeded} 条，共调用接口 ${result.httpCalls} 次`,
      { type: "success" }
    );
  }
}

function selectable(row: PushTask) {
  return (
    row.status === "SUCCESS" ||
    (row.dependencyReady &&
      ["PENDING", "FAILED", "PAUSED"].includes(row.status) &&
      !disabledRulePending(row))
  );
}

async function retry(item: PushTask) {
  try {
    await retryPushTask(item.id);
    message("任务已恢复到待执行状态", { type: "success" });
    await reloadData();
  } catch (error: any) {
    fail(error, "任务恢复失败");
  }
}

async function pause(item: PushTask) {
  const reason = await askReason("暂停任务", "请填写暂停原因");
  if (!reason) return;
  try {
    await pausePushTask(item.id, reason);
    message("任务已暂停", { type: "success" });
    await reloadData();
  } catch (error: any) {
    fail(error, "暂停任务失败");
  }
}

async function ignore(item: PushTask) {
  const reason = await askReason(
    "忽略任务",
    "忽略后下级任务可继续执行，请填写原因"
  );
  if (!reason) return;
  try {
    await ignorePushTask(item.id, reason);
    message("任务已忽略并保留操作记录", { type: "success" });
    await reloadData();
  } catch (error: any) {
    fail(error, "忽略任务失败");
  }
}

async function askReason(title: string, prompt: string) {
  try {
    const { value } = await ElMessageBox.prompt(prompt, title, {
      confirmButtonText: "确认",
      cancelButtonText: "取消",
      inputValidator: value => !!value?.trim() || "必须填写原因"
    });
    return value.trim();
  } catch {
    return "";
  }
}

async function submitRebuild() {
  if (!currentWorkspace.value?.proCode) {
    message("请先选择项目工作区", { type: "warning" });
    return;
  }
  starting.value = true;
  try {
    const result = (await startProjectPush(currentWorkspace.value.proCode))
      .data;
    rebuildDialog.value = false;
    message(
      `项目同步已启动，已激活 ${result.activatedTasks} 条现有任务；后续数据任务将自动生成并进入每日同步`,
      {
        type: "success"
      }
    );
    await reloadData(true);
  } catch (error: any) {
    fail(error, "启动项目同步失败");
  } finally {
    starting.value = false;
  }
}

async function showDetail(item: PushTask) {
  selected.value = item;
  selectedDetail.value = undefined;
  detailDrawer.value = true;
  detailLoading.value = true;
  try {
    selectedDetail.value = (await getPushTask(item.id)).data;
  } catch (error: any) {
    fail(error, "推送任务详情加载失败");
  } finally {
    detailLoading.value = false;
  }
}

function detailTask() {
  return selectedDetail.value || selected.value;
}

function prettyPayload(value?: string) {
  if (!value) return "暂无可展示的推送数据";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function handleBatchCommand(command: string) {
  if (command === "execute") void executeSelected();
  if (command === "replay") void replaySelected();
}

function handlePageCommand(command: string) {
  if (command === "replay") void replayProject();
  if (command === "history") void openReplayHistory();
  if (command === "start") rebuildDialog.value = true;
}

function hasRowMoreActions(row: PushTask) {
  return (
    row.status === "SUCCESS" ||
    (["FAILED", "PAUSED"].includes(row.status) && !disabledRulePending(row)) ||
    ["PENDING", "FAILED"].includes(row.status) ||
    !["SUCCESS", "RUNNING", "IGNORED"].includes(row.status)
  );
}

function handleRowCommand(command: string, row: PushTask) {
  if (command === "retry") void retry(row);
  if (command === "replay") void replayTask(row);
  if (command === "pause") void pause(row);
  if (command === "ignore") void ignore(row);
}

function disabledRulePending(row: PushTask) {
  return row.manualReason?.includes("未定义删除或停用规则");
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

function isRequestTimeout(error: any) {
  return (
    error?.code === "ECONNABORTED" ||
    error?.code === "ETIMEDOUT" ||
    String(error?.message || "")
      .toLowerCase()
      .includes("timeout")
  );
}

async function handleReplayCreateError(error: any, fallback: string) {
  if (!isRequestTimeout(error)) {
    fail(error, fallback);
    return;
  }
  replayDialog.value = false;
  message(
    "创建请求等待超时，后台可能已经受理；请先查看补推记录，避免重复创建",
    {
      type: "warning"
    }
  );
  await openReplayHistory();
}

function isMessageBoxDismiss(error: unknown) {
  if (typeof error === "string") return ["cancel", "close"].includes(error);
  if (!error || typeof error !== "object" || !("action" in error)) return false;
  return ["cancel", "close"].includes(String(error.action));
}

onMounted(() => reloadData());
</script>

<template>
  <div v-loading="loading" class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>信息推送任务</h1>
        <p class="labor-page__desc">
          每小时40分采集、匹配并推送；11:40先补全当日00:00—12:00，23:40先补全当日12:00—24:00。
        </p>
      </div>
      <div v-if="hasPerms('push:operate')" class="header-actions">
        <el-button type="primary" :loading="loading" @click="executeProject">
          立即执行本项目
        </el-button>
        <el-dropdown
          trigger="click"
          :disabled="selectedRows.length === 0"
          @command="handleBatchCommand"
        >
          <el-button :disabled="selectedRows.length === 0">
            批量操作<span v-if="selectedRows.length"
              >（{{ selectedRows.length }}）</span
            >⌄
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="execute"
                >批量立即执行</el-dropdown-item
              >
              <el-dropdown-item command="replay"
                >补推选中成功任务</el-dropdown-item
              >
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-dropdown trigger="click" @command="handlePageCommand">
          <el-button>更多操作⌄</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="replay">按条件补推</el-dropdown-item>
              <el-dropdown-item command="history">补推记录</el-dropdown-item>
              <el-dropdown-item divided command="start"
                >启动项目同步</el-dropdown-item
              >
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="labor-toolbar">
      <el-radio-group v-model="viewMode" size="default">
        <el-radio-button value="summary">批次概览</el-radio-button>
        <el-radio-button value="details">任务明细</el-radio-button>
      </el-radio-group>
      <template v-if="viewMode === 'details'">
        <el-select
          v-model="query.taskType"
          clearable
          placeholder="数据类型"
          class="filter-type"
          @change="load(true)"
        >
          <el-option
            v-for="(label, value) in typeLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-select
          v-model="query.status"
          clearable
          placeholder="任务状态"
          class="filter-status"
          @change="load(true)"
        >
          <el-option
            v-for="(label, value) in statusLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-input
          v-model="query.search"
          clearable
          placeholder="业务键或幂等键"
          class="filter-search"
          @keyup.enter="load(true)"
        />
        <el-button @click="load(true)">查询</el-button>
      </template>
    </div>

    <section
      v-if="viewMode === 'summary'"
      v-loading="summaryLoading"
      class="batch-overview"
    >
      <div class="overview-heading">
        <div>
          <strong>推送批次概览</strong>
          <span
            >任务按业务对象留痕，实际请求按类型每
            {{ taskSummary?.batchSize || 50 }} 条合并</span
          >
        </div>
        <span class="overview-total"
          >共 {{ taskSummary?.totalCount || 0 }} 条任务</span
        >
      </div>
      <el-table
        :data="taskSummary?.groups || []"
        empty-text="暂无推送任务"
        class="batch-overview__table"
      >
        <el-table-column label="数据类型" min-width="140">
          <template #default="{ row }">{{ typeLabels[row.taskType] }}</template>
        </el-table-column>
        <el-table-column prop="totalCount" label="任务数" width="100" />
        <el-table-column label="待处理" width="110">
          <template #default="{ row }">{{
            row.pendingCount + row.failedCount
          }}</template>
        </el-table-column>
        <el-table-column prop="successCount" label="已成功" width="100" />
        <el-table-column label="暂停/忽略" width="110">
          <template #default="{ row }">{{
            row.pausedCount + row.ignoredCount
          }}</template>
        </el-table-column>
        <el-table-column
          prop="estimatedBatchCount"
          label="预计待推批次"
          width="140"
        />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="showTypeDetails(row.taskType)"
              >查看明细</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <p class="overview-note">
        “任务数”包含版本与结果留痕；“预计待推批次”只按待执行和自动补偿中的任务估算，成功数据需要补推时请使用“更多操作
        → 按条件补推”。
      </p>
    </section>

    <div v-else class="labor-table-wrap">
      <el-table
        :data="rows"
        empty-text="暂无推送任务"
        @selection-change="selectedRows = $event"
      >
        <el-table-column type="selection" width="46" :selectable="selectable" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeLabels[row.taskType] }}</template>
        </el-table-column>
        <el-table-column
          prop="businessKey"
          label="业务键"
          min-width="210"
          show-overflow-tooltip
        />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">第 {{ row.dataVersionNo }} 版</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span
              class="labor-status"
              :class="`status-${row.status.toLowerCase()}`"
            >
              {{ statusLabels[row.status] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="重试" width="100">
          <template #default="{ row }"
            >{{ row.retryCount }} / {{ row.maxRetries }}</template
          >
        </el-table-column>
        <el-table-column
          prop="remoteMessage"
          label="平台返回"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{
            row.remoteMessage || row.lastErrorMessage || "—"
          }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="190">
          <template #default="{ row }">{{
            formatDateTime(row.createdAt)
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link @click="showDetail(row)">详情</el-button>
            <template v-if="hasPerms('push:operate')">
              <el-button
                v-if="
                  ['PENDING', 'FAILED', 'PAUSED'].includes(row.status) &&
                  row.dependencyReady &&
                  !disabledRulePending(row)
                "
                link
                type="primary"
                @click="execute(row)"
              >
                立即执行
              </el-button>
              <span
                v-else-if="
                  ['PENDING', 'FAILED'].includes(row.status) &&
                  !row.dependencyReady
                "
                class="dependency-waiting"
              >
                等待上级任务
              </span>
              <el-dropdown
                v-if="hasRowMoreActions(row)"
                trigger="click"
                @command="command => handleRowCommand(command, row)"
              >
                <el-button link>更多⌄</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-if="
                        ['FAILED', 'PAUSED'].includes(row.status) &&
                        !disabledRulePending(row)
                      "
                      command="retry"
                      >恢复任务</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="row.status === 'SUCCESS'"
                      command="replay"
                      >重新推送</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="['PENDING', 'FAILED'].includes(row.status)"
                      command="pause"
                      >暂停任务</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="
                        !['SUCCESS', 'RUNNING', 'IGNORED'].includes(row.status)
                      "
                      command="ignore"
                      class="danger-menu-item"
                      >忽略任务</el-dropdown-item
                    >
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-pagination
      v-if="viewMode === 'details'"
      class="labor-pagination"
      :current-page="query.page + 1"
      :page-size="query.size"
      :page-sizes="PAGE_SIZE_OPTIONS"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @size-change="changePageSize"
      @current-change="
        value => {
          query.page = value - 1;
          load();
        }
      "
    />

    <el-drawer
      v-model="detailDrawer"
      v-loading="detailLoading"
      title="推送任务详情"
      size="680px"
    >
      <el-descriptions v-if="detailTask()" :column="1" border>
        <el-descriptions-item label="任务编号">{{
          detailTask()?.id
        }}</el-descriptions-item>
        <el-descriptions-item label="数据类型">{{
          typeLabels[detailTask()?.taskType || ""]
        }}</el-descriptions-item>
        <el-descriptions-item label="项目编码">{{
          detailTask()?.proCode
        }}</el-descriptions-item>
        <el-descriptions-item label="业务键">{{
          detailTask()?.businessKey
        }}</el-descriptions-item>
        <el-descriptions-item label="幂等键">{{
          detailTask()?.idempotencyKey
        }}</el-descriptions-item>
        <el-descriptions-item label="依赖任务">{{
          detailTask()?.dependencyKey || "无"
        }}</el-descriptions-item>
        <el-descriptions-item label="任务状态">{{
          statusLabels[detailTask()?.status || ""]
        }}</el-descriptions-item>
        <el-descriptions-item label="下次重试">{{
          formatDateTime(detailTask()?.nextRetryAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">{{
          detailTask()?.lastErrorMessage || "无"
        }}</el-descriptions-item>
        <el-descriptions-item label="人工说明">{{
          detailTask()?.manualReason || "无"
        }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{
          formatDateTime(detailTask()?.completedAt)
        }}</el-descriptions-item>
      </el-descriptions>
      <section v-if="selectedDetail" class="payload-detail">
        <div class="payload-detail__heading">
          <h3>本次要推送的数据</h3>
          <el-tag
            :type="selectedDetail.payloadSnapshot ? 'success' : 'warning'"
            size="small"
          >
            {{
              selectedDetail.payloadSnapshot
                ? "原始报文快照"
                : "根据当前数据生成"
            }}
          </el-tag>
        </div>
        <el-alert
          v-if="selectedDetail.payloadMessage"
          :title="selectedDetail.payloadMessage"
          type="warning"
          :closable="false"
          show-icon
        />
        <pre class="payload-detail__json">{{
          prettyPayload(selectedDetail.payloadJson)
        }}</pre>
      </section>
    </el-drawer>

    <el-dialog v-model="rebuildDialog" title="启动项目同步" width="520px">
      <el-form label-position="top">
        <el-form-item label="项目" required>
          <div class="workspace-field">
            <strong>{{ currentWorkspace?.projectName }}</strong>
            <small>{{ currentWorkspace?.proCode }}</small>
          </div>
        </el-form-item>
        <p class="dialog-note">
          首次授权后，系统每小时40分完成采集、匹配和逐级推送；11:40、23:40额外完成对应半日自动补全。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="rebuildDialog = false">取消</el-button>
        <el-button type="primary" :loading="starting" @click="submitRebuild"
          >确认启动</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="replayDialog"
      title="按条件重新推送"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form :model="replayForm" label-position="top">
        <el-form-item label="项目" required>
          <div class="workspace-field">
            <strong>{{ currentWorkspace?.projectName }}</strong>
            <small>{{ currentWorkspace?.proCode }}（由顶部当前项目决定）</small>
          </div>
        </el-form-item>
        <div class="replay-filter-grid">
          <el-form-item label="推送类型">
            <el-select
              v-model="replayForm.taskType"
              clearable
              placeholder="全部类型"
              @change="onReplayTaskTypeChange"
            >
              <el-option
                v-for="(label, value) in typeLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="参建企业">
            <el-select
              v-model="replayForm.companyCode"
              clearable
              filterable
              :disabled="replayForm.taskType === 'PROJECT'"
              placeholder="全部企业"
              @change="clearReplayPreview"
            >
              <el-option
                v-for="company in companyOptions"
                :key="company.collCropCode"
                :label="`${company.companyName}（${company.collCropCode}）${company.personPushEnabled ? '' : '—人员及考勤推送已关闭'}`"
                :value="company.collCropCode"
                :disabled="
                  !company.personPushEnabled &&
                  ['PERSON', 'ATTENDANCE'].includes(replayForm.taskType)
                "
              />
            </el-select>
          </el-form-item>
          <el-form-item label="业务日期范围">
            <el-date-picker
              v-model="replayForm.dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              @change="clearReplayPreview"
            />
          </el-form-item>
        </div>
        <el-form-item>
          <el-checkbox
            v-model="replayForm.refreshSources"
            @change="clearReplayPreview"
          >
            发送前按当前主数据补齐任务并自动匹配考勤
          </el-checkbox>
          <p class="form-hint">
            适用于历史没有任务或需要全量重推的场景；关闭后只使用已成功任务的原始快照。
          </p>
        </el-form-item>
        <el-form-item label="补推原因" required>
          <el-input
            v-model="replayForm.reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="请填写补推原因，例如：接收端数据核对后要求补发"
          />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="replayPreviewResult?.warning"
        :title="replayPreviewResult.warning"
        type="warning"
        :closable="false"
        show-icon
        class="replay-warning"
      />
      <div v-if="replayPreviewResult" class="replay-preview-card">
        <div class="replay-preview-head">
          <strong>预览结果</strong>
          <span>{{
            replayPreviewResult.refreshSources
              ? "创建时刷新当前数据并按批次发送"
              : "仅重放最新成功版本"
          }}</span>
        </div>
        <div class="replay-preview-stats">
          <span
            >任务 <b>{{ replayPreviewResult.total }}</b> 条</span
          >
          <span
            >请求批次 <b>{{ replayPreviewResult.batchCount }}</b> 个</span
          >
          <span v-if="replayPreviewResult.attendanceCount"
            >考勤 <b>{{ replayPreviewResult.attendanceCount }}</b> 条</span
          >
        </div>
        <div class="replay-type-counts">
          <span v-for="(count, type) in replayPreviewResult.byType" :key="type">
            {{ typeLabels[type] }} {{ count }} 条
          </span>
        </div>
      </div>
      <el-empty v-else description="填写条件后点击预览，系统不会立即发送" />

      <template #footer>
        <el-button @click="replayDialog = false">取消</el-button>
        <el-button
          :loading="replayPreviewLoading"
          @click="previewFilteredReplay"
          >预览</el-button
        >
        <el-button
          type="primary"
          :loading="replayLoading"
          :disabled="
            !replayPreviewResult ||
            (!replayPreviewResult.refreshSources &&
              replayPreviewResult.total === 0)
          "
          @click="submitFilteredReplay"
          >创建补推作业</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="replayHistoryDialog" title="补推记录" width="980px">
      <div v-loading="replayHistoryLoading" class="replay-history-wrap">
        <el-table :data="replayJobs" empty-text="暂无补推记录">
          <el-table-column prop="jobNo" label="作业编号" min-width="190" />
          <el-table-column label="范围" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.scopeDescription }}</template>
          </el-table-column>
          <el-table-column label="进度" width="180">
            <template #default="{ row }">
              <el-progress
                :percentage="row.progressPercent"
                :stroke-width="10"
              />
              <small>{{ row.completedCount }} / {{ row.totalCount }}</small>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{
              replayStatusLabels[row.status]
            }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{
              formatDateTime(row.createdAt)
            }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showReplayProgress(row)">
                查看进度
              </el-button>
              <el-button
                v-if="
                  row.failedCount > 0 &&
                  ['SUCCESS', 'RUNNING'].indexOf(row.status) < 0
                "
                link
                @click="retryReplay(row)"
                >重试失败项</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog
      v-model="replayProgressDialog"
      title="补推作业进度"
      width="620px"
      :close-on-click-modal="false"
    >
      <template v-if="activeReplay">
        <div class="replay-progress-meta">
          <strong>{{ activeReplay.jobNo }}</strong>
          <span>{{ replayStatusLabels[activeReplay.status] }}</span>
        </div>
        <div class="replay-scope">{{ activeReplay.scopeDescription }}</div>
        <el-progress
          :percentage="activeReplay.progressPercent"
          :status="
            activeReplay.status === 'SUCCESS'
              ? 'success'
              : ['FAILED', 'PARTIAL_SUCCESS'].includes(activeReplay.status)
                ? 'exception'
                : undefined
          "
        />
        <div class="replay-progress-stats">
          <span
            >成功 <b>{{ activeReplay.successCount }}</b></span
          >
          <span
            >失败 <b>{{ activeReplay.failedCount }}</b></span
          >
          <span
            >待处理 <b>{{ activeReplay.pendingCount }}</b></span
          >
          <span
            >批次 <b>{{ activeReplay.totalBatchCount }}</b></span
          >
        </div>
        <el-alert
          v-if="activeReplay.warning"
          :title="activeReplay.warning"
          type="warning"
          :closable="false"
          show-icon
          class="replay-warning"
        />
        <p v-if="activeReplay.lastError" class="replay-last-error">
          {{ activeReplay.lastError }}
        </p>
      </template>
      <template #footer>
        <el-button @click="replayProgressDialog = false">关闭</el-button>
        <el-button
          v-if="
            activeReplay &&
            activeReplay.failedCount > 0 &&
            ['SUCCESS', 'RUNNING'].indexOf(activeReplay.status) < 0
          "
          type="primary"
          :loading="replayLoading"
          @click="retryReplay(activeReplay)"
          >重试失败项</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.filter-project {
  width: 260px;
}

.filter-type,
.filter-status {
  width: 140px;
}

.filter-search {
  width: 240px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.header-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.batch-overview {
  padding: 18px 20px 14px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.overview-heading {
  display: flex;
  gap: 16px;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.overview-heading div {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.overview-heading span,
.overview-note,
.form-hint {
  font-size: 13px;
  color: var(--labor-muted);
}

.overview-total {
  white-space: nowrap;
}

.overview-note {
  margin: 12px 0 0;
  line-height: 1.6;
}

.payload-detail {
  margin-top: 22px;
}

.payload-detail__heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.payload-detail__heading h3 {
  margin: 0;
  font-size: 15px;
}

.payload-detail__json {
  max-height: 360px;
  padding: 14px;
  margin: 12px 0 0;
  overflow: auto;
  font:
    12px/1.65 ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    monospace;
  color: #24333b;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: #f6f8fa;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.form-hint {
  margin: 6px 0 0;
  line-height: 1.5;
}

.danger-menu-item {
  color: var(--el-color-danger);
}

.dependency-waiting {
  margin: 0 8px;
  font-size: 13px;
  color: var(--labor-muted);
}

.dialog-note {
  margin: 0;
  font-size: 13px;
  color: var(--labor-muted);
}

.replay-filter-grid {
  display: grid;
  grid-template-columns: 1fr 1.4fr 1.4fr;
  gap: 12px;
}

.replay-filter-grid :deep(.el-select),
.replay-filter-grid :deep(.el-date-editor) {
  width: 100%;
}

.replay-warning {
  margin-bottom: 14px;
}

.replay-preview-card {
  padding: 14px 16px;
  margin-top: 14px;
  background: #f6faf9;
  border: 1px solid #d7ebe7;
  border-radius: 8px;
}

.replay-preview-head,
.replay-progress-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.replay-preview-head span,
.replay-progress-meta span,
.replay-history-wrap small {
  color: var(--labor-muted);
}

.replay-preview-stats,
.replay-progress-stats,
.replay-type-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 12px;
  font-size: 13px;
}

.replay-preview-stats b,
.replay-progress-stats b {
  font-size: 17px;
  color: var(--el-color-primary);
}

.replay-type-counts {
  gap: 8px;
}

.replay-type-counts span {
  padding: 4px 8px;
  background: #fff;
  border-radius: 4px;
}

.replay-progress-meta {
  margin-bottom: 12px;
}

.replay-scope {
  padding: 10px 12px;
  margin-bottom: 16px;
  color: var(--labor-muted);
  background: #f7f8fa;
  border-radius: 6px;
}

.replay-last-error {
  padding: 10px 12px;
  margin-bottom: 0;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
}

@media (width <= 860px) {
  .overview-heading,
  .overview-heading div {
    flex-direction: column;
    gap: 6px;
    align-items: flex-start;
  }

  .replay-filter-grid {
    grid-template-columns: 1fr;
  }
}
</style>
