<script setup lang="ts">
import { onMounted, ref } from "vue";
import { getProjects, type ProjectItem } from "@/api/labor/masterData";

defineProps<{ disabled?: boolean; placeholder?: string }>();
const emit = defineEmits<{ change: [value: string] }>();
const model = defineModel<string>({ default: "" });
const loading = ref(false);
const options = ref<ProjectItem[]>([]);

async function load(search = "") {
  loading.value = true;
  try {
    options.value = (
      await getProjects({ page: 0, size: 100, search: search || model.value })
    ).data.items;
  } finally {
    loading.value = false;
  }
}

onMounted(() => load());
</script>

<template>
  <el-select
    v-model="model"
    clearable
    filterable
    remote
    reserve-keyword
    class="w-full"
    :disabled="disabled"
    :loading="loading"
    :placeholder="placeholder || '请选择项目'"
    :remote-method="load"
    @change="value => emit('change', value || '')"
  >
    <el-option
      v-for="item in options"
      :key="item.id"
      :label="`${item.projectName}（${item.proCode}）`"
      :value="item.proCode"
    />
  </el-select>
</template>
