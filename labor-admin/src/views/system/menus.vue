<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import {
  getMenus,
  removeMenu,
  saveMenu,
  type MenuItem,
  type MenuType
} from "@/api/labor/menus";
import { getPermissions, type PermissionItem } from "@/api/labor/system";
import { message } from "@/utils/message";
import { reloadNavigationMenus } from "@/router/utils";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import MenuIconPicker from "./components/MenuIconPicker.vue";

defineOptions({ name: "SystemMenus" });

type MenuTree = MenuItem & {
  children?: MenuTree[];
  depth?: number;
  parentTitle?: string;
};

const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const tableRef = ref();
const formRef = ref<FormInstance>();
const menus = ref<MenuItem[]>([]);
const permissions = ref<PermissionItem[]>([]);
const expanded = ref(true);
const filter = reactive({ keyword: "", status: "ALL" });

const components = [
  { value: "welcome/index", label: "工作台" },
  { value: "imports/index", label: "导入中心" },
  { value: "basic-data/projects", label: "项目管理" },
  { value: "basic-data/companies", label: "参建企业管理" },
  { value: "basic-data/teams", label: "施工队管理" },
  { value: "basic-data/persons", label: "人员管理" },
  { value: "hik/index", label: "海康考勤采集" },
  { value: "matching/index", label: "人员匹配" },
  { value: "push/index", label: "信息推送任务" },
  { value: "push-logs/index", label: "接口调用日志" },
  { value: "integration/index", label: "接口配置" },
  { value: "system/users", label: "用户与角色" },
  { value: "system/menus", label: "菜单管理" },
  { value: "system/audit", label: "操作审计" }
];

const form = reactive<Partial<MenuItem>>({});

const rules: FormRules = {
  title: [{ required: true, message: "请输入菜单名称", trigger: "blur" }],
  routeName: [
    { required: true, message: "请输入路由标识", trigger: "blur" },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
      message: "以字母开头，只能包含字母、数字和下划线",
      trigger: "blur"
    }
  ],
  routePath: [
    { required: true, message: "请输入路由路径", trigger: "blur" },
    { pattern: /^\//, message: "路由路径必须以 / 开头", trigger: "blur" }
  ],
  componentPath: [
    {
      validator: (_rule, value, callback) => {
        if (form.menuType === "PAGE" && !value) {
          callback(new Error("请选择页面组件"));
        } else callback();
      },
      trigger: "change"
    }
  ]
};

function blankForm(parentId?: number): Partial<MenuItem> {
  return {
    parentId,
    menuType: "PAGE",
    title: "",
    routeName: "",
    routePath: "",
    componentPath: "",
    redirectPath: "",
    permissionCode: "",
    icon: "ri/menu-2-line",
    sortNo: 100,
    visible: true,
    enabled: true,
    keepAlive: false,
    builtin: false
  };
}

function resetForm(values: Partial<MenuItem>) {
  Object.keys(form).forEach(key => delete form[key]);
  Object.assign(form, values);
}

function buildTree(items: MenuItem[]): MenuTree[] {
  const map = new Map<number, MenuTree>();
  items.forEach(item => map.set(item.id, { ...item, children: [] }));
  const roots: MenuTree[] = [];
  map.forEach(item => {
    if (item.parentId && map.has(item.parentId)) {
      map.get(item.parentId)?.children?.push(item);
    } else roots.push(item);
  });
  const sort = (nodes: MenuTree[], depth = 0, parentTitle?: string) => {
    nodes.sort((a, b) => a.sortNo - b.sortNo || a.id - b.id);
    nodes.forEach(node => {
      node.depth = depth;
      node.parentTitle = parentTitle;
      sort(node.children || [], depth + 1, node.title);
    });
  };
  sort(roots);
  return roots;
}

const menuTree = computed(() => buildTree(menus.value));

