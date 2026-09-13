<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import PersonSelect from "@/views/basic-data/components/PersonSelect.vue";
import {
  batchConfirm,
  batchRematch,
  confirmMatch,
  getMatches,
  getMatchHistory,
  releaseMatch,
  rematch,
  type MatchHistory,
  type MatchItem
} from "@/api/labor/matching";
import type { MatchStatus } from "@/api/labor/hik";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "PersonMatching" });

const loading = ref(false);
const rows = ref<MatchItem[]>([]);
const selectedRows = ref<MatchItem[]>([]);
const batchWorking = ref(false);
const total = ref(0);
const query = reactive({
  page: 0,
  size: 20,
  status: "" as MatchStatus | "",
  search: ""
});

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  load();
}
const confirmDialog = ref(false);
const confirmForm = reactive({
  mode: "single" as "single" | "batch",
  eventId: 0,
  eventIds: [] as number[],
  proCode: "",
  personId: undefined as number | undefined,
  reason: ""
});
const historyDrawer = ref(false);
const history = ref<MatchHistory[]>([]);
const confirmDialogTitle = computed(() =>
  confirmForm.mode === "batch" ? "批量确认实名人员" : "人工确认人员"
);

const statusLabels: Record<string, string> = {
  UNMATCHED: "待匹配",
  MATCHED: "已匹配",
  CONFLICT: "需处理",
  RELEASED: "已解除"
};
const methodLabels: Record<string, string> = {
  CERTIFICATE: "证件号码",
  HIK_PERSON_ID: "海康人员编号",
  MANUAL: "人工确认"
};
const directionLabels: Record<string, string> = {
  JINCHANG_JINCHU: "进场",
  TUICHANG_JINCHU: "出场",
  UNKNOWN: "待确认"
};

async function load(reset = false) {
  if (reset) query.page = 0;
  loading.value = true;
  try {
    const result = (await getMatches(query)).data;
    rows.value = result.items;
    total.value = result.total;
    selectedRows.value = [];
  } catch (error: any) {
    fail(error, "匹配记录加载失败");
  } finally {
    loading.value = false;
  }
}

function openConfirm(item: MatchItem) {
  Object.assign(confirmForm, {
    mode: "single",
    eventId: item.id,
    eventIds: [item.id],
    proCode: item.proCode,
    personId: item.matchedPersonId,
    reason: "人工核对确认"
  });
  confirmDialog.value = true;
}

function openBatchConfirm() {
  if (!selectedRows.value.length) {
    message("请先勾选需要确认的记录", { type: "warning" });
    return;
  }
  const hikPersonIds = new Set(
    selectedRows.value.map(item => item.hikPersonId?.trim()).filter(Boolean)
  );
  if (
    hikPersonIds.size !== 1 ||
    selectedRows.value.some(item => !item.hikPersonId?.trim())
  ) {
    message("批量确认只能选择同一海康人员编号的记录", {
      type: "warning"
    });
    return;
  }
  Object.assign(confirmForm, {
    mode: "batch",
    eventId: 0,
    eventIds: selectedRows.value.map(item => item.id),
    proCode: selectedRows.value[0].proCode,
    personId: selectedRows.value[0].matchedPersonId,
    reason: `批量人工核对确认，共${selectedRows.value.length}条记录`
  });
  confirmDialog.value = true;
}

async function submitConfirm() {
  if (!confirmForm.personId || !confirmForm.reason.trim()) {
    message("请选择实名人员并填写确认原因", { type: "warning" });
    return;
  }
  batchWorking.value = confirmForm.mode === "batch";
  try {
    if (confirmForm.mode === "batch") {
      const result = (
        await batchConfirm(
          confirmForm.eventIds,
          confirmForm.personId,
          confirmForm.reason
        )
      ).data;
      message(`批量确认完成：已匹配 ${result.matched} 条`, {
        type: "success"
      });
    } else {
      await confirmMatch(confirmForm.eventId, {
        personId: confirmForm.personId,
        reason: confirmForm.reason
      });
      message("人员匹配已确认，考勤推送任务已生成", {
        type: "success"
      });
    }
    confirmDialog.value = false;
    await load();
  } catch (error: any) {
    fail(error, "人工匹配失败");
  } finally {
    batchWorking.value = false;
  }
}

