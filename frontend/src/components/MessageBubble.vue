<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../stores/chat'
import { renderMarkdown } from '../utils/markdown'

/**
 * 消息气泡（Phase 16）——把 {@link ChatMessage} 投影为可见气泡。
 *
 * <p>user 右对齐、assistant 左对齐（Markdown 渲染）、system 居中琥珀（降级话术）。
 * 降级态 assistant（{@code degraded=true}）带琥珀左边框——镜像「系统仍答」语义（①②），
 * 不抛错、不暴露技术码。traceId/sessionId 以仪表读出声（mono）小字呈现，对齐后端链路。
 */
const props = defineProps<{
  message: ChatMessage
  /** 流式占位仍在生成中——内容为空时显示打字指示。 */
  typing?: boolean
}>()

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')

const html = computed(() => (isAssistant.value ? renderMarkdown(props.message.content) : ''))

const showTyping = computed(() => props.typing === true && !props.message.content)

/** 计时展示（后端口径）：毫秒原样，秒 1 位小数。 */
function fmtMs(ms?: number | null): string {
  if (ms == null) return ''
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

const timingText = computed(() => {
  if (props.message.totalMs == null) return ''
  const total = fmtMs(props.message.totalMs)
  const first = props.message.firstTokenMs != null ? `（首字 ${fmtMs(props.message.firstTokenMs)}）` : ''
  return `耗时 ${total}${first}`
})
</script>

<template>
  <div class="msg" :class="`msg--${message.role}`" :data-degraded="message.degraded ? '' : null">
    <div v-if="isUser" class="msg__bubble msg__bubble--user">{{ message.content }}</div>

    <div v-else-if="isAssistant" class="msg__bubble msg__bubble--assistant" :class="{ 'msg__bubble--degraded': message.degraded }">
      <div v-if="showTyping" class="typing"><span /><span /><span /></div>
      <div v-else class="msg__markdown" v-html="html" />
      <div v-if="message.degraded" class="msg__tag">降级·系统仍答</div>
      <div v-if="message.traceId || message.scenario || timingText" class="msg__meta">
        <span v-if="message.traceId" class="mono">trace:{{ message.traceId }}</span>
        <span v-if="message.scenario" class="mono msg__scenario">·{{ message.scenario }}</span>
        <span v-if="timingText" class="mono msg__timing">·{{ timingText }}</span>
      </div>
      <div v-if="message.citations && message.citations.length" class="msg__citations">
        <div class="msg__citations-label">参考来源 · 可追溯不等于绝对正确</div>
        <ol class="msg__citations-list">
          <li v-for="(c, idx) in message.citations" :key="idx" class="mono">{{ c }}</li>
        </ol>
      </div>
    </div>

    <div v-else class="msg__bubble msg__bubble--system">
      <span class="msg__tag msg__tag--inline">降级</span>
      <span>{{ message.content }}</span>
    </div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  margin: var(--space-2) 0;
}
.msg--user {
  justify-content: flex-end;
}
.msg--assistant {
  justify-content: flex-start;
}
.msg--system {
  justify-content: center;
}

.msg__bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.msg__bubble--user {
  background: var(--ink-700);
  border: 1px solid var(--ink-600);
  color: var(--ink-100);
  border-bottom-right-radius: 2px;
}

.msg__bubble--assistant {
  background: var(--ink-800);
  border: 1px solid var(--ink-600);
  border-bottom-left-radius: 2px;
}
/* 降级 assistant：琥珀左边框——「系统仍答」 */
.msg__bubble--degraded {
  border-left: 3px solid var(--amber);
  background: rgba(245, 185, 65, 0.06);
}

.msg__bubble--system {
  max-width: 90%;
  border-left: 3px solid var(--amber);
  background: rgba(245, 185, 65, 0.08);
  color: var(--amber);
  font-size: 13px;
  border-radius: var(--radius-sm);
}

.msg__markdown :deep(p) {
  margin: 0 0 8px;
}
.msg__markdown :deep(p:last-child) {
  margin-bottom: 0;
}
.msg__markdown :deep(code) {
  background: var(--ink-900);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 13px;
}
.msg__markdown :deep(pre) {
  background: var(--ink-900);
  border: 1px solid var(--ink-600);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.msg__markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.msg__markdown :deep(a) {
  color: var(--signal);
}

.msg__tag {
  display: inline-block;
  font-size: 10px;
  font-family: var(--font-mono);
  letter-spacing: 0.04em;
  color: var(--amber);
  margin-top: 6px;
  margin-bottom: 2px;
}
.msg__tag--inline {
  margin: 0 6px 0 0;
}

.msg__meta {
  margin-top: 6px;
  font-size: 10px;
  color: var(--ink-300);
  opacity: 0.8;
}
.msg__scenario {
  margin-left: 4px;
}
.msg__timing {
  margin-left: 4px;
}

/* 参考来源区（Phase 20 citation）——可追溯不等于绝对正确 */
.msg__citations {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--ink-600);
}
.msg__citations-label {
  font-size: 10px;
  color: var(--ink-300);
  margin-bottom: 4px;
}
.msg__citations-list {
  margin: 0;
  padding-left: 18px;
  font-size: 11px;
  color: var(--ink-300);
  line-height: 1.6;
}
.msg__citations-list li {
  word-break: break-word;
}

/* 打字指示——三个磷光青点 */
.typing {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--signal);
  opacity: 0.4;
  animation: typing-blink 1.2s infinite;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes typing-blink {
  0%,
  60%,
  100% {
    opacity: 0.3;
  }
  30% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .typing span {
    animation: none;
    opacity: 0.6;
  }
}
</style>
