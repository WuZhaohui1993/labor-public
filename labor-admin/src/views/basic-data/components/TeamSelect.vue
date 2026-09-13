<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { getTeams, type TeamItem } from "@/api/labor/masterData";

const props = defineProps<{
  proCode?: string;
  disabled?: boolean;
  placeholder?: string;
}>();
const emit = defineEmits<{ change: [value: string] }>();
const model = defineModel<string>({ default: "" });
const loading = ref(false);
const options = ref<TeamItem[]>([]);

async function load(search = "") {
  if (!props.proCode) {
    options.value = [];
    return;
  }
  loading.value = true;
  try {
    const requests = [
      getTeams({
        page: 0,
        size: 100,
        proCode: props.proCode,
        search
      })
    ];
    if (!search && model.value) {
      requests.push(
        getTeams({
          page: 0,
          size: 100,
          proCode: props.proCode,
          search: model.value
        })
      );
    }
    const results = await Promise.all(requests);
    options.value = Array.from(
      new Map(
        results
          .flatMap(result => result.data.items)
          .map(item => [item.id, item])
      ).values()
    );
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.proCode,
  () => load()
);
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
    :disabled="disabled || !proCode"
    :loading="loading"
    :placeholder="placeholder || (proCode ? '请选择施工队' : '请先选择项目')"
    :remote-method="load"
    @change="value => emit('change', value || '')"
  >
    <el-option
      v-for="item in options"
      :key="item.id"
      :label="`${item.teamName}（${item.teamId}）`"
      :value="item.teamId"
    />
  </el-select>
</template>