function handleSelectionChange(selection: MatchItem[]) {
  selectedRows.value = selection;
}

function canSelectForBatch(item: MatchItem) {
  return item.matchStatus !== "MATCHED";
}

async function runBatchRematch() {
  if (!selectedRows.value.length) {
    message("请先勾选需要重新匹配的记录", { type: "warning" });
    return;
  }
  const { value } = await ElMessageBox.prompt(
    `将对选中的 ${selectedRows.value.length} 条记录重新执行证件号码和海康人员编号匹配`,
    "批量自动重匹配",
    {
      confirmButtonText: "开始匹配",
      cancelButtonText: "取消",
      inputValue: `批量发起自动重匹配，共${selectedRows.value.length}条记录`,
      inputValidator: value => !!value?.trim() || "必须填写原因"
    }
  );
  batchWorking.value = true;
  try {
    const result = (
      await batchRematch(
        selectedRows.value.map(item => item.id),
        value
      )
    ).data;
    message(
      `批量匹配完成：匹配成功 ${result.matched} 条，待匹配 ${result.unmatched} 条，需处理 ${result.conflicts} 条`,
      { type: result.conflicts || result.unmatched ? "warning" : "success" }
    );
    await load();
  } catch (error: any) {
    fail(error, "批量自动匹配失败");
  } finally {
    batchWorking.value = false;
  }
}

async function release(item: MatchItem) {
  const { value } = await ElMessageBox.prompt(
    "请填写解除匹配的原因",
    "解除匹配",
    {
      confirmButtonText: "确认解除",
      cancelButtonText: "取消",
      inputPlaceholder: "例如：海康人员绑定错误",
      inputValidator: value => !!value?.trim() || "必须填写原因"
    }
  );
  try {
    await releaseMatch(item.id, value);
    message("匹配关系已解除，未完成的考勤任务已暂停", { type: "success" });
    await load();
  } catch (error: any) {
    fail(error, "解除匹配失败");
  }
}

async function runRematch(item: MatchItem) {
  const { value } = await ElMessageBox.prompt(
    "系统将重新按证件号码、海康人员编号依次匹配",
    "重新匹配",
    {
      confirmButtonText: "开始匹配",
      cancelButtonText: "取消",
      inputValue: "人工发起重新匹配",
      inputValidator: value => !!value?.trim() || "必须填写原因"
    }
  );
  try {
    await rematch(item.id, value);
    message("重新匹配已完成", { type: "success" });
    await load();
  } catch (error: any) {
    fail(error, "重新匹配失败");
  }
}

