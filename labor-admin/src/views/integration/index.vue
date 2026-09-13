<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import {
  getIntegrationConfigs,
  saveIntegrationConfig,
  testIntegrationConfig,
  type IntegrationConfig
} from "@/api/labor/integration";
import { message } from "@/utils/message";
import { hasPerms } from "@/utils/auth";
import { formatDateTime } from "@/utils/dateTime";
import {
  getWorkspaceSyncSetting,
  saveWorkspaceSyncSetting,
  type WorkspaceSyncSetting
} from "@/api/labor/workspaceSettings";

defineOptions({ name: "IntegrationConfig" });

const loading = ref(false);
const configs = ref<IntegrationConfig[]>([]);
const forms = reactive<Record<string, any>>({});
const syncSetting = reactive<WorkspaceSyncSetting>({
  id: 0,
  hikCollectionEnabled: true,
  pushEnabled: true,
  syncStarted: false,
  pushTime: "00:00",
  zoneId: "Asia/Shanghai",
  attendanceCompletionEnabled: false,
  attendanceCompletenessRate: 95
});

const descriptions: Record<string, { name: string; note: string }> = {
  HIKVISION: {
    name: "海康平台",
    note: "组织、人员与门禁考勤查询端口，配置仅作用于当前项目。"
  },
  LABOR_PLATFORM: {
    name: "劳务实名制平台",
    note: "项目、企业、施工队、人员及考勤推送端口，配置仅作用于当前项目。"
  }
};

async function load() {
  loading.value = true;
  try {
    const [configResult, settingResult] = await Promise.all([
      getIntegrationConfigs(),
      getWorkspaceSyncSetting()
    ]);
    configs.value = configResult.data;
    Object.assign(syncSetting, settingResult.data);
    configs.value.forEach(config => {
      forms[config.integrationType] = {
        baseUrl: config.baseUrl || "",
        appKey: config.appKey || "",
        appSecret: config.appSecret || "",
        userId: config.userId || "",
        enabled: config.enabled
      };
    });
  } catch (error: any) {
    fail(error, "接口配置加载失败");
  } finally {
    loading.value = false;
  }
}

async function saveSchedule() {
  try {
    const result = await saveWorkspaceSyncSetting(syncSetting);
    Object.assign(syncSetting, result.data);
    message("项目同步计划已保存", { type: "success" });
  } catch (error: any) {
    fail(error, "项目同步计划保存失败");
  }
}

async function save(config: IntegrationConfig) {
  try {
    const result = await saveIntegrationConfig(
      config.integrationType,
      forms[config.integrationType]
    );
    Object.assign(config, result.data);
    forms[config.integrationType].appSecret = result.data.appSecret || "";
    message("配置已保存并生成新版本", { type: "success" });
  } catch (error: any) {
    fail(error, "配置保存失败");
  }
}

