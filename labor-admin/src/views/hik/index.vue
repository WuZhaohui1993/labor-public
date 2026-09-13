<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";
import {
  collectHikEvents,
  disableHikMapping,
  getHikEvents,
  getHikMappings,
  getHikOrganizationOptions,
  saveHikMapping,
  updateHikDirection,
  type HikEvent,
  type HikMapping,
  type HikOrganizationOption,
  type MatchStatus
} from "@/api/labor/hik";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";
import { message } from "@/utils/message";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";
import { useUserStoreHook } from "@/store/modules/user";

defineOptions({ name: "HikCollection" });

const activeTab = ref("mappings");
const workspaceStore = useWorkspaceStoreHook();
const userStore = useUserStoreHook();
const currentWorkspace = computed(() => workspaceStore.current);
const canManualMapping = computed(() =>
  userStore.roles.includes("SYSTEM_ADMIN")
);
const loading = ref(false);
const mappings = ref<HikMapping[]>([]);
const events = ref<HikEvent[]>([]);
const total = ref(0);
const query = reactive({
  page: 0,
  size: 20,
  matchStatus: "" as MatchStatus | "",
  search: ""
});

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  loadEvents();
}
const mappingDialog = ref(false);
const mappingForm = reactive({
  id: undefined as number | undefined,
  proCode: "",
  orgIndexCode: "",
  orgName: "",
  orgPath: "",
  mappingSource: "HIKVISION" as "HIKVISION" | "MANUAL",
  includeChildren: true,
  enabled: true
});
const organizationLoading = ref(false);
const organizationLoaded = ref(false);
const organizationError = ref("");
const organizationOptions = ref<HikOrganizationOption[]>([]);
const manualMode = ref(false);
const collectDialog = ref(false);
const collecting = ref(false);
const directionDialog = ref(false);
const directionForm = reactive({ eventId: 0, direction: "JINCHANG_JINCHU" });
const collectForm = reactive({
  mappingId: undefined as number | undefined,
  range: [] as Date[],
  advanceCursor: false
});

const statusLabels: Record<string, string> = {
  UNMATCHED: "待匹配",
  MATCHED: "已匹配",
  CONFLICT: "需处理",
  RELEASED: "已解除"
};
const directionLabels: Record<string, string> = {
  JINCHANG_JINCHU: "进场",
  TUICHANG_JINCHU: "出场",
  UNKNOWN: "待确认"
};

async function loadMappings() {
  loading.value = true;
  try {
    mappings.value = (await getHikMappings()).data;
  } catch (error: any) {
    fail(error, "组织映射加载失败");
  } finally {
    loading.value = false;
  }
}

async function loadEvents(reset = false) {
  if (reset) query.page = 0;
  loading.value = true;
  try {
    const result = (await getHikEvents(query)).data;
    events.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    fail(error, "采集记录加载失败");
  } finally {
    loading.value = false;
  }
}

type OrganizationTreeNode = HikOrganizationOption & {
  children: OrganizationTreeNode[];
  disabled: boolean;
};