const filteredTree = computed(() => {
  const keyword = filter.keyword.trim().toLowerCase();
  const visit = (nodes: MenuTree[]): MenuTree[] =>
    nodes.flatMap(node => {
      const children = visit(node.children || []);
      const keywordMatch =
        !keyword ||
        node.title.toLowerCase().includes(keyword) ||
        node.routePath.toLowerCase().includes(keyword) ||
        node.routeName.toLowerCase().includes(keyword);
      const statusMatch =
        filter.status === "ALL" ||
        (filter.status === "ENABLED" && node.enabled) ||
        (filter.status === "DISABLED" && !node.enabled);
      return (keywordMatch && statusMatch) || children.length
        ? [{ ...node, children }]
        : [];
    });
  return visit(menuTree.value);
});

const parentOptions = computed(() => {
  const excluded = new Set<number>();
  if (form.id) {
    const addDescendants = (parentId: number) => {
      excluded.add(parentId);
      menus.value
        .filter(item => item.parentId === parentId)
        .forEach(item => addDescendants(item.id));
    };
    addDescendants(form.id);
  }
  const directories = menus.value.filter(
    item => item.menuType === "DIRECTORY" && !excluded.has(item.id)
  );
  return buildTree(directories);
});

async function load() {
  loading.value = true;
  try {
    const [menuResult, permissionResult] = await Promise.all([
      getMenus(),
      getPermissions()
    ]);
    menus.value = menuResult.data;
    permissions.value = permissionResult.data;
  } catch (error: any) {
    fail(error, "菜单数据加载失败");
  } finally {
    loading.value = false;
  }
}

function openCreate(parent?: MenuItem) {
  resetForm(blankForm(parent?.id));
  if (!parent) form.menuType = "DIRECTORY";
  dialogVisible.value = true;
}

function openEdit(row: MenuItem) {
  resetForm({ ...row });
  dialogVisible.value = true;
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    await saveMenu({ ...form });
    message(form.id ? "菜单修改成功" : "菜单新增成功", {
      type: "success"
    });
    dialogVisible.value = false;
    await load();
    await reloadNavigationMenus();
  } catch (error: any) {
    fail(error, "菜单保存失败");
  } finally {
    saving.value = false;
  }
}

async function remove(row: MenuItem) {
  try {
    await ElMessageBox.confirm(`确认删除菜单“${row.title}”吗？`, "删除菜单", {
      type: "warning",
      confirmButtonText: "确认删除",
      cancelButtonText: "取消"
    });
    await removeMenu(row.id);
    message("菜单删除成功", { type: "success" });
    await load();
    await reloadNavigationMenus();
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    fail(error, "菜单删除失败");
  }
}

function toggleExpand() {
  expanded.value = !expanded.value;
  menus.value.forEach(item => {
    const row = menuTree.value
      .flatMap(flattenTree)
      .find(node => node.id === item.id);
    if (row) tableRef.value?.toggleRowExpansion(row, expanded.value);
  });
}

function flattenTree(node: MenuTree): MenuTree[] {
  return [node, ...(node.children || []).flatMap(flattenTree)];
}

function menuRowClass({ row }: { row: MenuTree }) {
  const rowType = row.menuType === "DIRECTORY" ? "directory" : "page";
  return `menu-row--${rowType} menu-row--level-${row.depth || 0}`;
}

