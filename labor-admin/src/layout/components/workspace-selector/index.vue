<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";
import { useUserStoreHook } from "@/store/modules/user";
import { reloadNavigationMenus } from "@/router/utils";
import { storageLocal } from "@pureadmin/utils";
import { type DataInfo, userKey } from "@/utils/auth";

defineOptions({ name: "WorkspaceSelector" });

const workspaceStore = useWorkspaceStoreHook();
const userStore = useUserStoreHook();
const route = useRoute();
const router = useRouter();
const { workspaces, activeProCode, current, loading } =
  storeToRefs(workspaceStore);

const currentName = computed(() => current.value?.projectName || "选择项目");

function applyPermissions() {
  if (!current.value) return;
  userStore.SET_ROLES(current.value.roles);
  userStore.SET_PERMS(current.value.permissions);
  const stored = storageLocal().getItem<DataInfo<number>>(userKey);
  if (stored) {
    storageLocal().setItem(userKey, {
      ...stored,
      roles: current.value.roles,
      permissions: current.value.permissions
    });
  }
}

async function changeWorkspace(proCode: string) {
  workspaceStore.select(proCode);
  applyPermissions();
  await reloadNavigationMenus();
  if (route.path.startsWith("/basic-data/projects")) return;
  await router.replace({
    path: `/redirect${route.path}`,
    query: route.query
  });
}

onMounted(async () => {
  await workspaceStore.load();
  applyPermissions();
  await reloadNavigationMenus();
});
</script>

<template>
  <div class="workspace-switcher" :class="{ 'is-loading': loading }">
    <span class="workspace-switcher__dot" />
    <div class="workspace-switcher__copy">
      <small>当前项目</small>
      <el-select
        :model-value="activeProCode"
        :loading="loading"
        :placeholder="currentName"
        popper-class="workspace-options"
        @change="changeWorkspace"
      >
        <el-option
          v-for="workspace in workspaces"
          :key="workspace.id"
          :value="workspace.proCode"
          :label="workspace.projectName"
        >
          <div class="workspace-option">
            <span>{{ workspace.projectName }}</span>
            <small>{{ workspace.proCode }}</small>
          </div>
        </el-option>
      </el-select>
    </div>
  </div>
</template>

<style scoped lang="scss">
.workspace-switcher {
  display: flex;
  gap: 9px;
  align-items: center;
  width: 244px;
  height: 38px;
  padding: 4px 10px;
  margin-right: 10px;
  background: #f4f8f8;
  border: 1px solid #dce8e8;
  border-radius: 7px;
  transition:
    background-color 160ms ease,
    border-color 160ms ease;
}

.workspace-switcher:hover {
  background: #eef5f5;
  border-color: #b9d0d0;
}

.workspace-switcher__dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  background: #167276;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgb(22 114 118 / 10%);
}

.workspace-switcher__copy {
  display: grid;
  flex: 1;
  min-width: 0;
}

.workspace-switcher__copy > small {
  height: 13px;
  font-size: 10px;
  line-height: 13px;
  color: #758787;
}

.workspace-switcher :deep(.el-select__wrapper) {
  min-height: 19px;
  padding: 0;
  background: transparent;
  box-shadow: none !important;
}

.workspace-switcher :deep(.el-select__selected-item) {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  font-weight: 650;
  color: #193c3f;
  white-space: nowrap;
}

@media (width <= 900px) {
  .workspace-switcher {
    width: 180px;
  }
}
</style>

<style lang="scss">
.workspace-options .workspace-option {
  display: flex;
  gap: 18px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.workspace-options .workspace-option small {
  font-size: 11px;
  color: #879494;
}
</style>
