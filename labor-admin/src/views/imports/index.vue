<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import type { UploadFile } from "element-plus";
import { ElMessageBox } from "element-plus";
import {
  approveImport,
  downloadImportErrors,
  downloadTemplate,
  getImportBatch,
  getImportBatches,
  getImportErrors,
  getImportRows,
  publishImport,
  rejectImport,
  submitImport,
  uploadImport,
  type ImportBatch,
  type ImportError,
  type ImportRow
} from "@/api/labor/imports";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";

defineOptions({ name: "ImportCenter" });

const loading = ref(false);
const route = useRoute();
const uploading = ref(false);
const drawer = ref(false);
const activeTab = ref("rows");
const batches = ref<ImportBatch[]>([]);
const total = ref(0);
const query = reactive({ page: 0, size: 20 });
const selected = ref<ImportBatch>();
const rows = ref<ImportRow[]>([]);
const rowTotal = ref(0);
const rowPage = ref(0);
const rowSize = ref(20);
const errors = ref<ImportError[]>([]);
const errorTotal = ref(0);
const errorPage = ref(0);
const errorSize = ref(50);

const detailStats = computed(() =>
  selected.value
    ? [
        ["总数", selected.value.totalCount],
        ["新增", selected.value.newCount],
        ["修改", selected.value.updatedCount],
        ["无变化", selected.value.unchangedCount],
        ["错误", selected.value.errorCount]
      ]
    : []
);

async function load() {
  loading.value = true;
  try {
    const result = (await getImportBatches(query)).data;
    batches.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    fail(error, "导入批次加载失败");
  } finally {
    loading.value = false;
  }
}

async function handleFile(file: UploadFile) {
  if (!file.raw) return;
  uploading.value = true;
  try {
    const result = await uploadImport(file.raw);
    message(
      result.data.errorCount ? "文件已解析，请处理校验错误" : "文件校验通过",
      {
        type: result.data.errorCount ? "warning" : "success"
      }
    );
    await load();
    await openDetail(result.data);
  } catch (error: any) {
    fail(error, "文件上传失败");
  } finally {
    uploading.value = false;
  }
}

async function openDetail(batch: ImportBatch) {
  selected.value = batch;
  drawer.value = true;
  activeTab.value = batch.errorCount > 0 ? "errors" : "rows";
  rowPage.value = 0;
  errorPage.value = 0;
  await Promise.all([loadRows(), loadErrors()]);
}

async function loadRows() {
  if (!selected.value) return;
  const result = (
    await getImportRows(selected.value.id, {
      page: rowPage.value,
      size: rowSize.value
    })
  ).data;
  rows.value = result.items;
  rowTotal.value = result.total;
}

async function loadErrors() {
  if (!selected.value) return;
  const result = (
    await getImportErrors(selected.value.id, {
      page: errorPage.value,
      size: errorSize.value
    })
  ).data;
  errors.value = result.items;
  errorTotal.value = result.total;
}

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  load();
}

function changeRowPageSize(size: number) {
  rowSize.value = size;
  rowPage.value = 0;
  loadRows();
}

function changeErrorPageSize(size: number) {
  errorSize.value = size;
  errorPage.value = 0;
  loadErrors();
}

async function action(kind: "submit" | "approve" | "reject" | "publish") {
  if (!selected.value) return;
  try {
    let result;
    if (kind === "submit") result = await submitImport(selected.value.id);
    if (kind === "approve")
      result = await approveImport(selected.value.id, "复核通过");
    if (kind === "publish") {
      await ElMessageBox.confirm(
        "发布后将写入正式基础信息台账，是否继续？",
        "确认发布",
        { type: "warning" }
      );
      result = await publishImport(selected.value.id);
    }
    if (kind === "reject") {
      const prompt = await ElMessageBox.prompt("请填写驳回原因", "驳回批次", {
        inputValidator: value => !!value.trim() || "驳回原因不能为空"
      });
      result = await rejectImport(selected.value.id, prompt.value);
    }
    if (result) selected.value = result.data;
    message("操作成功", { type: "success" });
    await load();
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    fail(error, "操作失败");
  }
}

