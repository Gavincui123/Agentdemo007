<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, RouterLink } from 'vue-router'
import PipelineSpine from './components/PipelineSpine.vue'
import { useChatStore } from './stores/chat'
import { stepToLayer } from './utils/spineLayers'

/**
 * 应用外壳（Phase 16）——品牌 + 七层流水线脊签名 + 顶栏导航 + 路由出口。
 *
 * <p>脊为架构真实流的实时联动（P0 可观测）：流式轮经 step_started/step_finished 事件驱动——
 * 当前步骤映射到对应层点亮（stepToLayer），出现降级/短路整脊转琥珀；空闲/同步轮静态。
 * 路由出口承接四模块视图。
 */
const chat = useChatStore()

/**
 * 脊当前点亮层：仅流式进行中（currentStep 非空）有效。
 * 子图节点（如售后 DAG 的 query_user/query_order）不在七层映射内——从步骤轨迹末尾回溯，
 * 沿用最近一个已映射步骤的层（如 WorkflowExecutionStep→能力层），脊不中途熄灭。
 */
const spineActive = computed<number | null>(() => {
  if (!chat.currentStep) return null
  for (let i = chat.steps.length - 1; i >= 0; i--) {
    const layer = stepToLayer(chat.steps[i].name)
    if (layer !== null) return layer
  }
  return null
})

const NAV = [
  { to: '/chat', label: '对话' },
  { to: '/admin', label: '管理台' },
  { to: '/kb', label: '知识库' },
  { to: '/eval', label: '评测' },
  { to: '/obs', label: '可观测' },
] as const
</script>

<template>
  <div class="shell">
    <header class="shell__header">
      <div class="shell__brand">
        <span class="shell__brand-name">Agentdemo007</span>
        <span class="shell__brand-suffix">控制台</span>
      </div>
      <div class="shell__spine">
        <PipelineSpine :active="spineActive" :degraded="chat.spineDegraded" />
      </div>
      <nav class="shell__nav">
        <RouterLink
          v-for="item in NAV"
          :key="item.to"
          :to="item.to"
          class="shell__nav-link"
          active-class="shell__nav-link--active"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </header>

    <main class="shell__main">
      <RouterView v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.shell__header {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  padding: 0 var(--space-5);
  height: 52px;
  border-bottom: 1px solid var(--ink-600);
  background: var(--ink-900);
  flex-shrink: 0;
}

.shell__brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.shell__brand-name {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-100);
  letter-spacing: 0.02em;
}
.shell__brand-suffix {
  font-size: 12px;
  color: var(--ink-300);
}

.shell__spine {
  margin-left: var(--space-2);
  padding-left: var(--space-5);
  border-left: 1px solid var(--ink-600);
}

.shell__nav {
  margin-left: auto;
  display: flex;
  gap: var(--space-1);
}
.shell__nav-link {
  padding: 6px 12px;
  font-size: 13px;
  color: var(--ink-300);
  border-radius: var(--radius-sm);
  transition: color 0.15s, background 0.15s;
}
.shell__nav-link:hover {
  color: var(--ink-100);
  text-decoration: none;
}
.shell__nav-link--active {
  color: var(--signal);
  background: rgba(94, 234, 212, 0.08);
}

.shell__main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .fade-enter-active,
  .fade-leave-active {
    transition: none;
  }
}
</style>
