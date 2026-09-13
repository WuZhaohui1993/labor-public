<script setup lang="ts">
import dayjs from "dayjs";
import { ElMessageBox } from "element-plus";
import { onMounted, reactive, ref } from "vue";
import {
  completeAndPushAttendanceDay,
  getAttendanceCompletionPreview,
  getAttendanceDailyDetails,
  getAttendanceDailySummaries,
  type AttendanceCompletionPreview,
  type AttendanceDailySummary,
  type PersonAttendanceDetail
} from "@/api/labor/attendanceStatistics";
import {
  getWorkspaceSyncSetting,
  saveWorkspaceSyncSetting,
  type WorkspaceSyncSetting
} from "@/api/labor/workspaceSettings";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "AttendanceStatistics" });

const loading = ref(false);
const detailLoading = ref(false);
const summaries = ref<AttendanceDailySummary[]>([]);
const dateRange = ref<[Date, Date]>([
  dayjs().subtract(6, "day").startOf("day").toDate(),
  dayjs().startOf("day").toDate()
]);
const detailVisible = ref(false);
const selectedDate = ref("");
const selectedSummary = ref<AttendanceDailySummary>();
const people = ref<PersonAttendanceDetail[]>([]);
const detailTotal = ref(0);
const detailQuery = reactive({
  page: 0,
  size: 20,
  attendanceStatus: "",
  search: ""
});
const completionDialog = ref(false);
const completionLoading = ref(false);
const completionSaving = ref(false);
const dayCompletionDialog = ref(false);
const dayCompletionLoading = ref(false);
const dayCompletionExecuting = ref(false);
const dayCompletionDate = ref("");
const dayCompletionRate = ref(95);
const dayCompletionPreview = ref<AttendanceCompletionPreview>();
const completionSetting = reactive<WorkspaceSyncSetting>({
  id: 0,
  hikCollectionEnabled: true,
  pushEnabled: true,
  syncStarted: false,
  pushTime: "00:00",
  zoneId: "Asia/Shanghai",
  attendanceCompletionEnabled: false,
  attendanceCompletenessRate: 95
});

const sourceLabels: Record<string, string> = {
  HIKVISION: "海康采集",
  AUTO_COMPLETED: "自动补全",
  MIXED: "海康 + 补全",
  NONE: "无考勤"
};

const pushLabels: Record<string, string> = {
  NOT_CREATED: "未生成",
  SUCCESS: "已推送",
  ATTENTION: "需处理",
  RUNNING: "推送中",
  PENDING: "待推送",
  IGNORED: "已忽略",
  PARTIAL: "部分完成"
};

function apiDate(value: Date) {
  return dayjs(value).format("YYYY-MM-DD");
}

async function loadSummaries() {
  if (!dateRange.value?.length) {
    message("请选择统计日期范围", { type: "warning" });
    return;
  }
  loading.value = true;
  try {
    const [start, end] = dateRange.value;
    summaries.value = (
      await getAttendanceDailySummaries({
        startDate: apiDate(start),
        endDate: apiDate(end)
      })
    ).data;
  } catch (error: any) {
    fail(error, "考勤统计加载失败");
  } finally {
    loading.value = false;
  }
}

async function openDetails(row: AttendanceDailySummary) {
  selectedDate.value = row.date;
  selectedSummary.value = row;
  detailQuery.page = 0;
  detailQuery.attendanceStatus = "";
  detailQuery.search = "";
  detailVisible.value = true;
  await loadDetails();
}

async function loadDetails(reset = false) {
  if (!selectedDate.value) return;
  if (reset) detailQuery.page = 0;
  detailLoading.value = true;
  try {
    const result = (
      await getAttendanceDailyDetails({
        date: selectedDate.value,
        attendanceStatus: detailQuery.attendanceStatus,
        search: detailQuery.search,
        page: detailQuery.page,
        size: detailQuery.size
      })
    ).data;
    selectedSummary.value = result.summary;
    people.value = result.people.items;
    detailTotal.value = result.people.total;
  } catch (error: any) {
    fail(error, "考勤明细加载失败");
  } finally {
    detailLoading.value = false;
  }
}

