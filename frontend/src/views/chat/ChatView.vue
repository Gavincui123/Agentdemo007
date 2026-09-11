<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import { useChatStore } from '../../stores/chat'
import MessageBubble from '../../components/MessageBubble.vue'

/**
 * 对话视图（Phase 16）——前端对话主界面。
 *
 * <p>消息流 + 输入器 + 同步/流式切换 + 空态示例（绑定后端 RAG 种子场景）。
 * 流式发送可中止（AbortController→SSE signal）。降级/话术短路由 store 兜底为
 * system/降级气泡（①②），视图层不感知错误码。traceId 经气泡 meta 呈现。
 */
const store = useChatStore()

const input = ref('')
const streamMode = ref(true)
const abortCtrl = ref<AbortController | null>(null)

const busy = computed(() => store.loading || store.streaming)
const listRef = ref<HTMLElement | null>(null)

const EXAMPLES = [
  '查一下订单 ORD123456 的物流状态',
  '帮我开增值税专用发票',
  '2加3乘4等于多少',
  '退款流程是什么',
]

async function send(): Promise<void> {
  const text = input.value.trim()
  if (!text || busy.value) return
  input.value = ''
  if (streamMode.value) {
    const ctrl = new AbortController()
    abortCtrl.value = ctrl
    await store.sendStream(text, ctrl.signal)
    abortCtrl.value = null
  } else {
    await store.send(text)
  }
}

function stop(): void {
  abortCtrl.value?.abort()
}

function useExample(ex: string): void {
  input.value = ex
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

// 新消息自动滚到底
watch(
  () => store.messages.length,
  async () => {
    await nextTick()
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
// 流式逐帧回填也滚到底
watch(
  () => store.messages.map((m) => m.content).join('|'),
  async () => {
    await nextTick()
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <div class="chat-view">
    <header class="chat-view__bar">
      <div class="chat-view__title">
        <span class="chat-view__dot" :class="{ 'chat-view__dot--live': store.streaming }" />
        对话
      </div>
      <div class="chat-view__mode">
        <span class="chat-view__mode-label">同步</span>
        <el-switch v-model="streamMode" size="small" />
        <span class="chat-view__mode-label chat-view__mode-label--active">流式</span>
      </div>
    </header>

    <div v-if="store.messages.length === 0" class="chat-view__empty">
      <div class="chat-view__empty-title">向 Agentdemo007 提问</div>
      <div class="chat-view__empty-sub">下面是几个可用场景，点选即可开始</div>
      <div class="chat-view__examples">
        <button v-for="ex in EXAMPLES" :key="ex" class="chat-view__example" @click="useExample(ex)">
          {{ ex }}
        </button>
      </div>
    </div>

    <div v-else ref="listRef" class="chat-view__list">
      <MessageBubble
        v-for="(m, i) in store.messages"
        :key="m.id"
        :message="m"
        :typing="i === store.messages.length - 1 && store.streaming"
      />
    </div>

    <footer class="chat-view__composer">
      <textarea
        v-model="input"
        class="chat-view__input"
        placeholder="输入消息…  Enter 发送 · Shift+Enter 换行"
        rows="2"
        :disabled="busy && !store.streaming"
        @keydown="onKeydown"
      />
      <div class="chat-view__actions">
        <span v-if="store.lastTraceId" class="mono chat-view__trace">trace:{{ store.lastTraceId }}</span>
        <el-button v-if="store.streaming" type="warning" plain size="small" @click="stop">停止</el-button>
        <el-button type="primary" size="small" :disabled="busy" :loading="store.loading" @click="send">
          发送
        </el-button>
        <el-button v-if="store.messages.length" size="small" plain @click="store.clear">清空</el-button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.chat-view__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--ink-600);
}
.chat-view__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}
.chat-view__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-500);
}
.chat-view__dot--live {
  background: var(--signal);
  box-shadow: 0 0 8px var(--signal);
  animation: dot-pulse 1.2s ease-in-out infinite;
}
@keyframes dot-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 1;
  }
}
.chat-view__mode {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-300);
}
.chat-view__mode-label--active {
  color: var(--signal);
}

.chat-view__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: var(--space-6);
}
.chat-view__empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--ink-100);
}
.chat-view__empty-sub {
  font-size: 13px;
  color: var(--ink-300);
  margin-bottom: var(--space-3);
}
.chat-view__examples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: center;
  max-width: 520px;
}
.chat-view__example {
  padding: 8px 14px;
  border: 1px solid var(--ink-600);
  background: var(--ink-800);
  color: var(--ink-100);
  border-radius: var(--radius);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.chat-view__example:hover {
  border-color: var(--signal);
  color: var(--signal);
}

.chat-view__list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

.chat-view__composer {
  border-top: 1px solid var(--ink-600);
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.chat-view__input {
  width: 100%;
  resize: none;
  background: var(--ink-900);
  border: 1px solid var(--ink-600);
  border-radius: var(--radius);
  color: var(--ink-100);
  padding: 10px 12px;
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.5;
  outline: none;
}
.chat-view__input:focus {
  border-color: var(--signal);
}
.chat-view__input::placeholder {
  color: var(--ink-300);
}
.chat-view__input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.chat-view__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  justify-content: flex-end;
}
.chat-view__trace {
  font-size: 10px;
  color: var(--ink-300);
  margin-right: auto;
}

@media (prefers-reduced-motion: reduce) {
  .chat-view__dot--live {
    animation: none;
  }
}
</style>