const organizationTree = computed<OrganizationTreeNode[]>(() => {
  const nodes = new Map<string, OrganizationTreeNode>();
  organizationOptions.value.forEach(item => {
    nodes.set(item.orgIndexCode, {
      ...item,
      children: [],
      disabled: item.mapped && item.orgIndexCode !== mappingForm.orgIndexCode
    });
  });
  const roots: OrganizationTreeNode[] = [];
  nodes.forEach(node => {
    const parent = node.parentOrgIndexCode
      ? nodes.get(node.parentOrgIndexCode)
      : undefined;
    if (parent && parent.orgIndexCode !== node.orgIndexCode) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  const sort = (items: OrganizationTreeNode[]) => {
    items.sort(
      (left, right) =>
        left.sortOrder - right.sortOrder ||
        left.orgName.localeCompare(right.orgName, "zh-CN")
    );
    items.forEach(item => sort(item.children));
  };
  sort(roots);
  return roots;
});

const selectedOrganization = computed(() =>
  organizationOptions.value.find(
    item => item.orgIndexCode === mappingForm.orgIndexCode
  )
);

async function loadOrganizationOptions(force = false) {
  if (organizationLoaded.value && !force) return;
  organizationLoading.value = true;
  organizationError.value = "";
  try {
    organizationOptions.value = (await getHikOrganizationOptions()).data;
    organizationLoaded.value = true;
  } catch (error: any) {
    organizationError.value =
      error?.response?.data?.message || "海康组织读取失败，请检查接口配置";
  } finally {
    organizationLoading.value = false;
  }
}

async function openMapping(item?: HikMapping) {
  Object.assign(mappingForm, {
    id: item?.id,
    proCode: item?.proCode || currentWorkspace.value?.proCode || "",
    orgIndexCode: item?.orgIndexCode || "",
    orgName: item?.orgName || "",
    orgPath: item?.orgPath || "",
    mappingSource: item?.mappingSource || "HIKVISION",
    includeChildren: item?.includeChildren ?? true,
    enabled: item?.enabled ?? true
  });
  manualMode.value = item?.mappingSource === "MANUAL";
  mappingDialog.value = true;
  if (!manualMode.value) await loadOrganizationOptions();
}

function selectOrganization(code: string) {
  const selected = organizationOptions.value.find(
    item => item.orgIndexCode === code
  );
  if (!selected) return;
  mappingForm.orgName = selected.orgName;
  mappingForm.orgPath = selected.orgPath || selected.orgName;
  mappingForm.mappingSource = "HIKVISION";
}

function enableManualMode() {
  if (!canManualMapping.value) return;
  manualMode.value = true;
  mappingForm.mappingSource = "MANUAL";
  mappingForm.orgIndexCode = "";
  mappingForm.orgName = "";
  mappingForm.orgPath = "";
}

async function enableOrganizationMode() {
  manualMode.value = false;
  mappingForm.mappingSource = "HIKVISION";
  mappingForm.orgIndexCode = "";
  mappingForm.orgName = "";
  mappingForm.orgPath = "";
  await loadOrganizationOptions();
}

async function submitMapping() {
  if (!mappingForm.orgIndexCode) {
    message(manualMode.value ? "请填写海康组织编码" : "请选择海康组织", {
      type: "warning"
    });
    return;
  }
  try {
    await saveHikMapping(mappingForm.id, {
      proCode: mappingForm.proCode,
      orgIndexCode: mappingForm.orgIndexCode,
      orgName: mappingForm.orgName,
      orgPath: mappingForm.orgPath,
      mappingSource: mappingForm.mappingSource,
      includeChildren: mappingForm.includeChildren,
      enabled: mappingForm.enabled
    });
    mappingDialog.value = false;
    message("组织映射已保存", { type: "success" });
    await loadMappings();
  } catch (error: any) {
    fail(error, "组织映射保存失败");
  }
}

async function disableMapping(item: HikMapping) {
  await ElMessageBox.confirm(
    `确认停用“${item.orgName || item.orgIndexCode}”吗？`,
    "停用映射",
    {
      confirmButtonText: "确认停用",
      cancelButtonText: "取消",
      type: "warning"
    }
  );
  try {
    await disableHikMapping(item.id);
    message("组织映射已停用", { type: "success" });
    await loadMappings();
  } catch (error: any) {
    fail(error, "组织映射停用失败");
  }
}

function openCollect(item?: HikMapping) {
  collectForm.mappingId = item?.id;
  collectForm.range = [];
  collectForm.advanceCursor = false;
  collectDialog.value = true;
}

async function submitCollect() {
  if (!collectForm.mappingId) {
    message("请选择需要补查的组织映射", { type: "warning" });
    return;
  }
  collecting.value = true;
  try {
    const [start, end] = collectForm.range || [];
    const result = (
      await collectHikEvents({
        mappingId: collectForm.mappingId,
        startTime: start?.toISOString(),
        endTime: end?.toISOString(),
        advanceCursor: collectForm.advanceCursor
      })
    ).data;
    collectDialog.value = false;
    message(
      `补查完成：查询 ${result.queried} 条，新增 ${result.inserted} 条，补全 ${result.enriched} 条，重复 ${result.duplicates} 条`,
      {
        type: "success"
      }
    );
    activeTab.value = "events";
    await Promise.all([loadMappings(), loadEvents(true)]);
  } catch (error: any) {
    fail(error, "海康补查失败");
  } finally {
    collecting.value = false;
  }
}

function confirmDirection(item: HikEvent) {
  directionForm.eventId = item.id;
  directionForm.direction = "JINCHANG_JINCHU";
  directionDialog.value = true;
}

async function submitDirection() {
  try {
    await updateHikDirection(directionForm.eventId, directionForm.direction);
    directionDialog.value = false;
    message("进出方向已更新，可到人员匹配页重新匹配", { type: "success" });
    await loadEvents();
  } catch (error: any) {
    fail(error, "进出方向更新失败");
  }
}

function onTabChange(name: string | number) {
  if (name === "events") loadEvents(true);
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

onMounted(loadMappings);

watch(
  () => currentWorkspace.value?.proCode,
  async (current, previous) => {
    if (!current || current === previous) return;
    organizationLoaded.value = false;
    organizationOptions.value = [];
    organizationError.value = "";
    await loadMappings();
    if (activeTab.value === "events") await loadEvents(true);
  }
);
</script>

<template>
  <div v-loading="loading" class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>海康考勤采集</h1>
        <p class="labor-page__desc">
          维护项目与海康组织范围，按游标增量采集并自动去重。
        </p>
      </div>
      <el-button
        v-if="hasPerms('hik:operate')"
        type="primary"
        @click="openCollect()"
      >
        历史补查
      </el-button>
    </header>

    <el-tabs v-model="activeTab" class="labor-tabs" @tab-change="onTabChange">
      <el-tab-pane label="组织映射" name="mappings">
        <div class="labor-toolbar">
          <span class="toolbar-note"
            >共 {{ mappings.length }} 条项目组织映射</span
          >
          <el-button
            v-if="hasPerms('hik:operate')"
            type="primary"
            plain
            @click="openMapping()"
          >
            新增映射
          </el-button>
        </div>
        <div class="labor-table-wrap">
          <el-table :data="mappings" empty-text="暂无组织映射">
            <el-table-column prop="orgName" label="海康组织" min-width="180">
              <template #default="{ row }">
                <div class="mapping-organization">
                  <strong>{{ row.orgName || "—" }}</strong>
                  <small v-if="row.orgPath">{{ row.orgPath }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              prop="orgIndexCode"
              label="海康组织编码"
              min-width="210"
              show-overflow-tooltip
            />
            <el-table-column label="采集范围" width="130">
              <template #default="{ row }">
                {{ row.includeChildren ? "包含下级组织" : "仅当前组织" }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <span
                  class="labor-status"
                  :class="row.enabled ? 'is-success' : 'is-muted'"
                >
                  {{ row.enabled ? "已启用" : "已停用" }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="采集游标" width="190">
              <template #default="{ row }">{{
                formatDateTime(row.lastEventTime)
              }}</template>
            </el-table-column>
            <el-table-column label="最近成功" width="190">
              <template #default="{ row }">{{
                formatDateTime(row.lastSuccessAt)
              }}</template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openCollect(row)"
                  >补查</el-button
                >
                <el-button
                  v-if="hasPerms('hik:operate')"
                  link
                  @click="openMapping(row)"
                  >修改</el-button
                >
                <el-button
                  v-if="hasPerms('hik:operate') && row.enabled"
                  link
                  type="danger"
                  @click="disableMapping(row)"
                >
                  停用
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="采集记录" name="events">
        <div class="labor-toolbar">
          <el-select
            v-model="query.matchStatus"
            clearable
            placeholder="匹配状态"
            class="filter-status"
            @change="loadEvents(true)"
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
            @keyup.enter="loadEvents(true)"
          />
          <el-button @click="loadEvents(true)">查询</el-button>
        </div>
        <div class="labor-table-wrap">
          <el-table :data="events" empty-text="暂无采集记录">
            <el-table-column prop="personName" label="海康姓名" width="120">
              <template #default="{ row }">{{
                row.personName || "—"
              }}</template>
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
            <el-table-column label="方向" width="100">
              <template #default="{ row }">{{
                directionLabels[row.direction] || "待确认"
              }}</template>
            </el-table-column>
            <el-table-column label="数据来源" width="110">
              <template #default="{ row }">
                <el-tag
                  :type="
                    row.attendanceSource === 'AUTO_COMPLETED'
                      ? 'warning'
                      : 'success'
                  "
                  effect="plain"
                >
                  {{
                    row.attendanceSource === "AUTO_COMPLETED"
                      ? "自动补全"
                      : "海康采集"
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="checkLocation"
              label="位置"
              min-width="150"
              show-overflow-tooltip
            />
            <el-table-column label="匹配状态" width="110">
              <template #default="{ row }">
                <span
                  class="labor-status"
                  :class="`status-${row.matchStatus.toLowerCase()}`"
                >
                  {{ statusLabels[row.matchStatus] || row.matchStatus }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.direction === 'UNKNOWN' && hasPerms('hik:operate')"
                  link
                  type="primary"
                  @click="confirmDirection(row)"
                >
                  确认方向
                </el-button>
                <span v-else>—</span>
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
              loadEvents();
            }
          "
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="mappingDialog"
      :title="mappingForm.id ? '修改组织映射' : '新增组织映射'"
      width="540px"
    >
      <el-form label-position="top">
        <el-form-item label="项目" required>
          <div class="workspace-field">
            <strong>{{ currentWorkspace?.projectName }}</strong>
            <small>{{ currentWorkspace?.proCode }}</small>
          </div>
        </el-form-item>
        <template v-if="mappingForm.id">
          <el-form-item label="已映射海康组织">
            <div class="selected-organization">
              <strong>{{
                mappingForm.orgName || mappingForm.orgIndexCode
              }}</strong>
              <span>{{ mappingForm.orgPath || "未记录组织路径" }}</span>
              <small>{{ mappingForm.orgIndexCode }}</small>
            </div>
          </el-form-item>
        </template>
        <template v-else-if="!manualMode">
          <el-form-item label="选择海康组织" required>
            <div class="organization-selector">
              <el-tree-select
                v-model="mappingForm.orgIndexCode"
                :data="organizationTree"
                :props="{
                  label: 'orgName',
                  children: 'children',
                  disabled: 'disabled'
                }"
                node-key="orgIndexCode"
                check-strictly
                filterable
                clearable
                :render-after-expand="false"
                :loading="organizationLoading"
                placeholder="按组织名称搜索或从组织树选择"
                class="w-full"
                @change="selectOrganization"
              />
              <div class="selector-actions">
                <small v-if="selectedOrganization">
                  {{
                    selectedOrganization.orgPath || selectedOrganization.orgName
                  }}
                </small>
                <el-button
                  link
                  type="primary"
                  :loading="organizationLoading"
                  @click="loadOrganizationOptions(true)"
                >
                  刷新组织
                </el-button>
              </div>
            </div>
          </el-form-item>
          <el-alert
            v-if="organizationError"
            :title="organizationError"
            type="warning"
            :closable="false"
            show-icon
          />
          <div v-if="canManualMapping" class="manual-entry-action">
            <span>组织接口暂时不可用时，系统管理员可使用编码兜底。</span>
            <el-button link type="primary" @click="enableManualMode">
              手工录入组织编码
            </el-button>
          </div>
        </template>
        <template v-else>
          <el-alert
            title="当前为管理员手工录入模式，请确认编码来自海康平台。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-form-item label="海康组织编码" required>
            <el-input
              v-model="mappingForm.orgIndexCode"
              placeholder="填写海康平台组织唯一编码"
            />
          </el-form-item>
          <el-form-item label="组织名称">
            <el-input v-model="mappingForm.orgName" />
          </el-form-item>
          <el-form-item label="组织完整路径">
            <el-input v-model="mappingForm.orgPath" />
          </el-form-item>
          <el-button link type="primary" @click="enableOrganizationMode">
            返回海康组织选择
          </el-button>
        </template>
        <el-form-item label="采集范围">
          <div class="include-children-control">
            <el-switch v-model="mappingForm.includeChildren" />
            <span>包含所选组织的全部下级组织</span>
          </div>
          <small class="form-hint">
            关闭后只查询当前组织；开启后系统会根据海康组织树自动加入下级组织编码。
          </small>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="mappingForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mappingDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMapping">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="collectDialog" title="海康历史补查" width="600px">
      <el-form label-position="top">
        <el-form-item label="组织映射" required>
          <el-select
            v-model="collectForm.mappingId"
            filterable
            class="w-full"
            placeholder="请选择组织映射"
          >
            <el-option
              v-for="item in mappings.filter(row => row.enabled)"
              :key="item.id"
              :label="item.orgName || item.orgIndexCode"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补查时间范围">
          <el-date-picker
            v-model="collectForm.range"
            type="datetimerange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            class="w-full"
          />
          <small class="form-hint"
            >留空时从当前采集游标继续；历史补查默认不改变游标。</small
          >
        </el-form-item>
        <el-form-item label="推进采集游标">
          <el-switch v-model="collectForm.advanceCursor" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="collectDialog = false">取消</el-button>
        <el-button type="primary" :loading="collecting" @click="submitCollect"
          >开始补查</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="directionDialog" title="确认进出方向" width="420px">
      <el-form label-position="top">
        <el-form-item label="进出方向" required>
          <el-select v-model="directionForm.direction" class="w-full">
            <el-option label="进场" value="JINCHANG_JINCHU" />
            <el-option label="出场" value="TUICHANG_JINCHU" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="directionDialog = false">取消</el-button>
        <el-button type="primary" @click="submitDirection">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.labor-tabs {
  margin-top: 18px;
}

.labor-toolbar .toolbar-note {
  margin-right: auto;
  font-size: 13px;
  color: var(--labor-muted);
}

.filter-project {
  width: 280px;
}

.filter-status {
  width: 150px;
}

.filter-search {
  width: 300px;
}

.form-hint {
  display: block;
  margin-top: 7px;
  color: var(--labor-muted);
}

.mapping-organization,
.selected-organization {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mapping-organization small,
.selected-organization span,
.selected-organization small,
.selector-actions small,
.manual-entry-action span,
.include-children-control span {
  font-size: 12px;
  color: var(--labor-muted);
}

.selected-organization {
  width: 100%;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--labor-line);
  border-radius: 8px;
}

.organization-selector {
  width: 100%;
}

.selector-actions,
.manual-entry-action,
.include-children-control {
  display: flex;
  align-items: center;
}

.selector-actions {
  gap: 12px;
  justify-content: space-between;
  min-height: 30px;
}

.selector-actions small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.manual-entry-action {
  justify-content: space-between;
  margin: 10px 0 18px;
}

.include-children-control {
  gap: 10px;
}
</style>