async function openCompletionSettings() {
  if (!hasPerms("integration:view")) {
    message("当前账号没有考勤补全设置查看权限", { type: "warning" });
    return;
  }
  completionLoading.value = true;
  completionDialog.value = true;
  try {
    Object.assign(completionSetting, (await getWorkspaceSyncSetting()).data);
  } catch (error: any) {
    completionDialog.value = false;
    fail(error, "考勤补全设置加载失败");
  } finally {
    completionLoading.value = false;
  }
}

async function saveCompletionSettings() {
  completionSaving.value = true;
  try {
    Object.assign(
      completionSetting,
      (await saveWorkspaceSyncSetting(completionSetting)).data
    );
    message("考勤补全设置已保存，将从下一个半日推送窗口生效", {
      type: "success"
    });
    completionDialog.value = false;
  } catch (error: any) {
    fail(error, "考勤补全设置保存失败");
  } finally {
    completionSaving.value = false;
  }
}

async function openDayCompletion(row: AttendanceDailySummary) {
  if (!hasPerms(["hik:operate", "push:operate"])) {
    message("当前账号没有补全并推送考勤的权限", { type: "warning" });
    return;
  }
  dayCompletionDate.value = row.date;
  dayCompletionRate.value = completionSetting.attendanceCompletenessRate || 95;
  dayCompletionPreview.value = undefined;
  dayCompletionDialog.value = true;
  await loadDayCompletionPreview(false);
}

async function loadDayCompletionPreview(useSelectedRate = true) {
  if (!dayCompletionDate.value) return;
  dayCompletionLoading.value = true;
  try {
    const result = (
      await getAttendanceCompletionPreview({
        date: dayCompletionDate.value,
        targetRate: useSelectedRate ? dayCompletionRate.value : undefined
      })
    ).data;
    dayCompletionPreview.value = result;
    dayCompletionRate.value = result.targetRate;
  } catch (error: any) {
    fail(error, "考勤补全预览失败");
  } finally {
    dayCompletionLoading.value = false;
  }
}

async function executeDayCompletion() {
  if (!dayCompletionPreview.value?.executable) return;
  if (dayCompletionPreview.value.targetRate !== dayCompletionRate.value) {
    message("目标完整率已调整，请先重新计算预计补全人数", {
      type: "warning"
    });
    return;
  }
  const earlyWindows = dayCompletionPreview.value.windows
    .filter(window => window.beforePushCutoff)
    .map(window => window.label);
  const earlyNotice = earlyWindows.length
    ? `\n\n温馨提醒：${earlyWindows.join("、")} 尚未到 11:40/23:40 推送截止时间，后续海康真实记录可能继续到达。现在提前补全后，系统仍会优先协调真实记录，避免重复外发。`
    : "";
  try {
    await ElMessageBox.confirm(
      `系统将重新补查 ${dayCompletionDate.value} 的海康记录，按 ${dayCompletionRate.value.toFixed(
        2
      )}% 补全可执行时段并立即推送。执行可能需要数分钟，期间请勿关闭页面或重复提交。操作会产生正式考勤数据，是否继续？${earlyNotice}`,
      "确认补全并推送",
      {
        confirmButtonText: "确认执行",
        cancelButtonText: "取消",
        type: "warning"
      }
    );
  } catch {
    return;
  }
  dayCompletionExecuting.value = true;
  try {
    const result = (
      await completeAndPushAttendanceDay({
        date: dayCompletionDate.value,
        targetRate: dayCompletionRate.value
      })
    ).data;
    const remaining = result.windows.reduce(
      (total, window) => total + window.remainingTaskCount,
      0
    );
    message(
      remaining > 0
        ? `补全已生成：海康新增 ${result.collection.inserted} 条，自动补全 ${result.newlyGeneratedCount} 条，仍有 ${remaining} 条推送任务待处理`
        : `补全并推送完成：海康新增 ${result.collection.inserted} 条，自动补全 ${result.newlyGeneratedCount} 条`,
      { type: remaining > 0 ? "warning" : "success" }
    );
    dayCompletionDialog.value = false;
    await loadSummaries();
    if (detailVisible.value && selectedDate.value === result.date) {
      await loadDetails();
    }
  } catch (error: any) {
    fail(error, "补全并推送失败");
    await loadDayCompletionPreview();
  } finally {
    dayCompletionExecuting.value = false;
  }
}

