<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { deviceDetection } from "@pureadmin/utils";
import { message } from "@/utils/message";
import {
  getMine,
  type UserInfo,
  updateMinePassword,
  updateMineProfile,
  updateMineSecurityQuestion
} from "@/api/user";
import { useUserStoreHook } from "@/store/modules/user";

defineOptions({
  name: "AccountManagement"
});

const loading = ref(true);
const passwordVisible = ref(false);
const securityVisible = ref(false);
const contactVisible = ref(false);
const contactType = ref<"phone" | "email">("phone");
const userInfo = reactive<UserInfo>({
  avatar: "",
  username: "",
  nickname: "",
  email: "",
  phone: "",
  description: "",
  securityQuestionSet: false
});
const passwordForm = reactive({
  currentPassword: "",
  newPassword: "",
  confirmPassword: ""
});
const securityForm = reactive({
  question: "",
  answer: "",
  currentPassword: ""
});
const contactValue = ref("");

const list = computed(() => [
  {
    key: "password",
    title: "账户密码",
    illustrate: "定期修改密码可以提高账户安全性",
    button: "修改"
  },
  {
    key: "phone",
    title: "密保手机",
    illustrate: userInfo.phone
      ? `已绑定手机：${userInfo.phone}`
      : "暂未绑定手机",
    button: userInfo.phone ? "修改" : "绑定"
  },
  {
    key: "question",
    title: "密保问题",
    illustrate: userInfo.securityQuestionSet
      ? "已设置密保问题"
      : "暂未设置密保问题",
    button: userInfo.securityQuestionSet ? "修改" : "设置"
  },
  {
    key: "email",
    title: "备用邮箱",
    illustrate: userInfo.email
      ? `已绑定邮箱：${userInfo.email}`
      : "暂未绑定备用邮箱",
    button: userInfo.email ? "修改" : "绑定"
  }
]);

function onClick(item: (typeof list.value)[number]) {
  if (item.key === "password") {
    Object.assign(passwordForm, {
      currentPassword: "",
      newPassword: "",
      confirmPassword: ""
    });
    passwordVisible.value = true;
    return;
  }
  if (item.key === "question") {
    Object.assign(securityForm, {
      question: "",
      answer: "",
      currentPassword: ""
    });
    securityVisible.value = true;
    return;
  }
  contactType.value = item.key as "phone" | "email";
  contactValue.value = userInfo[contactType.value] || "";
  contactVisible.value = true;
}

async function submitPassword() {
  if (!passwordForm.currentPassword || passwordForm.newPassword.length < 8) {
    message("请输入当前密码和至少8位的新密码", { type: "warning" });
    return;
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    message("两次输入的新密码不一致", { type: "warning" });
    return;
  }
  try {
    const result = await updateMinePassword(passwordForm);
    if (result.code !== "OK") throw new Error(result.message);
    passwordVisible.value = false;
    message("密码修改成功，请重新登录", { type: "success" });
    window.setTimeout(() => useUserStoreHook().logOut(), 500);
  } catch (error) {
    message(error instanceof Error ? error.message : "密码修改失败", {
      type: "error"
    });
  }
}

async function submitContact() {
  const value = contactValue.value.trim();
  if (contactType.value === "phone" && !/^1[3-9]\d{9}$/.test(value)) {
    message("请输入正确的手机号码", { type: "warning" });
    return;
  }
  if (
    contactType.value === "email" &&
    !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value)
  ) {
    message("请输入正确的邮箱地址", { type: "warning" });
    return;
  }
  try {
    const profile = await updateMineProfile({
      nickname: userInfo.nickname,
      email: contactType.value === "email" ? value : userInfo.email,
      phone: contactType.value === "phone" ? value : userInfo.phone,
      description: userInfo.description
    });
    if (profile.code !== "OK") throw new Error(profile.message);
    Object.assign(userInfo, profile.data);
    contactVisible.value = false;
    message("绑定信息更新成功", { type: "success" });
  } catch (error) {
    message(error instanceof Error ? error.message : "绑定信息更新失败", {
      type: "error"
    });
  }
}

async function submitSecurityQuestion() {
  if (
    !securityForm.question.trim() ||
    !securityForm.answer.trim() ||
    !securityForm.currentPassword
  ) {
    message("请完整填写密保问题、答案和当前密码", { type: "warning" });
    return;
  }
  try {
    const result = await updateMineSecurityQuestion(securityForm);
    if (result.code !== "OK") throw new Error(result.message);
    Object.assign(userInfo, result.data);
    securityVisible.value = false;
    message("密保问题设置成功", { type: "success" });
  } catch (error) {
    message(error instanceof Error ? error.message : "密保问题设置失败", {
      type: "error"
    });
  }
}

onMounted(async () => {
  try {
    const result = await getMine();
    if (result.code === "OK") Object.assign(userInfo, result.data);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">账户管理</h3>
    <el-skeleton v-if="loading" :rows="5" animated />
    <template v-else>
      <div v-for="item in list" :key="item.key">
        <div class="flex items-center">
          <div class="flex-1">
            <p>{{ item.title }}</p>
            <el-text class="mx-1" type="info">{{ item.illustrate }}</el-text>
          </div>
          <el-button type="primary" text @click="onClick(item)">
            {{ item.button }}
          </el-button>
        </div>
        <el-divider />
      </div>
    </template>

    <el-dialog v-model="passwordVisible" title="修改账户密码" width="460px">
      <el-form label-position="top">
        <el-form-item label="当前密码">
          <el-input
            v-model="passwordForm.currentPassword"
            type="password"
            show-password
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPassword">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="contactVisible"
      :title="contactType === 'phone' ? '绑定密保手机' : '绑定备用邮箱'"
      width="460px"
    >
      <el-form label-position="top">
        <el-form-item
          :label="contactType === 'phone' ? '手机号码' : '邮箱地址'"
        >
          <el-input v-model="contactValue" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactVisible = false">取消</el-button>
        <el-button type="primary" @click="submitContact">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="securityVisible" title="设置密保问题" width="460px">
      <el-form label-position="top">
        <el-form-item label="密保问题">
          <el-input
            v-model="securityForm.question"
            placeholder="例如：您的出生地是？"
          />
        </el-form-item>
        <el-form-item label="密保答案">
          <el-input v-model="securityForm.answer" />
        </el-form-item>
        <el-form-item label="当前密码">
          <el-input
            v-model="securityForm.currentPassword"
            type="password"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="securityVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSecurityQuestion"
          >保存</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.el-divider--horizontal {
  border-top: 0.1px var(--el-border-color) var(--el-border-style);
}
</style>