async function download(kind: "template" | "errors") {
  try {
    const blob =
      kind === "template"
        ? await downloadTemplate()
        : await downloadImportErrors(selected.value!.id);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download =
      kind === "template"
        ? "劳务实名制线下数据采集模板_V1.0.xlsx"
        : `导入错误_${selected.value!.id}.xlsx`;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error: any) {
    fail(error, "文件下载失败");
  }
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

function statusLabel(status: string) {
  return (
    {
      UPLOADED: "已校验",
      WAITING_REVIEW: "待复核",
      APPROVED: "待发布",
      REJECTED: "已驳回",
      PUBLISHED: "已发布"
    }[status] || status
  );
}

function scopeLabel(scope: string) {
  return (
    {
      ALL: "综合导入",
      PROJECT: "项目",
      COMPANY: "参建企业",
      TEAM: "施工队",
      PERSON: "人员"
    }[scope] || scope
  );
}

function changeLabel(change: string) {
  return (
    { NEW: "新增", UPDATED: "修改", UNCHANGED: "无变化", ERROR: "错误" }[
      change
    ] || change
  );
}

function normalizedText(row: ImportRow) {
  return Object.entries(row.normalized)
    .filter(([, value]) => value)
    .slice(0, 5)
    .map(([key, value]) => `${key}: ${value}`)
    .join(" · ");
}

onMounted(async () => {
  await load();
  const batchId = Number(route.query.batchId);
  if (Number.isFinite(batchId) && batchId > 0) {
    try {
      await openDetail((await getImportBatch(batchId)).data);
    } catch (error: any) {
      fail(error, "导入批次加载失败");
    }
  }
});
</script>

<template>
  <div class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>文件导入与复核</h1>
        <p class="labor-page__desc">
          文件先进入暂存区，校验和差异确认完成后才会写入正式台账。
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="download('template')">下载标准模板</el-button>
        <el-upload
          v-if="hasPerms('import:upload')"
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx"
          :on-change="handleFile"
        >
          <el-button type="primary" :loading="uploading">上传并校验</el-button>
        </el-upload>
      </div>
    </header>

    <div class="labor-toolbar">
      <span class="toolbar-note">共 {{ total }} 个导入批次</span>
      <el-button text @click="load">刷新</el-button>
    </div>

    <div v-loading="loading" class="labor-table-wrap">
      <el-table
        :data="batches"
        row-class-name="clickable-row"
        @row-click="openDetail"
      >
        <el-table-column
          prop="fileName"
          label="文件名称"
          min-width="260"
          show-overflow-tooltip
        />
        <el-table-column prop="uploadedBy" label="上传人" width="120" />
        <el-table-column label="导入范围" width="120">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="数据概览" min-width="240">
          <template #default="{ row }">
            <span>{{ row.totalCount }} 条</span>
            <span class="data-delta">新增 {{ row.newCount }}</span>
            <span class="data-delta">修改 {{ row.updatedCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="错误" width="90">
          <template #default="{ row }"
            ><b :class="{ danger: row.errorCount }">{{
              row.errorCount
            }}</b></template
          >
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"
            ><span class="labor-status">{{
              statusLabel(row.status)
            }}</span></template
          >
        </el-table-column>
        <el-table-column label="上传时间" width="190">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-pagination
      class="page-footer"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-size="query.size"
      :page-sizes="PAGE_SIZE_OPTIONS"
      :current-page="query.page + 1"
      @size-change="changePageSize"
      @current-change="
        value => {
          query.page = value - 1;
          load();
        }
      "
    />

    <el-drawer
      v-model="drawer"
      size="76%"
      :with-header="false"
      class="import-drawer"
    >
      <template v-if="selected">
        <div class="drawer-head">
          <div>
            <p class="drawer-batch-no">批次编号：{{ selected.id }}</p>
            <h2>{{ selected.fileName }}</h2>
            <p>
              {{ scopeLabel(selected.scope) }} · {{ selected.uploadedBy }} ·
              {{ formatDateTime(selected.createdAt) }} ·
              {{ statusLabel(selected.status) }}
            </p>
          </div>
          <div class="drawer-actions">
            <el-button v-if="selected.errorCount" @click="download('errors')"
              >导出错误</el-button
            >
            <el-button
              v-if="
                selected.status === 'UPLOADED' &&
                !selected.errorCount &&
                hasPerms('import:submit')
              "
              type="primary"
              @click="action('submit')"
              >提交复核</el-button
            >
            <el-button
              v-if="
                selected.status === 'WAITING_REVIEW' &&
                hasPerms('import:review')
              "
              @click="action('reject')"
              >驳回</el-button
            >
            <el-button
              v-if="
                selected.status === 'WAITING_REVIEW' &&
                hasPerms('import:review')
              "
              type="primary"
              @click="action('approve')"
              >复核通过</el-button
            >
            <el-button
              v-if="
                selected.status === 'APPROVED' && hasPerms('import:publish')
              "
              type="primary"
              @click="action('publish')"
              >发布台账</el-button
            >
          </div>
        </div>
        <div class="batch-metrics">
          <div v-for="item in detailStats" :key="item[0]">
            <span>{{ item[0] }}</span
            ><strong>{{ item[1] }}</strong>
          </div>
        </div>
        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="数据预览" name="rows">
            <div class="labor-table-wrap">
              <el-table :data="rows">
                <el-table-column prop="sheet" label="工作表" width="130" />
                <el-table-column prop="row" label="行号" width="80" />
                <el-table-column
                  prop="businessKey"
                  label="业务键"
                  min-width="210"
                  show-overflow-tooltip
                />
                <el-table-column label="差异" width="100">
                  <template #default="{ row }">{{
                    changeLabel(row.changeType)
                  }}</template>
                </el-table-column>
                <el-table-column
                  label="标准化内容"
                  min-width="360"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">{{
                    normalizedText(row)
                  }}</template>
                </el-table-column>
              </el-table>
            </div>
            <el-pagination
              class="page-footer"
              layout="total, sizes, prev, pager, next"
              :total="rowTotal"
              :page-size="rowSize"
              :page-sizes="PAGE_SIZE_OPTIONS"
              :current-page="rowPage + 1"
              @size-change="changeRowPageSize"
              @current-change="
                value => {
                  rowPage = value - 1;
                  loadRows();
                }
              "
            />
          </el-tab-pane>
          <el-tab-pane
            :label="`错误明细 (${selected.errorCount})`"
            name="errors"
          >
            <div class="labor-table-wrap">
              <el-table :data="errors" empty-text="没有校验错误">
                <el-table-column prop="sheet" label="工作表" width="130" />
                <el-table-column prop="row" label="行号" width="80" />
                <el-table-column prop="field" label="字段" width="170" />
                <el-table-column
                  prop="errorCode"
                  label="错误代码"
                  width="180"
                />
                <el-table-column
                  prop="message"
                  label="错误说明"
                  min-width="260"
                />
                <el-table-column
                  prop="rawValue"
                  label="原始值"
                  min-width="200"
                  show-overflow-tooltip
                />
              </el-table>
            </div>
            <el-pagination
              class="page-footer"
              layout="total, sizes, prev, pager, next"
              :total="errorTotal"
              :page-size="errorSize"
              :page-sizes="PAGE_SIZE_OPTIONS"
              :current-page="errorPage + 1"
              @size-change="changeErrorPageSize"
              @current-change="
                value => {
                  errorPage = value - 1;
                  loadErrors();
                }
              "
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.header-actions,
.drawer-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.toolbar-note {
  font-size: 13px;
  color: var(--labor-muted);
}

