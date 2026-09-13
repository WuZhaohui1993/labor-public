<script setup lang="ts">
import { onMounted, ref } from "vue";
import { deviceDetection } from "@pureadmin/utils";
import { message } from "@/utils/message";
import { getMinePreferences, updateMinePreferences } from "@/api/user";

defineOptions({
  name: "Preferences"
});

type PreferenceItem = {
  key: string;
  title: string;
  illustrate: string;
  checked: boolean;
};

const loading = ref(true);
const list = ref<PreferenceItem[]>([
  {
    key: "userMessage",
    title: "用户消息",
    illustrate: "其他用户的消息将以站内信的形式通知",
    checked: true
  },
  {
    key: "systemMessage",
    title: "系统消息",
    illustrate: "系统消息将以站内信的形式通知",
    checked: true
  },
  {
    key: "todo",
    title: "待办任务",
    illustrate: "待办任务将以站内信的形式通知",
    checked: true
  }
]);

async function onChange(value: boolean, item: PreferenceItem) {
  const previous = !value;
  try {
    const result = await updateMinePreferences(
      Object.fromEntries(
        list.value.map(current => [current.key, current.checked])
      )
    );
    if (result.code !== "OK") throw new Error(result.message);
    message(`${item.title}设置成功`, { type: "success" });
  } catch (error) {
    item.checked = previous;
    message(error instanceof Error ? error.message : `${item.title}设置失败`, {
      type: "error"
    });
  }
}

onMounted(async () => {
  try {
    const result = await getMinePreferences();
    if (result.code === "OK") {
      list.value.forEach(item => {
        if (result.data[item.key] !== undefined)
          item.checked = result.data[item.key];
      });
    }
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">偏好设置</h3>
    <el-skeleton v-if="loading" :rows="3" animated />
    <template v-else>
      <div v-for="item in list" :key="item.key">
        <div class="flex items-center">
          <div class="flex-1">
            <p>{{ item.title }}</p>
            <p class="wp-4">
              <el-text class="mx-1" type="info">{{ item.illustrate }}</el-text>
            </p>
          </div>
          <el-switch
            v-model="item.checked"
            inline-prompt
            active-text="是"
            inactive-text="否"
            @change="value => onChange(Boolean(value), item)"
          />
        </div>
        <el-divider />
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.el-divider--horizontal {
  border-top: 0.1px var(--el-border-color) var(--el-border-style);
}
</style>
