<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { getAuditLogs, type AuditItem } from "@/api/labor/system";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "AuditLogs" });

const loading = ref(false);
const rows = ref<AuditItem[]>([]);
const total = ref(0);
const query = reactive({ page: 0, size: 20 });

async function load() {
  loading.value = true;
  try {
    const result = (await getAuditLogs(query)).data;
    rows.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    message(error?.response?.data?.message || "审计日志加载失败", {
      type: "error"
    });
  } finally {
    loading.value = false;
  }
}

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  load();
}

onMounted(load);
</script>

<template>
  <div class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>操作审计</h1>
        <p class="labor-page__desc">
          记录导入、复核、发布、权限和接口配置等关键操作，不包含密钥及完整证件号。
        </p>
      </div>
    </header>
    <div class="labor-toolbar">
      <span class="audit-total">{{ total }} 条操作记录</span
      ><el-button text @click="load">刷新</el-button>
    </div>
    <div v-loading="loading" class="labor-table-wrap">
      <el-table :data="rows">
        <el-table-column label="时间" width="190">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="actor" label="操作人" width="120" />
        <el-table-column prop="action" label="动作" width="160" />
        <el-table-column prop="resourceType" label="资源" width="170" />
        <el-table-column prop="resourceId" label="资源编号" width="130" />
        <el-table-column
          prop="reason"
          label="说明"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column prop="clientIp" label="来源地址" width="150" />
        <el-table-column
          prop="traceId"
          label="追踪编号"
          min-width="260"
          show-overflow-tooltip
        />
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
  </div>
</template>

<style scoped>
.audit-total {
  font-size: 13px;
  color: var(--labor-muted);
}

.page-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
