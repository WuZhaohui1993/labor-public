<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { getPersons, type PersonItem } from "@/api/labor/masterData";

const props = defineProps<{
  proCode?: string;
  disabled?: boolean;
  placeholder?: string;
}>();
const model = defineModel<number>();
const loading = ref(false);
const options = ref<PersonItem[]>([]);

async function load(search = "") {
  if (!props.proCode) {
    options.value = [];
    return;
  }
  loading.value = true;
  try {
    options.value = (
      await getPersons({
        page: 0,
        size: 100,
        proCode: props.proCode,
        search
      })
    ).data.items;
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.proCode,
  () => {
    model.value = undefined;
    load();
  }
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
    :placeholder="placeholder || (proCode ? '请选择实名人员' : '请先选择项目')"
    :remote-method="load"
  >
    <el-option
      v-for="item in options"
      :key="item.id"
      :label="`${item.name}（${item.idcardNumber}）`"
      :value="item.id"
    />
  </el-select>
</template>
