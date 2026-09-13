<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { message } from "@/utils/message";
import { useUserStoreHook } from "@/store/modules/user";
import { initRouter } from "@/router/utils";
import { useWorkspaceStoreHook } from "@/store/modules/workspace";
import { getCaptcha } from "@/api/user";

defineOptions({ name: "Login" });

const router = useRouter();
const formRef = ref<FormInstance>();
const loading = ref(false);
const captchaLoading = ref(false);
const isDev = import.meta.env.DEV;
const form = reactive({
  username: "admin",
  password: "",
  captchaId: "",
  captchaCode: "",
  captchaImage: ""
});
const rules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
  captchaCode: [{ required: true, message: "请输入验证码", trigger: "blur" }]
};

async function refreshCaptcha() {
  captchaLoading.value = true;
  try {
    const result = await getCaptcha();
    form.captchaId = result.data.captchaId;
    form.captchaImage = result.data.image;
    form.captchaCode = "";
  } catch (error: any) {
    message(error?.response?.data?.message || "验证码加载失败，请重试", {
      type: "error"
    });
  } finally {
    captchaLoading.value = false;
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  loading.value = true;
  try {
    await useUserStoreHook().loginByUsername({
      username: form.username,
      password: form.password,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode
    });
    await useWorkspaceStoreHook().load(true);
    await initRouter();
    await router.push("/welcome");
    message("登录成功", { type: "success" });
  } catch (error: any) {
    message(error?.response?.data?.message || error || "登录失败", {
      type: "error"
    });
    await refreshCaptcha();
  } finally {
    loading.value = false;
  }
}

onMounted(refreshCaptcha);
</script>

<template>
  <main class="labor-login">
    <section class="labor-login__context">
      <div class="labor-login__brand">
        <span class="labor-login__mark">劳</span>
        <span>劳务实名制</span>
      </div>
      <div class="labor-login__copy">
        <span class="labor-login__accent" />
        <h1>数据同步<br />管理系统</h1>
      </div>
    </section>

    <section class="labor-login__panel">
      <div class="labor-login__form">
        <div class="labor-login__heading">
          <h2>登录系统</h2>
          <p>请输入账号和密码。</p>
        </div>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          @keyup.enter="submit"
        >
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="密码"
            />
          </el-form-item>
          <el-form-item prop="captchaCode">
            <div class="labor-login__captcha">
              <el-input
                v-model="form.captchaCode"
                maxlength="4"
                autocomplete="off"
                placeholder="验证码"
              />
              <button
                type="button"
                class="labor-login__captcha-image"
                :disabled="captchaLoading"
                aria-label="刷新验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="form.captchaImage"
                  :src="form.captchaImage"
                  alt="验证码"
                />
                <span v-else>加载中</span>
              </button>
            </div>
          </el-form-item>
          <el-button
            class="labor-login__submit"
            type="primary"
            :loading="loading"
            @click="submit"
          >
            进入管理中心
          </el-button>
        </el-form>
        <p v-if="isDev" class="labor-login__dev-note">
          本地初始账号由环境变量配置
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
.labor-login {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(420px, 0.95fr);
  min-height: 100vh;
  color: #f5fbfb;
  background: #0e272c;
}

.labor-login__context {
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr;
  padding: 48px 58px;
  overflow: hidden;
  background:
    linear-gradient(rgb(14 39 44 / 15%), rgb(14 39 44 / 90%)),
    repeating-linear-gradient(
      90deg,
      transparent 0,
      transparent 79px,
      rgb(255 255 255 / 4.5%) 80px
    ),
    repeating-linear-gradient(
      0deg,
      transparent 0,
      transparent 79px,
      rgb(255 255 255 / 3.5%) 80px
    ),
    radial-gradient(circle at 70% 25%, #1c7278 0, #123b41 32%, #0e272c 72%);
}

.labor-login__context::after {
  position: absolute;
  top: 17%;
  right: -18%;
  width: 43vw;
  height: 43vw;
  content: "";
  border: 1px solid rgb(141 218 218 / 22%);
  border-radius: 50%;
  box-shadow:
    0 0 0 70px rgb(141 218 218 / 3.5%),
    0 0 0 140px rgb(141 218 218 / 2.5%);
}

.labor-login__brand {
  z-index: 1;
  display: flex;
  gap: 14px;
  align-items: center;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0.04em;
}

.labor-login__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  font-size: 16px;
  font-weight: 700;
  color: #0e272c;
  background: #92d8d6;
  border-radius: 9px;
}

.labor-login__copy {
  z-index: 1;
  align-self: center;
  max-width: 620px;
  padding-bottom: 72px;
}

.labor-login__accent {
  display: block;
  width: 46px;
  height: 3px;
  background: #92d8d6;
  border-radius: 2px;
}

.labor-login__copy h1 {
  margin: 24px 0 0;
  font-size: clamp(40px, 4.2vw, 58px);
  font-weight: 580;
  line-height: 1.22;
  letter-spacing: -0.03em;
}

.labor-login__panel {
  display: grid;
  place-items: center;
  padding: 48px;
  color: #152b31;
  background: #f6f9f9;
}

.labor-login__form {
  width: min(100%, 390px);
  animation: panel-in 420ms ease-out both;
}

.labor-login__heading {
  margin-bottom: 38px;
}

.labor-login__heading h2 {
  margin: 0 0 10px;
  font-size: 32px;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.labor-login__heading p {
  margin: 0;
  font-size: 14px;
  color: #718186;
}

.labor-login__submit {
  --el-button-bg-color: #146c73;
  --el-button-border-color: #146c73;
  --el-button-hover-bg-color: #195d63;
  --el-button-hover-border-color: #195d63;
  --el-button-active-bg-color: #104f55;
  --el-button-active-border-color: #104f55;

  width: 100%;
  margin-top: 8px;
  box-shadow: 0 10px 24px rgb(20 108 115 / 18%);
}

.labor-login__captcha {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 144px;
  gap: 10px;
  width: 100%;
}

.labor-login__captcha-image {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  background: #eef7f7;
  border: 1px solid #c5d8d9;
  border-radius: 6px;
}

.labor-login__captcha-image:disabled {
  cursor: wait;
  opacity: 0.65;
}

.labor-login__captcha-image img {
  display: block;
  width: 144px;
  height: 48px;
}

.labor-login__dev-note {
  margin-top: 20px;
  font-size: 12px;
  color: #879397;
  text-align: center;
}

@keyframes panel-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (width <= 920px) {
  .labor-login {
    grid-template-columns: 1fr;
  }

  .labor-login__context {
    display: none;
  }

  .labor-login__panel {
    min-height: 100vh;
    padding: 28px;
  }
}
</style>
