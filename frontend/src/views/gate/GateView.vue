<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { gateLogin, setAccessCode, fetchGateStatus } from '../../api/gate'
import { ApiError } from '../../api/http'

/**
 * 访问闸口登录页（2026-09-18 部署闸门·前端侧）——简历展示站点的对话入口。
 *
 * <p>路由守卫在闸口开启且本地无口令时拦到本页（携带 ?redirect）。录入口令→
 * {@link gateLogin} 后端校验（不消耗额度）→存 localStorage→回跳对话页；此后对话请求经
 * chat.ts 注入 X-Access-Code 头。①话术短路：录错口令由后端返 401 话术，ElMessage 展示。
 * 口令在 Nacos（agentdemo-gate.json）可随时轮换——旧口令在下轮对话 401 后被引导回本页。
 */
const route = useRoute()
const router = useRouter()

const code = ref('')
const submitting = ref(false)

async function submit(): Promise<void> {
  const t = code.value.trim()
  if (!t || submitting.value) return
  submitting.value = true
  try {
    const res = await gateLogin(t)
    setAccessCode(t)
    await fetchGateStatus(true) // 状态缓存失效重取（闸口可能刚被 Nacos 关闭）
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/chat'
    await router.replace(target)
    ElMessage.success(`验证通过，今日还可体验 ${res.remaining} 轮`)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '验证失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="gate">
    <div class="gate__card surface">
      <div class="gate__head">
        <span class="gate__badge mono">访问验证</span>
        <h2 class="gate__title">电商客服体验站</h2>
      </div>
      <p class="gate__sub">本站为受限体验，请输入访问口令后开始对话。每日体验轮次有限，用完次日自动恢复。</p>

      <div class="gate__field">
        <el-input
          v-model="code"
          placeholder="访问口令"
          class="gate__input"
          show-password
          @keyup.enter="submit"
        />
        <el-button type="primary" :loading="submitting" @click="submit">进入</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gate {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: var(--space-6);
}
.gate__card {
  width: 100%;
  max-width: 440px;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.gate__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.gate__badge {
  font-size: 11px;
  color: var(--amber);
  border: 1px solid var(--amber-dim);
  border-radius: 3px;
  padding: 2px 6px;
}
.gate__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.gate__sub {
  margin: 0;
  font-size: 12px;
  color: var(--ink-300);
  line-height: 1.6;
}
.gate__field {
  display: flex;
  gap: var(--space-2);
}
.gate__input {
  flex: 1;
}
</style>
