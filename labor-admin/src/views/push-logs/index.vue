<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import {
  getIntegrationCallLogs,
  type IntegrationCallLog
} from "@/api/labor/push";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "IntegrationCallLogs" });

const loading = ref(false);
const rows = ref<IntegrationCallLog[]>([]);
const total = ref(0);
const query = reactive({
  page: 0,
  size: 20,
  integrationType: "",
  operationType: "",
  success: "" as boolean | ""
});

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  load();
}
const detailDialog = ref(false);
const selected = ref<IntegrationCallLog>();

const integrationLabels: Record<string, string> = {
  HIKVISION: "海康平台",
  LABOR_PLATFORM: "劳务实名制平台"
};
const operationLabels: Record<string, string> = {
  QUERY_ATTENDANCE_EVENTS: "查询考勤事件",
  PROJECT: "推送项目",
  COMPANY: "推送参建企业",
  TEAM: "推送施工队",
  PERSON: "推送人员",
  ATTENDANCE: "推送考勤"
};

async function load(reset = false) {
  if (reset) query.page = 0;
  loading.value = true;
  try {
    const result = (await getIntegrationCallLogs(query)).data;
    rows.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    message(error?.response?.data?.message || "接口调用日志加载失败", {
      type: "error"
    });
  } finally {
    loading.value = false;
  }
}

function showDetail(item: IntegrationCallLog) {
  selected.value = item;
  detailDialog.value = true;
}

function pretty(value?: string) {
  if (!value) return "无";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

onMounted(() => load());
</script>

<template>
  <div v-loading="loading" class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>接口调用日志</h1>
        <p class="labor-page__desc">
          记录海康采集和劳务平台推送结果，有权限人员可查看本批实际请求 JSON
          和返回摘要。
        </p>
      </div>
    </header>

    <div class="labor-toolbar">
      <el-select
        v-model="query.integrationType"
        clearable
        placeholder="接口平台"
        class="filter-item"
        @change="load(true)"
      >
        <el-option
          v-for="(label, value) in integrationLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-select
        v-model="query.operationType"
        clearable
        placeholder="调用类型"
        class="filter-operation"
        @change="load(true)"
      >
        <el-option
          v-for="(label, value) in operationLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-select
        v-model="query.success"
        clearable
        placeholder="调用结果"
        class="filter-item"
        @change="load(true)"
      >
        <el-option label="成功" :value="true" />
        <el-option label="失败" :value="false" />
      </el-select>
      <el-button @click="load(true)">查询</el-button>
    </div>

    <div class="labor-table-wrap">
      <el-table :data="rows" empty-text="暂无接口调用日志">
        <el-table-column label="接口平台" width="160">
          <template #default="{ row }">{{
            integrationLabels[row.integrationType] || row.integrationType
          }}</template>
        </el-table-column>
        <el-table-column label="调用类型" width="150">
          <template #default="{ row }">{{
            operationLabels[row.operationType] || row.operationType
          }}</template>
        </el-table-column>
        <el-table-column
          prop="requestPath"
          label="接口路径"
          min-width="260"
          show-overflow-tooltip
        />
        <el-table-column prop="taskId" label="任务编号" width="100">
          <template #default="{ row }">{{ row.taskId || "—" }}</template>
        </el-table-column>
        <el-table-column label="本批条数" width="100">
          <template #default="{ row }">{{ row.batchSize || 1 }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <span
              class="labor-status"
              :class="row.success ? 'is-success' : 'is-danger'"
            >
              {{ row.success ? "成功" : "失败" }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="httpStatus" label="状态码" width="100">
          <template #default="{ row }">{{ row.httpStatus || "—" }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ row.durationMs }} 毫秒</template>
        </el-table-column>
        <el-table-column
          prop="errorMessage"
          label="错误信息"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ row.errorMessage || "—" }}</template>
        </el-table-column>
        <el-table-column label="调用时间" width="190">
          <template #default="{ row }">{{
            formatDateTime(row.createdAt)
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)"
              >详情</el-button
            >
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
      v-model="detailDialog"
      title="接口调用详情"
      width="760px"
      top="5vh"
    >
      <div v-if="selected" class="log-detail">
        <dl>
          <div>
            <dt>接口平台</dt>
            <dd>{{ integrationLabels[selected.integrationType] }}</dd>
          </div>
          <div>
            <dt>接口路径</dt>
            <dd>{{ selected.requestPath }}</dd>
          </div>
          <div>
            <dt>调用结果</dt>
            <dd>{{ selected.success ? "成功" : "失败" }}</dd>
          </div>
          <div>
            <dt>调用时间</dt>
            <dd>{{ formatDateTime(selected.createdAt) }}</dd>
          </div>
        </dl>
        <section>
          <h3>实际请求数据（JSON）</h3>
          <pre>{{ pretty(selected.requestSummaryJson) }}</pre>
        </section>
        <section>
          <h3>返回摘要</h3>
          <pre>{{ pretty(selected.responseSummaryJson) }}</pre>
        </section>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.filter-item {
  width: 160px;
}

.filter-operation {
  width: 190px;
}

.log-detail dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 0 0 24px;
  border-top: 1px solid var(--labor-line);
}

.log-detail {
  max-height: 76vh;
  padding-right: 4px;
  overflow: auto;
}

.log-detail dl div {
  display: grid;
  grid-template-columns: 100px 1fr;
  padding: 12px 0;
  border-bottom: 1px solid var(--labor-line);
}

.log-detail dt {
  color: var(--labor-muted);
}

.log-detail dd {
  margin: 0;
  word-break: break-all;
}

.log-detail h3 {
  margin: 18px 0 8px;
  font-size: 14px;
}

.log-detail pre {
  max-height: 230px;
  padding: 14px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.55;
  background: #f4f7f7;
  border: 1px solid var(--labor-line);
  border-radius: 8px;
}
</style>
