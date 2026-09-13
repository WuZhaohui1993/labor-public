<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  downloadTemplate,
  uploadImport,
  type ImportScope
} from "@/api/labor/imports";
import {
  getCompanies,
  getPersonFilterTree,
  getPersons,
  getProjects,
  getTeams,
  getVersions,
  removeMasterData,
  saveCompanyPersonPushSetting,
  saveMasterData,
  type PersonFilterTreeNode
} from "@/api/labor/masterData";
import { ElMessageBox } from "element-plus";
import { message } from "@/utils/message";
import { hasPerms } from "@/utils/auth";
import { formatDate, formatDateTime } from "@/utils/dateTime";
import {
  companyTypes,
  educationLevels,
  idTypes,
  maritalStatuses,
  sexOptions,
  teamTypes,
  userTypes,
  workTypes,
  yesNoOptions
} from "./data";
import { regionData } from "@/utils/chinaArea";
import type { UploadFile, UploadRawFile } from "element-plus";
import CompanySelect from "./components/CompanySelect.vue";
import TeamSelect from "./components/TeamSelect.vue";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";
import { PAGE_SIZE_OPTIONS } from "@/utils/pagination";

defineOptions({ name: "BasicDataLedger" });

type LedgerType = "projects" | "companies" | "teams" | "persons";

const props = defineProps<{ type: LedgerType }>();
const router = useRouter();
const workspaceStore = useWorkspaceStoreHook();
const currentWorkspace = computed(() => workspaceStore.current);
const active = computed(() => props.type);
const loading = ref(false);
const rows = ref<any[]>([]);
const total = ref(0);
const query = reactive({ page: 0, size: 20, search: "" });
const versionDrawer = ref(false);
const versions = ref<any[]>([]);
const selected = ref<any>();
const editorVisible = ref(false);
const saving = ref(false);
const importing = ref(false);
const editingId = ref<number>();
const form = reactive<Record<string, any>>({});
const personExpansion = ref<string[]>([]);
const pushSettingIds = ref<number[]>([]);
const personTree = ref<PersonFilterTreeNode[]>([]);
const personTreeLoading = ref(false);
const personTreeSearch = ref("");
const personTreeRef = ref();
const selectedPersonNodeKey = ref("");
const personScope = reactive({ collCropCode: "", teamId: "" });

const typeNames: Record<LedgerType, string> = {
  projects: "项目",
  companies: "参建企业",
  teams: "施工队",
  persons: "人员"
};
const activeName = computed(() => typeNames[active.value]);
const canEdit = computed(() => hasPerms("master:edit"));
const canImport = computed(() => hasPerms("import:upload"));
const selectedPersonNode = computed(() => {
  const root = personTree.value[0];
  if (!root) return undefined;
  if (root.key === selectedPersonNodeKey.value) return root;
  for (const company of root.children) {
    if (company.key === selectedPersonNodeKey.value) return company;
    const team = company.children.find(
      item => item.key === selectedPersonNodeKey.value
    );
    if (team) return team;
  }
  return root;
});
const importScope = computed<ImportScope>(
  () =>
    ({
      projects: "PROJECT",
      companies: "COMPANY",
      teams: "TEAM",
      persons: "PERSON"
    })[active.value] as ImportScope
);

async function downloadImportTemplate() {
  try {
    const blob = await downloadTemplate(importScope.value);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `劳务实名制${activeName.value}导入模板_V1.0.xlsx`;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error: any) {
    message(error?.response?.data?.message || "导入模板下载失败", {
      type: "error"
    });
  }
}

async function handleModuleImport(file: UploadFile) {
  if (!file.raw) return;
  importing.value = true;
  try {
    const result = await uploadImport(file.raw, importScope.value);
    message(
      result.data.errorCount
        ? "文件已解析，请在导入中心处理校验错误"
        : "文件校验通过，请在导入中心完成复核发布",
      { type: result.data.errorCount ? "warning" : "success" }
    );
    await router.push({
      path: "/imports/center",
      query: { batchId: String(result.data.id) }
    });
  } catch (error: any) {
    message(error?.response?.data?.message || "文件上传失败", {
      type: "error"
    });
  } finally {
    importing.value = false;
  }
}

const columns = computed(
  () =>
    ({
      projects: [
        ["proCode", "项目编码", 190],
        ["projectName", "项目名称", 260],
        ["status", "状态", 110],
        ["versionNo", "版本", 80],
        ["updatedAt", "更新时间", 190]
      ],
      companies: [
        ["collCropCode", "统一社会信用代码", 200],
        ["companyName", "企业名称", 240],
        ["collCropType", "企业类型", 170],
        ["contactName", "联系人", 110],
        ["status", "状态", 100],
        ["versionNo", "版本", 80]
      ],
      teams: [
        ["teamId", "施工队编码", 170],
        ["teamName", "施工队名称", 220],
        ["companyName", "所属参建企业", 240],
        ["leaderName", "队长", 110],
        ["status", "状态", 100],
        ["versionNo", "版本", 80]
      ],
      persons: [
        ["name", "姓名", 110],
        ["idcardNumber", "证件号码", 210],
        ["teamName", "所属施工队", 200],
        ["companyName", "所属参建企业", 220],
        ["userType", "人员类型", 150],
        ["workType", "工种", 150],
        ["hikPersonId", "海康人员编号", 170],
        ["status", "状态", 100],
        ["versionNo", "版本", 80]
      ]
    })[active.value]
);