function sourceType(source: string) {
  if (source === "HIKVISION") return "success";
  if (source === "AUTO_COMPLETED") return "warning";
  if (source === "MIXED") return "primary";
  return "info";
}

function rateType(rate: number) {
  if (rate >= 95) return "success";
  if (rate >= 80) return "warning";
  return "danger";
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

onMounted(loadSummaries);
</script>

<template>
  <div v-loading="loading" class="labor-page attendance-page">
    <header class="labor-page__header">
      <div>
        <h1>考勤统计</h1>
        <p class="labor-page__desc">
          以已成功推送且当前有效的人员为基准，按天查看海康采集、自动补全和缺勤明细。
        </p>
      </div>
      <el-button type="primary" @click="openCompletionSettings">
        考勤补全设置
      </el-button>
    </header>

    <div class="labor-toolbar">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        unlink-panels
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :clearable="false"
      />
      <el-button type="primary" @click="loadSummaries">查询</el-button>
      <span class="toolbar-note">单次最多查询 90 天</span>
    </div>

    <div class="labor-table-wrap">
      <el-table :data="summaries" empty-text="所选日期暂无考勤统计">
        <el-table-column prop="date" label="日期" width="130" fixed />
        <el-table-column prop="expectedCount" label="应考勤人数" width="120" />
        <el-table-column label="海康采集人数" width="130">
          <template #default="{ row }">
            <strong>{{ row.capturedCount }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="自动补全人数" width="130">
          <template #default="{ row }">
            <span :class="{ 'completion-count': row.autoCompletedCount > 0 }">
              {{ row.autoCompletedCount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="attendedCount"
          label="最终考勤人数"
          width="130"
        />
        <el-table-column prop="missingCount" label="缺勤人数" width="110" />
        <el-table-column label="完整率" width="120">
          <template #default="{ row }">
            <el-tag :type="rateType(row.completenessRate)" effect="plain">
              {{ row.completenessRate.toFixed(2) }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="eventCount" label="考勤记录数" width="120" />
        <el-table-column label="基准外人员" min-width="110">
          <template #default="{ row }">{{ row.outsideBaselineCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="205" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetails(row)">
              每日明细
            </el-button>
            <el-button
              link
              type="warning"
              :disabled="!hasPerms(['hik:operate', 'push:operate'])"
              @click="openDayCompletion(row)"
            >
              补全并推送
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer
      v-model="detailVisible"
      :title="`${selectedDate} 考勤明细`"
      size="88%"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="detail-content">
        <div v-if="selectedSummary" class="detail-summary">
          <div>
            <span>应考勤</span
            ><strong>{{ selectedSummary.expectedCount }}</strong>
          </div>
          <div>
            <span>海康采集</span
            ><strong>{{ selectedSummary.capturedCount }}</strong>
          </div>
          <div>
            <span>自动补全</span
            ><strong>{{ selectedSummary.autoCompletedCount }}</strong>
          </div>
          <div>
            <span>仍缺勤</span
            ><strong>{{ selectedSummary.missingCount }}</strong>
          </div>
          <div>
            <span>完整率</span>
            <strong>{{ selectedSummary.completenessRate.toFixed(2) }}%</strong>
          </div>
        </div>

        <div class="labor-toolbar detail-toolbar">
          <el-select
            v-model="detailQuery.attendanceStatus"
            clearable
            placeholder="考勤状态"
            class="status-filter"
            @change="loadDetails(true)"
          >
            <el-option label="已考勤" value="ATTENDED" />
            <el-option label="缺勤" value="MISSING" />
          </el-select>
          <el-input
            v-model="detailQuery.search"
            clearable
            placeholder="姓名、企业、施工队或工种"
            class="detail-search"
            @keyup.enter="loadDetails(true)"
            @clear="loadDetails(true)"
          />
          <el-button @click="loadDetails(true)">查询</el-button>
        </div>

        <div class="labor-table-wrap">
          <el-table :data="people" empty-text="没有符合条件的人员">
            <el-table-column prop="personName" label="姓名" width="120" fixed />
            <el-table-column
              prop="companyName"
              label="参建企业"
              min-width="180"
              show-overflow-tooltip
            />
            <el-table-column
              prop="teamName"
              label="施工队"
              min-width="160"
              show-overflow-tooltip
            />
            <el-table-column prop="workType" label="工种" width="130">
              <template #default="{ row }">{{ row.workType || "—" }}</template>
            </el-table-column>
            <el-table-column label="考勤状态" width="105">
              <template #default="{ row }">
                <span
                  class="labor-status"
                  :class="
                    row.attendanceStatus === 'ATTENDED'
                      ? 'is-success'
                      : 'is-danger'
                  "
                >
                  {{ row.attendanceStatus === "ATTENDED" ? "已考勤" : "缺勤" }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="数据来源" width="125">
              <template #default="{ row }">
                <el-tag :type="sourceType(row.attendanceSource)" effect="plain">
                  {{
                    sourceLabels[row.attendanceSource] || row.attendanceSource
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="首次考勤" width="180">
              <template #default="{ row }">{{
                formatDateTime(row.firstEventTime)
              }}</template>
            </el-table-column>
            <el-table-column label="末次考勤" width="180">
              <template #default="{ row }">{{
                formatDateTime(row.lastEventTime)
              }}</template>
            </el-table-column>
            <el-table-column label="记录数" width="90">
              <template #default="{ row }">
                {{ row.eventCount }}
                <small
                  v-if="row.autoCompletedEventCount"
                  class="source-breakdown"
                >
                  含补全 {{ row.autoCompletedEventCount }}
                </small>
              </template>
            </el-table-column>
            <el-table-column label="进/出" width="90">
              <template #default="{ row }"
                >{{ row.entryCount }}/{{ row.exitCount }}</template
              >
            </el-table-column>
            <el-table-column label="推送状态" width="105">
              <template #default="{ row }">{{
                pushLabels[row.pushStatus] || row.pushStatus
              }}</template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination
          class="labor-pagination"
          :current-page="detailQuery.page + 1"
          :page-size="detailQuery.size"
          :page-sizes="PAGE_SIZE_OPTIONS"
          :total="detailTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="
            value => {
              detailQuery.size = value;
              loadDetails(true);
            }
          "
          @current-change="
            value => {
              detailQuery.page = value - 1;
              loadDetails();
            }
          "
        />
      </div>
    </el-drawer>

    <el-dialog
      v-model="completionDialog"
      title="考勤补全设置"
      width="560px"
      destroy-on-close
    >
      <div v-loading="completionLoading" class="completion-setting">
        <el-alert
          title="系统每小时40分采集并推送，其中每日11:40和23:40在推送前自动补全；也可选择日期预览后补全可执行时段并立即推送。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-form label-position="top">
          <el-form-item label="固定推送窗口">
            <div class="completion-window">
              <span>11:40 推送当日 00:00—12:00</span>
              <span>23:40 推送次日更新所需的前一日 12:00—24:00</span>
            </div>
          </el-form-item>
          <el-form-item label="开启自动补全">
            <el-switch
              v-model="completionSetting.attendanceCompletionEnabled"
              :disabled="!hasPerms('integration:edit')"
              active-text="已开启"
              inactive-text="已关闭"
            />
          </el-form-item>
          <el-form-item label="半日完整率基准">
            <div class="completion-rate-field">
              <el-input-number
                v-model="completionSetting.attendanceCompletenessRate"
                :disabled="
                  !completionSetting.attendanceCompletionEnabled ||
                  !hasPerms('integration:edit')
                "
                :min="0"
                :max="100"
                :precision="2"
                :step="1"
                controls-position="right"
              />
              <span>%</span>
            </div>
          </el-form-item>
        </el-form>
        <p class="completion-note">
          人员基准为已成功推送、当前有效且所属企业开启人员推送的人员；补全记录会明确标记为“自动补全”。
        </p>
      </div>
      <template #footer>
        <el-button @click="completionDialog = false">取消</el-button>
        <el-button
          v-if="hasPerms('integration:edit')"
          type="primary"
          :loading="completionSaving"
          @click="saveCompletionSettings"
        >
          保存设置
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="dayCompletionDialog"
      :title="`${dayCompletionDate} 补全并推送`"
      width="760px"
      destroy-on-close
    >
      <div v-loading="dayCompletionLoading" class="day-completion">
        <el-alert
          :title="
            dayCompletionPreview?.message || '正在计算该日期可执行的考勤窗口'
          "
          :type="dayCompletionPreview?.executable ? 'warning' : 'info'"
          :closable="false"
          show-icon
        />
        <div class="day-completion__rate">
          <div>
            <span>目标完整率</span>
            <small
              >每个半日独立计算；自动补全在基准值上下随机浮动1个百分点，海康真实考勤优先</small
            >
          </div>
          <el-input-number
            v-model="dayCompletionRate"
            :min="0"
            :max="100"
            :precision="2"
            :step="1"
            controls-position="right"
          />
          <span>%</span>
          <el-button
            :loading="dayCompletionLoading"
            @click="loadDayCompletionPreview()"
          >
            重新计算
          </el-button>
        </div>
        <el-table
          :data="dayCompletionPreview?.windows || []"
          empty-text="暂无可预览窗口"
        >
          <el-table-column prop="label" label="时段" min-width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.closed ? 'success' : 'info'" effect="plain">
                {{
                  row.beforePushCutoff
                    ? "可提前补全"
                    : row.closed
                      ? "可补全"
                      : "未到时段"
                }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="expectedCount" label="应考勤" width="90" />
          <el-table-column prop="capturedCount" label="海康" width="80" />
          <el-table-column
            prop="autoCompletedCount"
            label="已补全"
            width="90"
          />
          <el-table-column prop="targetCount" label="目标" width="80" />
          <el-table-column label="预计新增" width="95">
            <template #default="{ row }">
              <strong v-if="row.closed" class="completion-count">
                {{ row.toGenerateCount }}
              </strong>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="completion-note">
          当天进入对应半日后即可补全，不必等到 12:00 或 24:00；在 11:40/23:40
          之前执行时，系统会给出提前补全提醒。执行前会重新查询所选时段的海康数据并完成实名匹配；若真实考勤在补全推送前到达，系统停止对应补全任务；若补全已成功推送，后到的真实记录仍会保留，但暂停自动外发，避免接收端出现重复考勤。
        </p>
      </div>
      <template #footer>
        <el-button @click="dayCompletionDialog = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="
            !dayCompletionPreview?.executable ||
            dayCompletionPreview.targetRate !== dayCompletionRate
          "
          :loading="dayCompletionExecuting"
          @click="executeDayCompletion"
        >
          补全并立即推送
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.toolbar-note {
  margin-left: auto;
  font-size: 12px;
  color: var(--labor-muted);
}

.completion-count {
  font-weight: 650;
  color: #a66f19;
}

.detail-content {
  min-height: 420px;
}

.detail-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 1px;
  overflow: hidden;
  background: var(--labor-line);
  border: 1px solid var(--labor-line);
  border-radius: 10px;
}

.detail-summary > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 18px;
  background: #fff;
}

.detail-summary span {
  font-size: 12px;
  color: var(--labor-muted);
}

.detail-summary strong {
  font-size: 22px;
  font-weight: 650;
}

.status-filter {
  width: 150px;
}

.detail-search {
  width: 300px;
}

.source-breakdown {
  display: block;
  color: #a66f19;
}

.completion-setting {
  min-height: 320px;
}

.completion-setting .el-form {
  margin-top: 22px;
}

.completion-window {
  display: grid;
  gap: 7px;
  width: 100%;
  padding: 13px 15px;
  font-size: 13px;
  color: var(--labor-ink);
  background: var(--labor-surface);
  border-left: 2px solid var(--labor-accent);
}

.completion-rate-field {
  display: flex;
  gap: 10px;
  align-items: center;
}

.completion-rate-field .el-input-number {
  width: 180px;
}

.completion-note {
  padding-top: 14px;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--labor-muted);
  border-top: 1px solid var(--labor-line);
}

.day-completion {
  display: grid;
  gap: 20px;
  min-height: 330px;
}

.day-completion__rate {
  display: flex;
  gap: 10px;
  align-items: center;
}

.day-completion__rate > div {
  display: grid;
  gap: 3px;
  margin-right: auto;
}

.day-completion__rate small {
  color: var(--labor-muted);
}

.day-completion__rate .el-input-number {
  width: 150px;
}

@media (width <= 900px) {
  .detail-summary {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
