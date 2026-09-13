<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  getPermissions,
  getRoles,
  getUsers,
  saveRolePermissions,
  saveUser,
  unlockUser,
  type PermissionItem,
  type RoleItem,
  type UserItem
} from "@/api/labor/system";
import { message } from "@/utils/message";
import { getWorkspaces, type WorkspaceItem } from "@/api/labor/workspaces";

defineOptions({ name: "SystemUsers" });

const loading = ref(false);
const users = ref<UserItem[]>([]);
const roles = ref<RoleItem[]>([]);
const permissions = ref<PermissionItem[]>([]);
const workspaces = ref<WorkspaceItem[]>([]);
const dialog = ref(false);
const roleDrawer = ref(false);
const editingRole = ref<RoleItem>();
const selectedPermissions = ref<string[]>([]);
const form = reactive<any>({
  id: undefined,
  username: "",
  displayName: "",
  password: "",
  enabled: true,
  roles: [],
  projectRoles: []
});

const groupedPermissions = computed(() => {
  const groups: Record<string, PermissionItem[]> = {};
  permissions.value.forEach(permission =>
    (groups[permission.group] ||= []).push(permission)
  );
  return groups;
});

async function load() {
  loading.value = true;
  try {
    [users.value, roles.value, permissions.value, workspaces.value] =
      await Promise.all([
        getUsers().then(result => result.data),
        getRoles().then(result => result.data),
        getPermissions().then(result => result.data),
        getWorkspaces().then(result => result.data)
      ]);
  } catch (error: any) {
    fail(error, "用户权限数据加载失败");
  } finally {
    loading.value = false;
  }
}

function editUser(user?: UserItem) {
  const assignments = workspaces.value.map(workspace => {
    const existing = user?.projectRoles?.find(
      item => item.proCode === workspace.proCode
    );
    return {
      projectId: workspace.id,
      proCode: workspace.proCode,
      projectName: workspace.projectName,
      roles: [...(existing?.roles || [])]
    };
  });
  Object.assign(
    form,
    user
      ? {
          ...user,
          password: "",
          roles: [...user.roles],
          projectRoles: assignments
        }
      : {
          id: undefined,
          username: "",
          displayName: "",
          password: "",
          enabled: true,
          roles: [],
          projectRoles: assignments
        }
  );
  dialog.value = true;
}

async function submitUser() {
  const assignedRoles = form.projectRoles.flatMap(item => item.roles);
  const roleCodes = Array.from(new Set([...form.roles, ...assignedRoles]));
  if (
    !form.username ||
    !form.displayName ||
    !roleCodes.length ||
    (!form.id && form.password.length < 8)
  ) {
    message("请完整填写用户信息，新用户密码至少8位", { type: "warning" });
    return;
  }
  try {
    await saveUser({ ...form, roles: roleCodes });
    message("用户信息已保存", { type: "success" });
    dialog.value = false;
    await load();
  } catch (error: any) {
    fail(error, "用户保存失败");
  }
}

function editRole(role: RoleItem) {
  editingRole.value = role;
  selectedPermissions.value = [...role.permissions];
  roleDrawer.value = true;
}

async function submitRole() {
  if (!editingRole.value) return;
  try {
    await saveRolePermissions(editingRole.value.id, selectedPermissions.value);
    message("角色权限已更新", { type: "success" });
    roleDrawer.value = false;
    await load();
  } catch (error: any) {
    fail(error, "角色权限保存失败");
  }
}

async function submitUnlock(user: UserItem) {
  try {
    await unlockUser(user.id);
    message(`${user.username} 已解锁`, { type: "success" });
    await load();
  } catch (error: any) {
    fail(error, "账号解锁失败");
  }
}

function securityStatus(user: UserItem) {
  if (user.lockedUntil)
    return `锁定至 ${new Date(user.lockedUntil).toLocaleString()}`;
  if (user.failedLoginAttempts > 0)
    return `失败 ${user.failedLoginAttempts} 次`;
  return "正常";
}