async function load() {
  loading.value = true;
  try {
    const params = {
      ...query,
      ...(active.value === "persons" && personScope.collCropCode
        ? { collCropCode: personScope.collCropCode }
        : {}),
      ...(active.value === "persons" && personScope.teamId
        ? { teamId: personScope.teamId }
        : {})
    };
    const request = {
      projects: getProjects,
      companies: getCompanies,
      teams: getTeams,
      persons: getPersons
    }[active.value];
    const result = (await request(params)).data;
    rows.value = result.items;
    total.value = result.total;
  } catch (error: any) {
    message(error?.response?.data?.message || "台账数据加载失败", {
      type: "error"
    });
  } finally {
    loading.value = false;
  }
}

async function loadPersonTree() {
  if (active.value !== "persons") return;
  personTreeLoading.value = true;
  try {
    const root = (await getPersonFilterTree()).data;
    personTree.value = root ? [root] : [];
    personScope.collCropCode = "";
    personScope.teamId = "";
    selectedPersonNodeKey.value = root?.key || "";
    await nextTick();
    personTreeRef.value?.setCurrentKey(selectedPersonNodeKey.value);
  } catch (error: any) {
    personTree.value = [];
    selectedPersonNodeKey.value = "";
    message(error?.response?.data?.message || "人员范围加载失败", {
      type: "error"
    });
  } finally {
    personTreeLoading.value = false;
  }
}

function filterPersonTree(value: string, data: PersonFilterTreeNode) {
  if (!value.trim()) return true;
  const keyword = value.trim().toLowerCase();
  return [data.label, data.proCode, data.collCropCode, data.teamId].some(item =>
    String(item || "")
      .toLowerCase()
      .includes(keyword)
  );
}

async function changePersonScope(node: PersonFilterTreeNode) {
  selectedPersonNodeKey.value = node.key;
  personScope.collCropCode =
    node.type === "PROJECT" ? "" : node.collCropCode || "";
  personScope.teamId = node.type === "TEAM" ? node.teamId || "" : "";
  query.page = 0;
  await load();
}

function changePageSize(size: number) {
  query.size = size;
  query.page = 0;
  load();
}

async function showVersions(row: any) {
  selected.value = row;
  try {
    versions.value = (await getVersions(active.value, row.id)).data;
    versionDrawer.value = true;
  } catch (error: any) {
    message(error?.response?.data?.message || "版本记录加载失败", {
      type: "error"
    });
  }
}

