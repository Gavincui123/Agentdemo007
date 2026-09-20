<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { setAdminToken, clearAdminToken, hasAdminToken, getAdminToken } from '../../api/auth'

/**
 * 令牌录入视图（Phase 19·T103 前端侧）——管理台/可观测鉴权闸口。
 *
 * <p>路由守卫在无令牌访问 {@code /admin} {@code /obs} 时重定向至此（携带 {@code ?redirect}）。
 * 录入令牌→{@link setAdminToken}（localStorage）→回跳原目标。后续出站请求经 {@code http}
 * 请求拦截器注入 {@code X-Admin-Token}，后端 {@code AdminAuthInterceptor} 放行。
 *
 * <p>①话术短路：录入错误令牌不会被前端拦截——原样出站，后端返 {@code code=401}（HTTP 200），
 * 经拦截器抛 {@code ApiError}（话术「未授权」），此处 ElMessage 展示，不暴露技术码。
 */
const route = useRoute()
const router = useRouter()

const token = ref('')
const submitting = ref(false)
const authed = ref(false)

onMounted(() => {
  authed.value = hasAdminToken()
  if (authed.value) token.value = getAdminToken() ?? ''
})

function submit(): void {
  const t = token.value.trim()
  if (!t) return
  submitting.value = true
  setAdminToken(t)
  const redirect = route.query.redirect
  const target = typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/admin'
  router.replace(target).then(() => {
    ElMessage.success('令牌已保存')
  })
}

function logout(): void {
  clearAdminToken()
  authed.value = false
  token.value = ''
  ElMessage.success('已退出，令牌已清除')
}
</script>

<template>
  <div class="auth">
    <div class="auth__card surface">
      <div class="auth__head">
        <span class="auth__badge mono">Phase 19</span>
        <h2 class="auth__title">管理台鉴权</h2>
      </div>
      <p class="auth__sub">输入管理令牌以访问管理台与可观测台。令牌存于本地，逐请求注入 X-Admin-Token 头。</p>

      <div class="auth__field">
        <el-input
          v-model="token"
          placeholder="管理令牌（与后端 agentdemo.admin.token 配置一致）"
          class="auth__input"
          show-password
          @keyup.enter="submit"
        />
        <el-button type="primary" :loading="submitting" @click="submit">进入</el-button>
      </div>

      <div v-if="authed" class="auth__authed live-note">
        <span>当前已持令牌。</span>
        <el-button link size="small" type="danger" @click="logout">清除令牌</el-button>
      </div>

      <p class="auth__hint">令牌须与后端 <code class="mono">agentdemo.admin.token</code> 配置一致：dev 默认 <code class="mono">dev-admin-token</code>；若经 Nacos / ADMIN_TOKEN 覆盖，以实际生效配置为准。</p>
    </div>
  </div>
</template>

<style scoped>
.auth {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: var(--space-6);
}
.auth__card {
  width: 100%;
  max-width: 440px;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.auth__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.auth__badge {
  font-size: 11px;
  color: var(--amber);
  border: 1px solid var(--amber-dim);
  border-radius: 3px;
  padding: 2px 6px;
}
.auth__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.auth__sub {
  margin: 0;
  font-size: 12px;
  color: var(--ink-300);
  line-height: 1.6;
}
.auth__field {
  display: flex;
  gap: var(--space-2);
}
.auth__input {
  flex: 1;
}
.auth__authed {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--signal);
}
.auth__hint {
  margin: 0;
  font-size: 11px;
  color: var(--ink-300);
}
.auth__hint code {
  background: var(--ink-900);
  padding: 1px 5px;
  border-radius: 3px;
}
</style>