async function test(config: IntegrationConfig) {
  try {
    const result = await testIntegrationConfig(config.integrationType);
    message(result.data.message, {
      type: result.data.success ? "success" : "error"
    });
    await load();
  } catch (error: any) {
    fail(error, "连通测试失败");
  }
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
        <h1>外部接口配置</h1>
        <p class="labor-page__desc">
          密钥加密保存，修改生成配置版本；真实联调前先确认网络和接口口径。
        </p>
      </div>
    </header>

    <section class="sync-setting">
      <div>
        <h2>项目同步计划</h2>
        <p>
          每小时40分采集、匹配并推送；11:40、23:40在对应半日推送前自动补全。
        </p>
      </div>
      <label>
        <span>海康自动采集</span>
        <el-switch v-model="syncSetting.hikCollectionEnabled" />
      </label>
      <label>
        <span>劳务平台自动推送</span>
        <el-switch v-model="syncSetting.pushEnabled" />
      </label>
      <label>
        <span>同步授权状态</span>
        <el-tag :type="syncSetting.syncStarted ? 'success' : 'info'">
          {{ syncSetting.syncStarted ? "已启动" : "待启动" }}
        </el-tag>
      </label>
      <label class="push-time">
        <span>自动推送时段</span>
        <el-tag type="info">每小时 40 分</el-tag>
      </label>
      <label>
        <span>考勤自动补全</span>
        <el-switch v-model="syncSetting.attendanceCompletionEnabled" />
      </label>
      <label class="completion-rate">
        <span>半日完整率基准</span>
        <el-input-number
          v-model="syncSetting.attendanceCompletenessRate"
          :disabled="!syncSetting.attendanceCompletionEnabled"
          :min="0"
          :max="100"
          :precision="2"
          :step="1"
          controls-position="right"
        />
        <small>%</small>
      </label>
      <el-button
        v-if="hasPerms('integration:edit')"
        type="primary"
        plain
        @click="saveSchedule"
      >
        保存计划
      </el-button>
    </section>

    <div class="integration-list">
      <section
        v-for="config in configs"
        :key="config.id"
        class="integration-item"
      >
        <div class="integration-meta">
          <span class="integration-index">0{{ config.id }}</span>
          <div>
            <h2>
              {{
                descriptions[config.integrationType]?.name ||
                config.integrationType
              }}
            </h2>
            <p>{{ descriptions[config.integrationType]?.note }}</p>
          </div>
          <div class="integration-state">
            <span class="labor-status">{{
              config.enabled ? "已启用" : "未启用"
            }}</span>
            <small>配置版本 {{ config.configVersion }}</small>
          </div>
        </div>

        <el-form
          v-if="forms[config.integrationType]"
          class="integration-form"
          label-position="top"
        >
          <el-form-item label="服务地址">
            <el-input
              v-model="forms[config.integrationType].baseUrl"
              placeholder="https://..."
            />
          </el-form-item>
          <el-form-item
            v-if="config.integrationType === 'HIKVISION'"
            label="应用标识"
          >
            <el-input v-model="forms[config.integrationType].appKey" />
          </el-form-item>
          <el-form-item
            v-if="config.integrationType === 'HIKVISION'"
            label="用户标识"
          >
            <el-input v-model="forms[config.integrationType].userId" />
          </el-form-item>
          <el-form-item
            v-if="config.integrationType === 'HIKVISION'"
            label="应用密钥"
          >
            <el-input v-model="forms[config.integrationType].appSecret" />
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="forms[config.integrationType].enabled" />
          </el-form-item>
        </el-form>

        <div class="integration-actions">
          <div>
            <span v-if="config.lastTestAt">
              上次测试：{{ formatDateTime(config.lastTestAt) }}
            </span>
            <small>{{ config.lastTestMessage || "尚未执行连通测试" }}</small>
          </div>
          <el-button v-if="hasPerms('integration:test')" @click="test(config)"
            >测试连接</el-button
          >
          <el-button
            v-if="hasPerms('integration:edit')"
            type="primary"
            @click="save(config)"
            >保存配置</el-button
          >
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.integration-list {
  margin-top: 8px;
}

.sync-setting {
  display: grid;
  grid-template-columns:
    minmax(220px, 1fr) repeat(3, auto)
    180px auto 180px auto;
  gap: 18px;
  align-items: center;
  padding: 22px 0;
  border-bottom: 1px solid var(--labor-line);
}

.sync-setting h2,
.sync-setting p {
  margin: 0;
}

.sync-setting h2 {
  font-size: 16px;
}

.sync-setting p {
  margin-top: 5px;
  font-size: 12px;
  color: var(--labor-muted);
}

.sync-setting label {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
}

.sync-setting .push-time {
  display: grid;
  gap: 5px;
}

.sync-setting .completion-rate {
  display: grid;
  grid-template-columns: auto 130px auto;
  gap: 8px;
}

.sync-setting .completion-rate .el-input-number {
  width: 130px;
}

.integration-item {
  padding: 30px 0 34px;
  border-bottom: 1px solid var(--labor-line);
}

.integration-meta {
  display: grid;
  grid-template-columns: 46px 1fr auto;
  gap: 24px;
  align-items: start;
}

.integration-index {
  font-size: 12px;
  color: #9eb0b3;
  letter-spacing: 0.12em;
}

.integration-meta h2 {
  margin: -4px 0 6px;
  font-size: 19px;
}

.integration-meta p {
  margin: 0;
  font-size: 13px;
  color: var(--labor-muted);
}

.integration-state {
  display: flex;
  flex-direction: column;
  gap: 5px;
  text-align: right;
}

.integration-state small {
  color: var(--labor-muted);
}

.integration-form {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr 120px;
  gap: 0 18px;
  padding-left: 70px;
  margin-top: 25px;
}

.integration-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
  padding-left: 70px;
  margin-top: 4px;
}

.integration-actions > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-right: auto;
  font-size: 12px;
  color: var(--labor-muted);
}

@media (width <= 1100px) {
  .sync-setting {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .integration-form {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (width <= 760px) {
  .integration-meta {
    grid-template-columns: 1fr;
  }

  .integration-index {
    display: none;
  }

  .integration-state {
    text-align: left;
  }

  .integration-form,
  .integration-actions {
    padding-left: 0;
  }

  .integration-form {
    grid-template-columns: 1fr;
  }

  .integration-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