.data-delta {
  margin-left: 14px;
  font-size: 12px;
  color: var(--labor-muted);
}

.danger {
  color: #bf4b4b;
}

.page-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}

.drawer-head {
  display: flex;
  gap: 24px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 30px 6px 24px;
  border-bottom: 1px solid var(--labor-line);
}

.drawer-head h2 {
  max-width: 680px;
  margin: 0;
  font-size: 24px;
}

.drawer-batch-no {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--labor-accent);
}

.drawer-head p:last-child {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--labor-muted);
}

.batch-metrics {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  margin: 24px 0 10px;
  border: 1px solid var(--labor-line);
  border-radius: 9px;
}

.batch-metrics > div {
  padding: 17px 20px;
  border-right: 1px solid var(--labor-line);
}

.batch-metrics > div:last-child {
  border: 0;
}

.batch-metrics span {
  display: block;
  font-size: 12px;
  color: var(--labor-muted);
}

.batch-metrics strong {
  display: block;
  margin-top: 5px;
  font-size: 22px;
}

.detail-tabs {
  margin-top: 20px;
}

:deep(.clickable-row) {
  cursor: pointer;
}

@media (width <= 900px) {
  .drawer-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .batch-metrics {
    grid-template-columns: repeat(2, 1fr);
  }

  .header-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