async function openHistory(item: MatchItem) {
  try {
    history.value = (await getMatchHistory(item.id)).data;
    historyDrawer.value = true;
  } catch (error: any) {
    fail(error, "匹配历史加载失败");
  }
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

onMounted(() => load());
</script>

<template>
  <div v-loading="loading" class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>人员匹配</h1>
        <p class="labor-page__desc">
          同项目内优先按证件号码匹配，其次使用海康人员编号，姓名仅用于人工核对。
        </p>
      </div>
    </header>

    <div class="labor-toolbar">
      <el-select
        v-model="query.status"
        clearable
        placeholder="匹配状态"
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
        placeholder="姓名、海康人员编号或事件编号"
        class="filter-search"
        @keyup.enter="load(true)"
      />
      <el-button @click="load(true)">查询</el-button>
      <div v-if="hasPerms('match:confirm')" class="batch-actions">
        <span>已选择 {{ selectedRows.length }} 条</span>
        <el-button
          :disabled="!selectedRows.length"
          :loading="batchWorking"
          @click="runBatchRematch"
        >
          批量自动重匹配
        </el-button>
        <el-button
          type="primary"
          plain
          :disabled="!selectedRows.length"
          :loading="batchWorking"
          @click="openBatchConfirm"
        >
          批量确认人员
        </el-button>
      </div>
    </div>

    <div class="labor-table-wrap">
      <el-table
        :data="rows"
        empty-text="暂无匹配记录"
        @selection-change="handleSelectionChange"
      >
        <el-table-column
          v-if="hasPerms('match:confirm')"
          type="selection"
          width="48"
          :selectable="canSelectForBatch"
        />
        <el-table-column prop="hikPersonName" label="海康姓名" width="120">
          <template #default="{ row }">{{ row.hikPersonName || "—" }}</template>
        </el-table-column>
        <el-table-column
          prop="hikPersonId"
          label="海康人员编号"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column prop="idcardNumber" label="证件号码" width="170" />
        <el-table-column label="考勤时间" width="190">
          <template #default="{ row }">{{
            formatDateTime(row.eventTime)
          }}</template>
        </el-table-column>
        <el-table-column label="方向" width="90">
          <template #default="{ row }">{{
            directionLabels[row.direction] || "待确认"
          }}</template>
        </el-table-column>
        <el-table-column label="匹配方式" width="130">
          <template #default="{ row }">{{
            methodLabels[row.matchMethod] || "—"
          }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span
              class="labor-status"
              :class="`status-${row.matchStatus.toLowerCase()}`"
            >
              {{ statusLabels[row.matchStatus] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="matchReason"
          label="处理说明"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="hasPerms('match:confirm') && row.matchStatus !== 'MATCHED'"
              link
              type="primary"
              @click="openConfirm(row)"
            >
              人工确认
            </el-button>
            <el-button
              v-if="hasPerms('match:confirm') && row.matchStatus === 'MATCHED'"
              link
              type="danger"
              @click="release(row)"
            >
              解除
            </el-button>
            <el-button
              v-if="hasPerms('match:confirm')"
              link
              @click="runRematch(row)"
              >重新匹配</el-button
            >
            <el-button link @click="openHistory(row)">历史</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-pagination
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

    <el-dialog
      v-model="confirmDialog"
      :title="confirmDialogTitle"
      width="540px"
    >
      <el-form label-position="top">
        <el-alert
          v-if="confirmForm.mode === 'batch'"
          :title="`将同一海康人员编号的 ${confirmForm.eventIds.length} 条记录统一绑定到所选实名人员`"
          type="info"
          :closable="false"
          show-icon
        />
        <el-form-item label="实名人员" required>
          <PersonSelect
            v-model="confirmForm.personId"
            :pro-code="confirmForm.proCode"
          />
        </el-form-item>
        <el-form-item label="确认原因" required>
          <el-input
            v-model="confirmForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="batchWorking"
          @click="submitConfirm"
        >
          {{ confirmForm.mode === "batch" ? "批量确认" : "确认匹配" }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" title="匹配处理历史" size="560px">
      <el-timeline v-if="history.length">
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="formatDateTime(item.createdAt)"
          placement="top"
        >
          <strong>{{
            statusLabels[item.matchStatus] || item.matchStatus
          }}</strong>
          <p>{{ item.reason || "无处理说明" }}</p>
          <small
            >操作人：{{ item.operatedBy }} · 匹配人员：{{
              item.matchedPersonId || "无"
            }}</small
          >
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无处理历史" />
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.filter-project {
  width: 280px;
}

.filter-status {
  width: 150px;
}

.filter-search {
  width: 300px;
}

.batch-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-left: auto;
}

.batch-actions span {
  font-size: 12px;
  color: var(--labor-muted);
}

.el-alert {
  margin-bottom: 18px;
}

.el-timeline p {
  margin: 7px 0;
  color: var(--labor-muted);
}

.el-timeline small {
  color: var(--labor-muted);
}
</style>
