<script setup lang="ts">
/**
 * 流水线脊（Phase 16·签名元素）。
 *
 * <p>七层架构节点链：接入→会话→意图→能力→上下文→网关→输出——这是 Agentdemo007
 * 真实的请求处理流（非装饰）。活跃节点磷光青、降级态整脊转琥珀，编码「健康实时 vs 系统仍答」
 * 两条核心态（①②）。header 中作品牌标识渲染（静态），ChatView 可传 active/degraded 联动。
 */
const LAYERS = [
  { key: 'ingest', label: '接入' },
  { key: 'session', label: '会话' },
  { key: 'intent', label: '意图' },
  { key: 'capability', label: '能力' },
  { key: 'context', label: '上下文' },
  { key: 'gateway', label: '网关' },
  { key: 'output', label: '输出' },
] as const

defineProps<{
  /** 活跃层下标（0-6）；该层及之前亮起磷光青。null=全静态。 */
  active?: number | null
  /** 降级态：整脊转琥珀（系统仍答）。 */
  degraded?: boolean
}>()
</script>

<template>
  <div class="spine" :class="{ 'spine--degraded': degraded }" role="img" aria-label="七层流水线">
    <template v-for="(layer, i) in LAYERS" :key="layer.key">
      <div
        class="spine__node"
        :class="{
          'spine__node--active': active != null && i <= active,
          'spine__node--current': active === i,
        }"
      >
        <span class="spine__diamond" />
        <span class="spine__label">{{ layer.label }}</span>
      </div>
      <span v-if="i < LAYERS.length - 1" class="spine__link" />
    </template>
  </div>
</template>

<style scoped>
.spine {
  display: inline-flex;
  align-items: center;
  gap: 0;
  font-family: var(--font-mono);
  user-select: none;
}

.spine__node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 0 6px;
}

.spine__diamond {
  width: 7px;
  height: 7px;
  transform: rotate(45deg);
  background: var(--ink-500);
  border: 1px solid var(--ink-500);
  transition: background 0.2s, border-color 0.2s, box-shadow 0.2s;
}

.spine__label {
  font-size: 9px;
  letter-spacing: 0.06em;
  color: var(--ink-300);
  transition: color 0.2s;
}

.spine__link {
  width: 14px;
  height: 1px;
  background: var(--ink-600);
  margin-top: -15px; /* 对齐菱形中线 */
  transition: background 0.2s;
}

/* 活跃层及之前：磷光青 */
.spine__node--active .spine__diamond {
  background: var(--signal);
  border-color: var(--signal);
  box-shadow: 0 0 6px rgba(94, 234, 212, 0.5);
}
.spine__node--active .spine__label {
  color: var(--signal);
}
.spine__node--active + .spine__link {
  background: var(--signal-dim);
}

/* 当前层：脉动 */
.spine__node--current .spine__diamond {
  animation: spine-pulse 1.4s ease-in-out infinite;
}
@keyframes spine-pulse {
  0%,
  100% {
    box-shadow: 0 0 4px rgba(94, 234, 212, 0.4);
  }
  50% {
    box-shadow: 0 0 10px rgba(94, 234, 212, 0.8);
  }
}

/* 降级态：整脊转琥珀 */
.spine--degraded .spine__node--active .spine__diamond {
  background: var(--amber);
  border-color: var(--amber);
  box-shadow: 0 0 6px rgba(245, 185, 65, 0.5);
}
.spine--degraded .spine__node--active .spine__label {
  color: var(--amber);
}
.spine--degraded .spine__node--current .spine__diamond {
  animation-name: spine-pulse-amber;
}
@keyframes spine-pulse-amber {
  0%,
  100% {
    box-shadow: 0 0 4px rgba(245, 185, 65, 0.4);
  }
  50% {
    box-shadow: 0 0 10px rgba(245, 185, 65, 0.8);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spine__node--current .spine__diamond {
    animation: none;
  }
}
</style>