async function removeRow(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除${activeName.value}“${row.projectName || row.companyName || row.teamName || row.name}”吗？删除后台账不再展示，历史版本仍保留。`,
      `删除${activeName.value}`,
      {
        type: "warning",
        confirmButtonText: "确认删除",
        cancelButtonText: "取消"
      }
    );
    await removeMasterData(active.value, row.id);
    if (active.value === "projects") {
      await workspaceStore.load(true);
    }
    message(`${activeName.value}删除成功`, { type: "success" });
    await load();
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    message(error?.response?.data?.message || "删除失败", { type: "error" });
  }
}

async function changeCompanyPersonPushSetting(row: any, enabled: boolean) {
  if (pushSettingIds.value.includes(row.id)) return;
  row.personPushEnabled = enabled;
  pushSettingIds.value.push(row.id);
  try {
    await saveCompanyPersonPushSetting(row.id, enabled);
    message(
      enabled ? "已开启该企业人员及考勤推送" : "已关闭该企业人员及考勤推送",
      {
        type: "success"
      }
    );
  } catch (error: any) {
    row.personPushEnabled = !enabled;
    message(error?.response?.data?.message || "推送开关保存失败", {
      type: "error"
    });
  } finally {
    pushSettingIds.value = pushSettingIds.value.filter(id => id !== row.id);
  }
}

function defaultForm(type: LedgerType) {
  const common = { internalRemark: "" };
  return {
    projects: { ...common, proCode: "", projectName: "" },
    companies: {
      ...common,
      proCode: currentWorkspace.value?.proCode || "",
      collCropCode: "",
      companyName: "",
      collCropType: "LAOWU_CANJIAN",
      chinaFlag: "Y",
      entryDate: "",
      exitDate: "",
      contactName: "",
      contactIdType: "",
      contactIdNumber: "",
      contactMobile: "",
      blacklistFlag: "N"
    },
    teams: {
      ...common,
      teamId: "",
      proCode: currentWorkspace.value?.proCode || "",
      collCropCode: "",
      teamType: "CANJIAN_TEAM",
      teamName: "",
      entryDate: "",
      exitDate: "",
      leaderName: "",
      leaderIdType: "",
      leaderIdNumber: "",
      leaderMobile: ""
    },
    persons: {
      ...common,
      name: "",
      idcardType: "SHENFEN_ZHENGJIAN",
      idcardNumber: "",
      idcardStartDate: "",
      idcardEndDate: "",
      idcardForever: "N",
      proCode: currentWorkspace.value?.proCode || "",
      teamId: "",
      userType: "LAB_USER_BULIDER",
      workType: "WORK_TYPE_OTHER",
      entryDate: "",
      exitDate: "",
      politicsStatus: "",
      eduLevel: "",
      maritalStatus: "",
      sex: "",
      idcardAddress: "",
      homeAddress: "",
      birthday: "",
      nation: "",
      countryCode: "",
      provinceCode: "",
      positiveIdcardImage: "",
      negativeIdcardImage: "",
      headImage: "",
      positiveIdcardImagePresent: false,
      negativeIdcardImagePresent: false,
      headImagePresent: false,
      clearPositiveIdcardImage: false,
      clearNegativeIdcardImage: false,
      clearHeadImage: false,
      mobile: "",
      teamLeaderFlag: "N",
      hikPersonId: ""
    }
  }[type];
}

function resetForm(values: Record<string, any>) {
  Object.keys(form).forEach(key => delete form[key]);
  Object.assign(form, values);
}

function openCreate() {
  editingId.value = undefined;
  const values = defaultForm(active.value);
  if (active.value === "persons" && personScope.teamId) {
    resetForm({ ...values, teamId: personScope.teamId });
  } else {
    resetForm(values);
  }
  personExpansion.value = [];
  editorVisible.value = true;
}

function openEdit(row: any) {
  editingId.value = row.id;
  const values = { ...defaultForm(active.value), ...row };
  if (active.value === "persons") {
    values.positiveIdcardImage = "";
    values.negativeIdcardImage = "";
    values.headImage = "";
    values.clearPositiveIdcardImage = false;
    values.clearNegativeIdcardImage = false;
    values.clearHeadImage = false;
  }
  resetForm(values);
  personExpansion.value =
    active.value === "persons" &&
    [
      "politicsStatus",
      "eduLevel",
      "maritalStatus",
      "idcardAddress",
      "homeAddress",
      "nation",
      "countryCode",
      "provinceCode",
      "positiveIdcardImagePresent",
      "negativeIdcardImagePresent",
      "headImagePresent"
    ].some(field => Boolean(values[field]))
      ? ["extended"]
      : [];
  editorVisible.value = true;
}

function onIdcardForeverChanged(value: string) {
  if (value === "Y") form.idcardEndDate = "";
}

const imageFields = {
  positiveIdcardImage: {
    present: "positiveIdcardImagePresent",
    clear: "clearPositiveIdcardImage"
  },
  negativeIdcardImage: {
    present: "negativeIdcardImagePresent",
    clear: "clearNegativeIdcardImage"
  },
  headImage: { present: "headImagePresent", clear: "clearHeadImage" }
} as const;

type ImageField = keyof typeof imageFields;

const personImageItems: Array<{ field: ImageField; label: string }> = [
  { field: "positiveIdcardImage", label: "证件正面照" },
  { field: "negativeIdcardImage", label: "证件反面照" },
  { field: "headImage", label: "近照" }
];

function selectPersonImage(field: ImageField, file: UploadRawFile) {
  if (!["image/jpeg", "image/png"].includes(file.type)) {
    message("仅支持 JPG 或 PNG 图片", { type: "warning" });
    return false;
  }
  if (file.size > 3 * 1024 * 1024) {
    message("单张图片不能超过3MB", { type: "warning" });
    return false;
  }
  const reader = new FileReader();
  reader.onload = event => {
    const result = String(event.target?.result || "");
    form[field] = result.includes(",")
      ? result.slice(result.indexOf(",") + 1)
      : result;
    form[imageFields[field].present] = true;
    form[imageFields[field].clear] = false;
    message("图片已选择，保存后生效", { type: "success" });
  };
  reader.onerror = () => message("图片读取失败", { type: "error" });
  reader.readAsDataURL(file);
  return false;
}

function clearPersonImage(field: ImageField) {
  form[field] = "";
  form[imageFields[field].present] = false;
  form[imageFields[field].clear] = true;
}

function imageStatus(field: ImageField) {
  if (form[field]) return "已选择新图片";
  return form[imageFields[field].present] ? "已保存" : "未上传";
}

const dictionaryOptions = [
  ...companyTypes,
  ...teamTypes,
  ...idTypes,
  ...userTypes,
  ...workTypes,
  ...educationLevels,
  ...maritalStatuses,
  ...yesNoOptions,
  ...sexOptions
];

const fieldLabels: Record<string, string> = {
  proCode: "项目编码",
  projectName: "项目名称",
  collCompanyName: "项目或企业名称",
  collCropCode: "统一社会信用代码",
  companyName: "企业名称",
  personPushEnabled: "人员及考勤推送",
  collCropType: "企业类型",
  chinaFlag: "境内企业",
  isChina: "境内企业",
  contactName: "联系人",
  linkName: "联系人",
  contactIdType: "联系人证件类型",
  contactIdNumber: "联系人证件号码",
  linkMobile: "联系人手机",
  contactMobile: "联系人手机",
  blacklistFlag: "黑名单标记",
  collCropStatus: "黑名单标记",
  teamId: "施工队编码",
  teamType: "施工队类型",
  teamName: "施工队名称",
  leaderName: "施工队长姓名",
  teamLeaderName: "施工队长姓名",
  leaderIdType: "施工队长证件类型",
  teamLeaderIdcardType: "施工队长证件类型",
  leaderIdNumber: "施工队长证件号码",
  teamLeaderIdcardNumber: "施工队长证件号码",
  leaderMobile: "施工队长手机",
  teamLeaderMobile: "施工队长手机",
  name: "姓名",
  idcardType: "证件类型",
  idcardNumber: "证件号码",
  idcardStartDate: "证件起始日期",
  idcardEndDate: "证件截止日期",
  idcardForever: "证件永久有效",
  userType: "人员类型",
  workType: "工种",
  entryDate: "进场日期",
  entryTime: "进场日期",
  exitDate: "退场日期",
  exitTime: "退场日期",
  politicsStatus: "政治面貌",
  eduLevel: "文化程度",
  maritalStatus: "婚姻状况",
  sex: "性别",
  idcardAddress: "发证机关",
  homeAddress: "家庭住址",
  birthday: "出生日期",
  nation: "民族编码",
  countryCode: "国家编码",
  provinceCode: "省或地区编码",
  positiveIdcardImage: "证件正面照",
  negativeIdcardImage: "证件反面照",
  headImage: "近照",
  mobile: "手机号码",
  teamLeaderFlag: "施工队长",
  hikPersonId: "海康人员编号",
  internalRemark: "内部备注",
  status: "状态"
};

const dateFields = new Set([
  "birthday",
  "entryDate",
  "entryTime",
  "exitDate",
  "exitTime",
  "idcardEndDate",
  "idcardStartDate"
]);

const dateTimeFields = new Set([
  "createdAt",
  "publishedAt",
  "reviewedAt",
  "updatedAt"
]);

function displayValue(field: string, value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  if (dateFields.has(field)) return formatDate(String(value));
  if (dateTimeFields.has(field)) return formatDateTime(String(value));
  if (field === "status")
    return (
      { PUBLISHED: "已发布", DISABLED: "已停用" }[String(value)] ||
      String(value)
    );
  return (
    dictionaryOptions.find(item => item.value === value)?.label || String(value)
  );
}

function validateForm() {
  const requiredFields: Record<LedgerType, Array<[string, string]>> = {
    projects: [
      ["proCode", "项目编码"],
      ["projectName", "项目名称"]
    ],
    companies: [
      ["proCode", "项目编码"],
      ["collCropCode", "统一社会信用代码"],
      ["companyName", "企业名称"],
      ["collCropType", "企业类型"],
      ["chinaFlag", "境内企业"]
    ],
    teams: [
      ["teamId", "施工队编码"],
      ["proCode", "项目编码"],
      ["collCropCode", "所属企业代码"],
      ["teamType", "施工队类型"],
      ["teamName", "施工队名称"]
    ],
    persons: [
      ["name", "姓名"],
      ["idcardType", "证件类型"],
      ["proCode", "项目编码"],
      ["teamId", "施工队编码"],
      ["userType", "人员类型"],
      ["workType", "工种"],
      ["idcardForever", "证件永久有效"]
    ]
  };
  const missing = requiredFields[active.value].find(
    ([field]) => !String(form[field] ?? "").trim()
  );
  if (missing) return `请填写${missing[1]}`;
  if (
    active.value === "persons" &&
    !editingId.value &&
    !String(form.idcardNumber ?? "").trim()
  )
    return "请填写证件号码";
  if (
    active.value === "persons" &&
    form.idcardForever === "N" &&
    !form.idcardEndDate
  )
    return "证件非永久有效时必须填写截止日期";
  return "";
}

async function submitForm() {
  if (active.value !== "projects") {
    form.proCode = currentWorkspace.value?.proCode || "";
  }
  const validationMessage = validateForm();
  if (validationMessage) {
    message(validationMessage, { type: "warning" });
    return;
  }
  saving.value = true;
  try {
    await saveMasterData(active.value, editingId.value, { ...form });
    if (active.value === "projects") {
      await workspaceStore.load(true);
    }
    message(`${activeName.value}${editingId.value ? "修改" : "新增"}成功`, {
      type: "success"
    });
    editorVisible.value = false;
    query.page = 0;
    await load();
  } catch (error: any) {
    message(error?.response?.data?.message || "基础信息保存失败", {
      type: "error"
    });
  } finally {
    saving.value = false;
  }
}

function parseJson(value?: string) {
  if (!value) return {};
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

function versionEntries(version: any) {
  return Object.entries(parseJson(version.snapshotJson)).filter(
    ([, value]) => value !== null && value !== ""
  );
}

watch(personTreeSearch, value => personTreeRef.value?.filter(value));

watch(
  () => props.type,
  async () => {
    query.page = 0;
    query.search = "";
    personTreeSearch.value = "";
    if (active.value === "persons") {
      await Promise.all([loadPersonTree(), load()]);
      return;
    }
    personScope.collCropCode = "";
    personScope.teamId = "";
    await load();
  }
);

watch(
  () => workspaceStore.activeProCode,
  async (proCode, previousProCode) => {
    if (!proCode || !previousProCode || proCode === previousProCode) return;
    query.page = 0;
    query.search = "";
    personTreeSearch.value = "";
    personScope.collCropCode = "";
    personScope.teamId = "";
    if (active.value === "persons") {
      await Promise.all([loadPersonTree(), load()]);
      return;
    }
    await load();
  }
);

onMounted(async () => {
  if (active.value === "persons") {
    await Promise.all([loadPersonTree(), load()]);
    return;
  }
  await load();
});
</script>

<template>
  <div class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>{{ activeName }}管理</h1>
        <p class="labor-page__desc">
          支持{{ activeName }}新增、查询、修改和删除，所有变更保留历史版本。
        </p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="hasPerms('import:view')"
          @click="downloadImportTemplate"
        >
          下载导入模板
        </el-button>
        <el-upload
          v-if="canImport"
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx"
          :on-change="handleModuleImport"
        >
          <el-button :loading="importing">导入{{ activeName }}</el-button>
        </el-upload>
        <el-button v-if="canEdit" type="primary" @click="openCreate">
          新增{{ activeName }}
        </el-button>
      </div>
    </header>

    <div
      class="ledger-workspace"
      :class="{ 'has-person-tree': active === 'persons' }"
    >
      <aside
        v-if="active === 'persons'"
        v-loading="personTreeLoading"
        class="person-filter"
      >
        <div class="person-filter__heading">
          <strong>人员范围</strong>
          <small>按项目、企业或施工队筛选</small>
        </div>
        <el-input
          v-model="personTreeSearch"
          clearable
          class="person-tree-search"
          placeholder="筛选企业或施工队"
        />
        <div class="person-tree-wrap">
          <el-tree
            ref="personTreeRef"
            :data="personTree"
            node-key="key"
            default-expand-all
            highlight-current
            :expand-on-click-node="false"
            :filter-node-method="filterPersonTree"
            :current-node-key="selectedPersonNodeKey"
            empty-text="暂无人员范围数据"
            @node-click="changePersonScope"
          >
            <template #default="{ data }">
              <span class="person-tree-node">
                <span class="person-tree-node__label">{{ data.label }}</span>
                <small>{{
                  data.type === "PROJECT"
                    ? "项目"
                    : data.type === "COMPANY"
                      ? "企业"
                      : "施工队"
                }}</small>
              </span>
            </template>
          </el-tree>
        </div>
      </aside>

      <section class="ledger-results">
        <div class="labor-toolbar">
          <el-input
            v-model="query.search"
            clearable
            class="search-input"
            :placeholder="
              active === 'persons'
                ? '按姓名或海康人员编号查询'
                : '按编码或名称查询'
            "
            @keyup.enter="load"
            @clear="load"
          />
          <el-button type="primary" @click="load">查询</el-button>
          <span v-if="active === 'persons'" class="scope-label">
            当前：{{ selectedPersonNode?.label || "当前项目" }}
          </span>
          <span class="result-count">{{ total }} 条已发布记录</span>
        </div>

        <div v-loading="loading" class="labor-table-wrap">
          <el-table :data="rows" empty-text="暂无已发布数据">
            <el-table-column
              v-for="column in columns"
              :key="column[0]"
              :prop="String(column[0])"
              :label="String(column[1])"
              :width="column[2]"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                {{ displayValue(String(column[0]), row[String(column[0])]) }}
              </template>
            </el-table-column>
            <el-table-column
              v-if="active === 'companies'"
              label="人员及考勤推送"
              width="170"
            >
              <template #default="{ row }">
                <el-switch
                  :model-value="row.personPushEnabled"
                  :disabled="!canEdit || pushSettingIds.includes(row.id)"
                  :loading="pushSettingIds.includes(row.id)"
                  inline-prompt
                  active-text="推送"
                  inactive-text="不推送"
                  @change="
                    value => changeCompanyPersonPushSetting(row, Boolean(value))
                  "
                />
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="200">
              <template #default="{ row }">
                <el-button
                  v-if="canEdit"
                  link
                  type="primary"
                  @click="openEdit(row)"
                  >编辑</el-button
                >
                <el-button link type="primary" @click="showVersions(row)"
                  >版本</el-button
                >
                <el-button
                  v-if="canEdit"
                  link
                  type="danger"
                  @click="removeRow(row)"
                  >删除</el-button
                >
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
      </section>
    </div>

    <el-dialog
      v-model="editorVisible"
      destroy-on-close
      :width="active === 'persons' ? '920px' : '760px'"
      :title="`${editingId ? '修改' : '新增'}${activeName}`"
    >
      <el-form label-position="top" class="master-editor">
        <div v-if="active === 'projects'" class="editor-grid">
          <el-form-item label="项目编码" required>
            <el-input v-model="form.proCode" :disabled="Boolean(editingId)" />
          </el-form-item>
          <el-form-item label="项目名称" required>
            <el-input v-model="form.projectName" />
          </el-form-item>
          <el-form-item label="内部备注" class="span-2">
            <el-input v-model="form.internalRemark" type="textarea" :rows="3" />
          </el-form-item>
        </div>

        <div v-else-if="active === 'companies'" class="editor-grid">
          <el-form-item label="所属项目" required>
            <div class="workspace-field">
              <strong>{{ currentWorkspace?.projectName }}</strong>
              <small>{{ currentWorkspace?.proCode }}</small>
            </div>
          </el-form-item>
          <el-form-item label="统一社会信用代码" required>
            <el-input
              v-model="form.collCropCode"
              :disabled="Boolean(editingId)"
            />
          </el-form-item>
          <el-form-item label="企业名称" required>
            <el-input v-model="form.companyName" />
          </el-form-item>
          <el-form-item label="企业类型" required>
            <el-select v-model="form.collCropType" class="w-full">
              <el-option
                v-for="item in companyTypes"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="是否境内企业" required>
            <el-select v-model="form.chinaFlag" class="w-full">
              <el-option
                v-for="item in yesNoOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="黑名单标记">
            <el-select v-model="form.blacklistFlag" clearable class="w-full">
              <el-option
                v-for="item in yesNoOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="进场日期">
            <el-date-picker
              v-model="form.entryDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="w-full!"
            />
          </el-form-item>
          <el-form-item label="退场日期">
            <el-date-picker
              v-model="form.exitDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="w-full!"
            />
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contactName" />
          </el-form-item>
          <el-form-item label="联系人手机">
            <el-input v-model="form.contactMobile" />
          </el-form-item>
          <el-form-item label="联系人证件类型">
            <el-select v-model="form.contactIdType" clearable class="w-full">
              <el-option
                v-for="item in idTypes"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="联系人证件号码">
            <el-input v-model="form.contactIdNumber" />
          </el-form-item>
          <el-form-item label="内部备注" class="span-2">
            <el-input v-model="form.internalRemark" type="textarea" :rows="3" />
          </el-form-item>
        </div>

        <div v-else-if="active === 'teams'" class="editor-grid">
          <el-form-item label="施工队编码" required>
            <el-input v-model="form.teamId" :disabled="Boolean(editingId)" />
          </el-form-item>
          <el-form-item label="所属项目" required>
            <div class="workspace-field">
              <strong>{{ currentWorkspace?.projectName }}</strong>
              <small>{{ currentWorkspace?.proCode }}</small>
            </div>
          </el-form-item>
          <el-form-item label="所属参建企业" required>
            <CompanySelect
              v-model="form.collCropCode"
              :pro-code="form.proCode"
            />
          </el-form-item>
          <el-form-item label="施工队类型" required>
            <el-select v-model="form.teamType" class="w-full">
              <el-option
                v-for="item in teamTypes"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="施工队名称" required>
            <el-input v-model="form.teamName" />
          </el-form-item>
          <el-form-item label="队长姓名">
            <el-input v-model="form.leaderName" />
          </el-form-item>
          <el-form-item label="进场日期">
            <el-date-picker
              v-model="form.entryDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="w-full!"
            />
          </el-form-item>
          <el-form-item label="退场日期">
            <el-date-picker
              v-model="form.exitDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="w-full!"
            />
          </el-form-item>
          <el-form-item label="队长证件类型">
            <el-select v-model="form.leaderIdType" clearable class="w-full">
              <el-option
                v-for="item in idTypes"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="队长证件号码">
            <el-input v-model="form.leaderIdNumber" />
          </el-form-item>
          <el-form-item label="队长手机">
            <el-input v-model="form.leaderMobile" />
          </el-form-item>
          <el-form-item label="内部备注" class="span-2">
            <el-input v-model="form.internalRemark" type="textarea" :rows="3" />
          </el-form-item>
        </div>

        <div v-else class="person-editor">
          <section class="editor-section">
            <h3>身份信息</h3>
            <div class="editor-grid">
              <el-form-item label="姓名" required>
                <el-input v-model="form.name" />
              </el-form-item>
              <el-form-item label="证件类型" required>
                <el-select v-model="form.idcardType" class="w-full">
                  <el-option
                    v-for="item in idTypes"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="证件号码" required>
                <el-input v-model="form.idcardNumber" />
              </el-form-item>
              <el-form-item label="证件永久有效" required>
                <el-select
                  v-model="form.idcardForever"
                  class="w-full"
                  @change="onIdcardForeverChanged"
                >
                  <el-option
                    v-for="item in yesNoOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="证件起始日期">
                <el-date-picker
                  v-model="form.idcardStartDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="w-full!"
                />
              </el-form-item>
              <el-form-item
                label="证件截止日期"
                :required="form.idcardForever === 'N'"
              >
                <el-date-picker
                  v-model="form.idcardEndDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  :disabled="form.idcardForever === 'Y'"
                  class="w-full!"
                />
              </el-form-item>
            </div>
          </section>

          <section class="editor-section">
            <h3>项目归属</h3>
            <div class="editor-grid">
              <el-form-item label="所属项目" required>
                <div class="workspace-field">
                  <strong>{{ currentWorkspace?.projectName }}</strong>
                  <small>{{ currentWorkspace?.proCode }}</small>
                </div>
              </el-form-item>
              <el-form-item label="所属施工队" required>
                <TeamSelect v-model="form.teamId" :pro-code="form.proCode" />
              </el-form-item>
              <el-form-item label="人员类型" required>
                <el-select v-model="form.userType" class="w-full">
                  <el-option
                    v-for="item in userTypes"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="工种" required>
                <el-select v-model="form.workType" filterable class="w-full">
                  <el-option
                    v-for="item in workTypes"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="进场日期">
                <el-date-picker
                  v-model="form.entryDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="w-full!"
                />
              </el-form-item>
              <el-form-item label="退场日期">
                <el-date-picker
                  v-model="form.exitDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="w-full!"
                />
              </el-form-item>
              <el-form-item label="是否施工队长">
                <el-select
                  v-model="form.teamLeaderFlag"
                  clearable
                  class="w-full"
                >
                  <el-option
                    v-for="item in yesNoOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </div>
          </section>

          <section class="editor-section">
            <h3>联系与匹配</h3>
            <div class="editor-grid">
              <el-form-item label="性别">
                <el-select v-model="form.sex" clearable class="w-full">
                  <el-option
                    v-for="item in sexOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="出生日期">
                <el-date-picker
                  v-model="form.birthday"
                  type="date"
                  value-format="YYYY-MM-DD"
                  class="w-full!"
                />
              </el-form-item>
              <el-form-item label="手机号码">
                <el-input v-model="form.mobile" />
              </el-form-item>
              <el-form-item label="海康人员编号">
                <el-input v-model="form.hikPersonId" />
              </el-form-item>
            </div>
          </section>

          <el-collapse v-model="personExpansion" class="person-expansion">
            <el-collapse-item name="extended" title="扩展资料（选填）">
              <div class="editor-grid expansion-grid">
                <el-form-item label="政治面貌编码">
                  <el-input
                    v-model="form.politicsStatus"
                    placeholder="按平台字典填写"
                  />
                </el-form-item>
                <el-form-item label="文化程度">
                  <el-select v-model="form.eduLevel" clearable class="w-full">
                    <el-option
                      v-for="item in educationLevels"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="婚姻状况">
                  <el-select
                    v-model="form.maritalStatus"
                    clearable
                    class="w-full"
                  >
                    <el-option
                      v-for="item in maritalStatuses"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="民族编码">
                  <el-input
                    v-model="form.nation"
                    placeholder="按平台字典填写"
                  />
                </el-form-item>
                <el-form-item label="国家编码">
                  <el-input
                    v-model="form.countryCode"
                    placeholder="按平台基础数据填写"
                  />
                </el-form-item>
                <el-form-item label="省或地区">
                  <el-cascader
                    v-model="form.provinceCode"
                    :options="regionData"
                    :props="{ checkStrictly: true, emitPath: false }"
                    clearable
                    filterable
                    class="w-full"
                  />
                </el-form-item>
                <el-form-item label="发证机关" class="span-2">
                  <el-input v-model="form.idcardAddress" />
                </el-form-item>
                <el-form-item label="家庭住址" class="span-2">
                  <el-input
                    v-model="form.homeAddress"
                    type="textarea"
                    :rows="2"
                  />
                </el-form-item>
                <el-form-item
                  v-for="item in personImageItems"
                  :key="item.field"
                  :label="item.label"
                >
                  <div class="image-control">
                    <el-upload
                      accept="image/jpeg,image/png"
                      :show-file-list="false"
                      :before-upload="
                        file => selectPersonImage(item.field, file)
                      "
                    >
                      <el-button>选择图片</el-button>
                    </el-upload>
                    <span>{{ imageStatus(item.field) }}</span>
                    <el-button
                      v-if="form[imageFields[item.field].present]"
                      link
                      type="danger"
                      @click="clearPersonImage(item.field)"
                    >
                      清除
                    </el-button>
                  </div>
                </el-form-item>
                <el-form-item label="内部备注" class="span-2">
                  <el-input
                    v-model="form.internalRemark"
                    type="textarea"
                    :rows="3"
                  />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
        <p v-if="editingId" class="editor-tip">
          证件号码和手机号码留空时保持原值；业务唯一编码创建后不能修改。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">
          保存并生成新版本
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionDrawer" size="560px" title="版本记录">
      <div v-if="selected" class="version-subject">
        <span>{{ active }}</span>
        <strong>{{
          selected.projectName ||
          selected.companyName ||
          selected.teamName ||
          selected.name
        }}</strong>
        <small>{{ selected.proCode || selected.id }}</small>
      </div>
      <el-timeline v-if="versions.length" class="version-timeline">
        <el-timeline-item
          v-for="version in versions"
          :key="version.id"
          :timestamp="formatDateTime(version.createdAt)"
          placement="top"
        >
          <div class="version-head">
            <b>版本 {{ version.versionNo }}</b
            ><span>{{ version.changedBy }}</span>
          </div>
          <dl class="version-fields">
            <template v-for="entry in versionEntries(version)" :key="entry[0]">
              <dt>{{ fieldLabels[entry[0]] || entry[0] }}</dt>
              <dd>{{ displayValue(entry[0], entry[1]) }}</dd>
            </template>
          </dl>
          <small>来源批次：{{ version.sourceBatchId || "手工维护" }}</small>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无历史版本" />
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.ledger-workspace,
.ledger-results {
  min-width: 0;
}

.ledger-workspace.has-person-tree {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}

.person-filter {
  min-height: 420px;
  padding: 18px 20px 18px 0;
  border-right: 1px solid var(--labor-line);
}

.person-filter__heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-bottom: 14px;
}

.person-filter__heading strong {
  font-size: 15px;
  font-weight: 650;
  color: var(--labor-ink);
}

.person-filter__heading small {
  font-size: 12px;
  color: var(--labor-muted);
}

.person-tree-search {
  margin-bottom: 12px;
}

.person-tree-wrap {
  max-height: calc(100vh - 320px);
  overflow: auto;
}

.person-tree-wrap :deep(.el-tree) {
  --el-tree-node-hover-bg-color: #f0f7f7;

  min-width: 100%;
  background: transparent;
}

.person-tree-wrap :deep(.el-tree-node__content) {
  height: 36px;
  padding-right: 6px;
  border-radius: 6px;
  transition:
    color 140ms ease,
    background-color 140ms ease;
}

.person-tree-wrap
  :deep(
    .el-tree--highlight-current
      .el-tree-node.is-current
      > .el-tree-node__content
  ) {
  color: var(--labor-accent);
  background: var(--labor-accent-soft);
}

.person-tree-node {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-width: 0;
}

.person-tree-node__label {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  white-space: nowrap;
}

.person-tree-node small {
  flex: none;
  font-size: 10px;
  color: var(--labor-muted);
}

.search-input {
  width: 320px;
}

.scope-label {
  padding-left: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  color: var(--labor-muted);
  white-space: nowrap;
  border-left: 1px solid var(--labor-line);
}

.result-count {
  margin-left: auto;
  font-size: 13px;
  color: var(--labor-muted);
}

.page-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}

.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}

.editor-grid .span-2 {
  grid-column: 1 / -1;
}

.workspace-field {
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  min-height: 32px;
  padding: 7px 10px;
  background: #f4f8f8;
  border-left: 2px solid var(--labor-accent);
}

.workspace-field strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--labor-text);
}

.workspace-field small {
  margin-top: 2px;
  font-size: 11px;
  color: var(--labor-muted);
}

.person-editor {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.editor-section h3 {
  padding-bottom: 9px;
  margin: 0 0 15px;
  font-size: 14px;
  font-weight: 600;
  color: var(--labor-text);
  border-bottom: 1px solid var(--labor-line);
}

.person-expansion {
  border-top: 1px solid var(--labor-line);
  border-bottom: 1px solid var(--labor-line);
}

.person-expansion :deep(.el-collapse-item__header) {
  font-weight: 600;
  color: var(--labor-text);
}

.person-expansion :deep(.el-collapse-item__content) {
  padding: 18px 0 2px;
}

.image-control {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 32px;
}

.image-control span {
  font-size: 12px;
  color: var(--labor-muted);
}

.editor-tip {
  padding: 10px 12px;
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--labor-muted);
  background: var(--labor-accent-soft);
  border-left: 2px solid var(--labor-accent);
}

.version-subject {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 15px 16px;
  margin-bottom: 25px;
  background: var(--labor-accent-soft);
  border-left: 3px solid var(--labor-accent);
}

.version-subject span,
.version-subject small {
  font-size: 11px;
  color: var(--labor-muted);
}

.version-subject strong {
  font-size: 17px;
}

.version-timeline {
  padding: 6px 8px;
}

.version-head {
  display: flex;
  justify-content: space-between;
}

.version-head span {
  font-size: 12px;
  color: var(--labor-muted);
}

.version-fields {
  display: grid;
  grid-template-columns: 130px 1fr;
  padding: 12px 0;
  margin: 8px 0;
  font-size: 12px;
  border-top: 1px solid var(--labor-line);
  border-bottom: 1px solid var(--labor-line);
}

.version-fields dt {
  color: var(--labor-muted);
}

.version-fields dd {
  margin: 0;
  overflow-wrap: anywhere;
}

@media (width <= 760px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }

  .editor-grid .span-2 {
    grid-column: auto;
  }

  .scope-label {
    padding-left: 0;
    border-left: 0;
  }

  .result-count {
    margin-left: 0;
  }
}

@media (width <= 980px) {
  .ledger-workspace.has-person-tree {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .person-filter {
    min-height: 0;
    padding: 18px 0;
    border-right: 0;
    border-bottom: 1px solid var(--labor-line);
  }

  .person-tree-wrap {
    max-height: 280px;
  }
}
</style>