function hierarchyLabel(row: MenuTree) {
  const level = (row.depth || 0) + 1;
  return `${level}级${row.menuType === "DIRECTORY" ? "目录" : "页面"}`;
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

watch(
  () => form.menuType,
  type => {
    if (type === "DIRECTORY") {
      form.componentPath = "";
      form.keepAlive = false;
    }
  }
);

onMounted(load);
</script>

<template>
  <div class="labor-page menu-page">
    <header class="labor-page__header">
      <div>
        <h1>菜单管理</h1>
        <p class="labor-page__desc">
          统一维护导航层级、名称、图标、排序和显示状态，保存后立即应用。
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="toggleExpand">
          {{ expanded ? "收起全部" : "展开全部" }}
        </el-button>
        <el-button type="primary" @click="openCreate()">新增目录</el-button>
      </div>
    </header>

    <section class="menu-toolbar">
      <el-input
        v-model="filter.keyword"
        clearable
        placeholder="按名称、路径或路由标识查询"
      />
      <el-select v-model="filter.status" class="status-filter">
        <el-option label="全部状态" value="ALL" />
        <el-option label="已启用" value="ENABLED" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <span>共 {{ menus.length }} 个菜单节点</span>
    </section>

    <div v-loading="loading" class="labor-table-wrap menu-table">
      <el-table
        ref="tableRef"
        :data="filteredTree"
        row-key="id"
        :indent="32"
        :row-class-name="menuRowClass"
        default-expand-all
        :tree-props="{ children: 'children' }"
        empty-text="暂无菜单数据"
      >
        <el-table-column label="菜单名称" min-width="300">
          <template #default="{ row }">
            <div
              class="menu-name"
              :class="{
                'menu-name--directory': row.menuType === 'DIRECTORY',
                'menu-name--child': row.depth > 0
              }"
            >
              <span class="menu-icon">
                <component :is="useRenderIcon(row.icon || 'ri/menu-2-line')" />
              </span>
              <div class="menu-name__text">
                <strong>{{ row.title }}</strong>
                <small v-if="row.parentTitle">
                  所属：{{ row.parentTitle }} · {{ row.routeName }}
                </small>
                <small v-else>路由标识：{{ row.routeName }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="层级类型" width="112">
          <template #default="{ row }">
            <el-tag
              class="hierarchy-tag"
              effect="plain"
              :type="row.menuType === 'DIRECTORY' ? undefined : 'info'"
            >
              {{ hierarchyLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="routePath" label="路由路径" min-width="190" />
        <el-table-column label="权限标识" min-width="150">
          <template #default="{ row }">
            <span class="permission-code">{{ row.permissionCode || "—" }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序" width="80" />
        <el-table-column label="导航显示" width="100">
          <template #default="{ row }">
            {{ row.visible ? "显示" : "隐藏" }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="labor-status">{{
              row.enabled ? "已启用" : "已停用"
            }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)"
              >编辑</el-button
            >
            <el-button
              v-if="row.menuType === 'DIRECTORY'"
              link
              type="primary"
              @click="openCreate(row)"
            >
              新增下级
            </el-button>
            <el-button
              v-if="!row.builtin"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑菜单' : '新增菜单'"
      width="780px"
      destroy-on-close
      close-on-press-escape
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="editor-grid">
          <el-form-item label="菜单类型" required>
            <el-segmented
              v-model="form.menuType"
              :disabled="form.builtin"
              :options="[
                { label: '目录', value: 'DIRECTORY' },
                { label: '页面', value: 'PAGE' }
              ]"
            />
          </el-form-item>
          <el-form-item label="上级目录">
            <el-tree-select
              v-model="form.parentId"
              :data="parentOptions"
              :disabled="form.builtin"
              node-key="id"
              :props="{ label: 'title', children: 'children' }"
              check-strictly
              clearable
              filterable
              placeholder="顶级菜单"
              class="w-full"
            />
          </el-form-item>
          <el-form-item label="菜单名称" prop="title">
            <el-input v-model="form.title" maxlength="100" />
          </el-form-item>
          <el-form-item label="菜单图标">
            <MenuIconPicker v-model="form.icon" />
          </el-form-item>
          <el-form-item label="路由标识" prop="routeName">
            <el-input
              v-model="form.routeName"
              :disabled="form.builtin"
              placeholder="例如 ProjectOverview"
            />
          </el-form-item>
          <el-form-item label="路由路径" prop="routePath">
            <el-input
              v-model="form.routePath"
              :disabled="form.builtin"
              placeholder="例如 /project/overview"
            />
          </el-form-item>
          <el-form-item
            v-if="form.menuType === 'PAGE'"
            label="页面组件"
            prop="componentPath"
          >
            <el-select
              v-model="form.componentPath"
              :disabled="form.builtin"
              filterable
              class="w-full"
              placeholder="选择已注册页面"
            >
              <el-option
                v-for="item in components"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-else label="默认跳转地址">
            <el-input
              v-model="form.redirectPath"
              placeholder="可填写第一个子菜单路径"
            />
          </el-form-item>
          <el-form-item label="权限标识">
            <el-select
              v-model="form.permissionCode"
              clearable
              filterable
              class="w-full"
              placeholder="不限制"
            >
              <el-option
                v-for="permission in permissions"
                :key="permission.code"
                :label="`${permission.name}（${permission.code}）`"
                :value="permission.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="菜单排序">
            <el-input-number
              v-model="form.sortNo"
              :min="1"
              :max="9999"
              controls-position="right"
              class="w-full!"
            />
          </el-form-item>
          <el-form-item label="导航显示">
            <el-switch
              v-model="form.visible"
              inline-prompt
              active-text="显示"
              inactive-text="隐藏"
            />
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch
              v-model="form.enabled"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
            />
          </el-form-item>
          <el-form-item v-if="form.menuType === 'PAGE'" label="页面缓存">
            <el-switch
              v-model="form.keepAlive"
              inline-prompt
              active-text="缓存"
              inactive-text="不缓存"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          保存菜单
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.menu-page {
  min-width: 920px;
}

.header-actions,
.menu-toolbar,
.menu-name {
  display: flex;
  align-items: center;
}

.header-actions {
  gap: 10px;
}

.menu-toolbar {
  gap: 12px;
  padding: 18px 0;
  border-bottom: 1px solid var(--labor-line);
}

.menu-toolbar .el-input {
  width: 320px;
}

.status-filter {
  width: 140px;
}

.menu-toolbar > span {
  margin-left: auto;
  color: var(--el-text-color-secondary);
}

.menu-table {
  margin-top: 0;
}

.menu-name {
  position: relative;
  gap: 11px;
  min-height: 46px;
}

.menu-icon {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
}

.menu-icon svg {
  width: 18px;
  height: 18px;
}

.menu-name > div {
  display: grid;
}

.menu-name strong {
  font-weight: 600;
  line-height: 22px;
}

.menu-name--directory strong {
  font-size: 15px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}

.menu-name small,
.permission-code {
  color: var(--el-text-color-secondary);
}

.menu-name small {
  line-height: 20px;
}

.menu-name--child::before {
  position: absolute;
  top: -43px;
  left: -24px;
  width: 18px;
  height: 66px;
  content: "";
  border-bottom: 1px solid #b9cece;
  border-left: 1px solid #b9cece;
  border-bottom-left-radius: 6px;
}

.menu-name--child {
  margin-left: 14px;
}

.hierarchy-tag {
  justify-content: center;
  min-width: 74px;
}

:deep(.el-table__header-wrapper th.el-table__cell) {
  background: #f7f9f9;
}

:deep(.el-table__body tr.menu-row--directory > td.el-table__cell) {
  background: #f2f7f7;
  border-top: 8px solid var(--el-bg-color);
  border-bottom-color: #d9e5e5;
}

:deep(.el-table__body tr.menu-row--directory:hover > td.el-table__cell) {
  background: #edf5f5 !important;
}

:deep(.el-table__body tr.menu-row--directory > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}

:deep(.el-table__body tr.menu-row--page > td.el-table__cell) {
  background: #fff;
}

:deep(.el-table__body tr.menu-row--page:hover > td.el-table__cell) {
  background: #f8fbfb !important;
}

:deep(.el-table__body tr.menu-row--page .el-table__expand-icon) {
  color: #89a2a2;
}

:deep(.el-table__body .el-table__expand-icon) {
  margin-right: 4px;
}

.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 22px;
}

@media (width <= 980px) {
  .menu-page {
    min-width: 0;
  }
}
</style>
