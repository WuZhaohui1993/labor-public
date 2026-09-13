<script setup lang="ts">
import { computed, ref } from "vue";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";

defineOptions({ name: "MenuIconPicker" });

const model = defineModel<string>({ default: "" });
const search = ref("");

const icons = [
  { value: "ep/home-filled", label: "首页" },
  { value: "ep/menu", label: "菜单" },
  { value: "ri/dashboard-line", label: "工作台" },
  { value: "ri/file-excel-2-line", label: "表格导入" },
  { value: "ri/upload-cloud-2-line", label: "上传" },
  { value: "ri/database-2-line", label: "数据" },
  { value: "ri/file-list-3-line", label: "清单" },
  { value: "ri/links-fill", label: "企业关联" },
  { value: "ri/user-settings-line", label: "用户设置" },
  { value: "ri/admin-line", label: "人员" },
  { value: "ri/exchange-box-line", label: "同步" },
  { value: "ri/camera-lens-line", label: "门禁采集" },
  { value: "ri/user-search-line", label: "人员匹配" },
  { value: "ri/send-plane-line", label: "信息推送" },
  { value: "ri/file-search-line", label: "日志查询" },
  { value: "ri/links-line", label: "接口" },
  { value: "ri/code-s-slash-line", label: "接口配置" },
  { value: "ri/settings-3-line", label: "系统设置" },
  { value: "ri/menu-2-line", label: "菜单管理" },
  { value: "ri/folder-settings-line", label: "目录设置" },
  { value: "ri/shield-user-line", label: "权限" },
  { value: "ri/building-line", label: "单位" },
  { value: "ri/team-line", label: "团队" },
  { value: "ri/tools-line", label: "运维" }
];

const filtered = computed(() => {
  const value = search.value.trim().toLowerCase();
  if (!value) return icons;
  return icons.filter(
    icon =>
      icon.label.includes(search.value.trim()) ||
      icon.value.toLowerCase().includes(value)
  );
});
</script>

<template>
  <el-popover width="420" trigger="click" popper-class="menu-icon-popper">
    <template #reference>
      <button class="icon-trigger" type="button">
        <component
          :is="useRenderIcon(model || 'ri/menu-2-line')"
          class="icon-trigger__preview"
        />
        <span>{{ model || "选择菜单图标" }}</span>
        <small>更换</small>
      </button>
    </template>

    <div class="icon-panel">
      <el-input v-model="search" clearable placeholder="搜索图标名称" />
      <div class="icon-grid">
        <button
          v-for="icon in filtered"
          :key="icon.value"
          type="button"
          :class="{ 'is-active': model === icon.value }"
          :title="icon.label"
          @click="model = icon.value"
        >
          <component :is="useRenderIcon(icon.value)" />
          <span>{{ icon.label }}</span>
        </button>
      </div>
      <el-empty
        v-if="!filtered.length"
        description="没有匹配的图标"
        :image-size="54"
      />
      <div class="icon-panel__footer">
        <span>共 {{ filtered.length }} 个图标</span>
        <el-button text type="danger" @click="model = ''">清除图标</el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped lang="scss">
.icon-trigger {
  display: grid;
  grid-template-columns: 30px 1fr auto;
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 40px;
  padding: 5px 11px;
  color: var(--el-text-color-regular);
  text-align: left;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  transition: border-color 160ms ease;
}

.icon-trigger:hover {
  border-color: var(--el-color-primary);
}

.icon-trigger__preview {
  width: 22px;
  height: 22px;
  color: var(--el-color-primary);
}

.icon-trigger small {
  color: var(--el-text-color-secondary);
}

.icon-panel {
  display: grid;
  gap: 12px;
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 7px;
  max-height: 280px;
  overflow: auto;
}

.icon-grid button {
  display: grid;
  gap: 5px;
  justify-items: center;
  min-height: 62px;
  padding: 8px 4px;
  color: var(--el-text-color-regular);
  background: transparent;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition:
    color 140ms ease,
    border-color 140ms ease,
    background 140ms ease;
}

.icon-grid button:hover,
.icon-grid button.is-active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.icon-grid button svg {
  width: 21px;
  height: 21px;
}

.icon-grid button span {
  max-width: 54px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 11px;
  white-space: nowrap;
}

.icon-panel__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 4px;
  color: var(--el-text-color-secondary);
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