function fail(error: any, fallback: string) {
  message(error?.response?.data?.message || fallback, { type: "error" });
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="labor-page">
    <header class="labor-page__header">
      <div>
        <h1>用户与角色</h1>
        <p class="labor-page__desc">页面访问和按钮操作均以后端权限代码为准。</p>
      </div>
      <el-button type="primary" @click="editUser()">新增用户</el-button>
    </header>

    <section class="system-section">
      <div class="section-title">
        <h2>系统用户</h2>
        <span>{{ users.length }} 个账号</span>
      </div>
      <div class="labor-table-wrap">
        <el-table :data="users">
          <el-table-column prop="username" label="用户名" width="180" />
          <el-table-column prop="displayName" label="姓名" width="180" />
          <el-table-column label="角色" min-width="320">
            <template #default="{ row }">
              <el-tag
                v-for="role in row.roles"
                :key="role"
                class="role-tag"
                effect="plain"
                >{{ role }}</el-tag
              >
            </template>
          </el-table-column>
          <el-table-column label="项目范围" min-width="220">
            <template #default="{ row }">
              {{ row.projectRoles.length }} 个项目
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"
              ><span class="labor-status">{{
                row.enabled ? "启用" : "停用"
              }}</span></template
            >
          </el-table-column>
          <el-table-column label="登录安全" min-width="180">
            <template #default="{ row }">
              <span
                :class="
                  row.lockedUntil
                    ? 'labor-status status-failed'
                    : 'labor-status'
                "
              >
                {{ securityStatus(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }"
              ><el-button link type="primary" @click="editUser(row)"
                >编辑</el-button
              >
              <el-button
                v-if="row.lockedUntil || row.failedLoginAttempts > 0"
                link
                type="warning"
                @click="submitUnlock(row)"
                >解锁</el-button
              ></template
            >
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="system-section">
      <div class="section-title">
        <h2>内置角色</h2>
        <span>按职责隔离数据、复核、运维与审计权限</span>
      </div>
      <div class="role-list">
        <button v-for="role in roles" :key="role.id" @click="editRole(role)">
          <span>{{ role.name }}</span>
          <small>{{ role.code }}</small>
          <b>{{ role.permissions.length }} 项权限</b>
        </button>
      </div>
    </section>

    <el-dialog
      v-model="dialog"
      :title="form.id ? '编辑用户' : '新增用户'"
      width="760px"
    >
      <el-form label-position="top">
        <el-form-item label="用户名"
          ><el-input v-model="form.username"
        /></el-form-item>
        <el-form-item label="显示名称"
          ><el-input v-model="form.displayName"
        /></el-form-item>
        <el-form-item :label="form.id ? '重置密码（留空不修改）' : '初始密码'">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="系统级角色">
          <el-select v-model="form.roles" multiple class="w-full">
            <el-option
              v-for="role in roles"
              :key="role.code"
              :label="role.name"
              :value="role.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="项目角色">
          <div class="project-role-list">
            <div
              v-for="item in form.projectRoles"
              :key="item.proCode"
              class="project-role-row"
            >
              <div>
                <strong>{{ item.projectName }}</strong>
                <small>{{ item.proCode }}</small>
              </div>
              <el-select
                v-model="item.roles"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="不分配则不可访问"
              >
                <el-option
                  v-for="role in roles.filter(
                    role => role.code !== 'SYSTEM_ADMIN'
                  )"
                  :key="role.code"
                  :label="role.name"
                  :value="role.code"
                />
              </el-select>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="启用"
          ><el-switch v-model="form.enabled"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" @click="submitUser"
          >保存</el-button
        ></template
      >
    </el-dialog>

    <el-drawer v-model="roleDrawer" size="520px" title="角色权限">
      <template v-if="editingRole">
        <div class="role-subject">
          <span>{{ editingRole.code }}</span
          ><strong>{{ editingRole.name }}</strong>
        </div>
        <el-checkbox-group
          v-model="selectedPermissions"
          class="permission-groups"
        >
          <section v-for="(items, group) in groupedPermissions" :key="group">
            <h3>{{ group }}</h3>
            <el-checkbox
              v-for="permission in items"
              :key="permission.code"
              :value="permission.code"
            >
              {{ permission.name }} <small>{{ permission.code }}</small>
            </el-checkbox>
          </section>
        </el-checkbox-group>
        <div class="drawer-save">
          <el-button type="primary" @click="submitRole">保存角色权限</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.system-section {
  margin-top: 30px;
}

.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-title h2 {
  margin: 0;
  font-size: 17px;
}

.section-title span {
  font-size: 12px;
  color: var(--labor-muted);
}

.role-tag {
  margin-right: 6px;
}

.project-role-list {
  width: 100%;
  border-top: 1px solid var(--labor-line);
}

.project-role-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(280px, 1.5fr);
  gap: 20px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--labor-line);
}

.project-role-row > div {
  display: flex;
  flex-direction: column;
}

.project-role-row small {
  margin-top: 3px;
  color: var(--labor-muted);
}

.role-list {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  border-top: 1px solid var(--labor-line);
}

.role-list button {
  display: flex;
  flex-direction: column;
  min-height: 118px;
  padding: 22px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-right: 1px solid var(--labor-line);
  border-bottom: 1px solid var(--labor-line);
}

.role-list button:hover {
  background: #eef5f5;
}

.role-list span {
  font-weight: 650;
}

.role-list small {
  margin-top: 5px;
  color: var(--labor-muted);
}

.role-list b {
  margin-top: auto;
  font-size: 12px;
  color: var(--labor-accent);
}

.role-subject {
  display: flex;
  flex-direction: column;
  padding: 16px;
  margin-bottom: 24px;
  background: var(--labor-accent-soft);
}

.role-subject span {
  font-size: 11px;
  color: var(--labor-muted);
}

.role-subject strong {
  margin-top: 4px;
  font-size: 18px;
}

.permission-groups section {
  padding: 16px 0;
  border-bottom: 1px solid var(--labor-line);
}

.permission-groups h3 {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--labor-muted);
  text-transform: uppercase;
}

.permission-groups .el-checkbox {
  display: flex;
  margin: 8px 0;
}

.permission-groups small {
  margin-left: 6px;
  color: #98a5a8;
}

.drawer-save {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  padding: 18px 0;
  background: #fff;
}

@media (width <= 1000px) {
  .role-list {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
