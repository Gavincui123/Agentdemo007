<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { startEval, fetchEvalProgress, type EvalProgress, type StageReport } from '../../api/eval'
import { ApiError } from '../../api/http'

/**
 * 评测视图（Phase 19·T101 + 2026-09-18 异步化改造）——③环节测评的前端呈现。
 *
 * <p>输入评测令牌 → POST /eval/run <b>秒回启动后台作业</b>（全量真 LLM 调用耗时长，不再同步
 * 等待——修复"无进度 + 30s 网络连接失败"）→ 每秒轮询 GET /eval/progress：进度条 + 当前环节 +
 * 已完成环节实时亮灯；完成后展示统计面板（总数/通过/失败/通过率/失败率）+ 分环节卡片
 * （类别、NACOS/LOCAL 来源徽标、通过率、失败率、失败用例明细）。
 * 作业在跑时重复点运行 → 后端 409 → 自动接上正在跑的进度（页面刷新后同样可续看）。
 * 视图层 ①②降级：运行失败 catch ApiError 展示话术；未运行 → 邀请行动空态（非报错）。
 */
const token = ref('')
const progress = ref<EvalProgress | null>(null)
const polling = ref(false)
const error = ref('')
const elapsedMs = ref(0)
let pollTimer: ReturnType<typeof setInterval> | null = null

const viewStages = computed<StageReport[]>(() => {
  if (!progress.value) return []
  return progress.value.report?.stages ?? progress.value.stages
})

// ---- 统计口径（2026-09-18 问题3：按类别、总数、成功率、失败率的最终展示）----
const totalCases = computed(() => viewStages.value.reduce((n, s) => n + s.total, 0))
const totalPassed = computed(() => viewStages.value.reduce((n, s) => n + s.passed, 0))
const totalFailed = computed(() => totalCases.value - totalPassed.value)
const passRate = computed(() => (totalCases.value === 0 ? 0 : totalPassed.value / totalCases.value))
const failRate = computed(() => (totalCases.value === 0 ? 0 : totalFailed.value / totalCases.value))
const hasResult = computed(() => viewStages.value.length > 0)

const progressPct = computed(() => {
  if (!progress.value || progress.value.totalStages === 0) return 0
  return progress.value.completedStages / progress.value.totalStages
})

function rateOf(s: StageReport): number {
  return s.total === 0 ? 0 : s.passed / s.total
}
function failCountOf(s: StageReport): number {
  return s.total - s.passed
}
function pct(n: number): string {
  return `${(n * 100).toFixed(0)}%`
}
function elapsedText(): string {
  const sec = Math.floor(elapsedMs.value / 1000)
  return sec >= 60 ? `${Math.floor(sec / 60)}分${sec % 60}秒` : `${sec}秒`
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

function beginPolling(): void {
  if (pollTimer) return
  polling.value = true
  pollTimer = setInterval(async () => {
    try {
      const p = await fetchEvalProgress(token.value.trim())
      progress.value = p
      elapsedMs.value = p.startedAtMs > 0 ? Date.now() - p.startedAtMs : 0
      if (!p.running) {
        stopPolling()
        if (p.failed) ElMessage.error(p.errorMessage ?? '评测执行异常')
        else ElMessage.success('评测完成')
      }
    } catch {
      // 单次轮询失败静默（下一轮重试；连续失败由后端不可达的整体错误承担）
    }
  }, 1000)
}

async function run(): Promise<void> {
  const t = token.value.trim()
  if (!t || polling.value) return
  error.value = ''
  try {
    progress.value = await startEval(t)
    elapsedMs.value = 0
    beginPolling()
  } catch (e) {
    if (e instanceof ApiError && e.code === 409) {
      // 已有作业在跑（页面刷新后回来点运行也会走到这里）：自动接上进度
      ElMessage.info(e.message)
      beginPolling()
      return
    }
    error.value = e instanceof ApiError ? e.message : '评测运行失败，请稍后重试'
  }
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="eval">
    <header class="eval__bar">
      <div class="eval__title-group">
        <h2 class="eval__title">评测</h2>
        <span class="eval__sub">黄金样例集 · 后台异步执行 · 实时进度</span>
      </div>
      <div class="eval__runner">
        <el-input
          v-model="token"
          size="small"
          placeholder="评测令牌（与后端 agentdemo.eval.token 一致）"
          class="eval__token"
          :disabled="polling"
        />
        <el-button type="primary" size="small" :loading="polling" @click="run">运行评测</el-button>
      </div>
    </header>

    <div class="eval__body">
      <div v-if="error" class="eval__err degradation-note">{{ error }}</div>

      <!-- 进度面板（运行中）：进度条 + 当前环节 + 已完成环节实时亮灯 -->
      <div v-if="progress && progress.running" class="eval__progress surface">
        <div class="eval__progress-head">
          <span class="eval__progress-label">
            正在执行：<span class="mono">{{ progress.currentStage ?? '…' }}</span>
            （{{ progress.completedStages }}/{{ progress.totalStages }}）
          </span>
          <span v-if="elapsedMs > 0" class="tnum eval__progress-elapsed">已运行 {{ elapsedText() }}</span>
        </div>
        <div class="eval__progress-bar">
          <div class="eval__progress-fill" :style="{ width: `${progressPct * 100}%` }" />
        </div>
        <div class="eval__progress-stages">
          <span
            v-for="s in viewStages"
            :key="s.stage"
            class="mono eval__progress-chip"
            :class="rateOf(s) === 1 ? 'eval__progress-chip--ok' : 'eval__progress-chip--warn'"
          >{{ s.stage }} {{ pct(rateOf(s)) }}</span>
          <span v-for="sk in progress.skipped" :key="sk" class="mono eval__progress-chip eval__progress-chip--skip">{{ sk }} 跳过</span>
        </div>
        <div class="eval__progress-note">评测逐例驱动真实流水线（含真实 LLM 调用），耗时取决于模型响应；可离开本页，后台继续执行。</div>
      </div>

      <!-- 统计面板 + 分环节结果（运行中部分可见，完成后全量） -->
      <div v-if="hasResult" class="eval__result">
        <div class="eval__overall surface">
          <div class="eval__overall-ring" :style="{ '--ring': passRate >= 0.95 ? 'var(--signal)' : passRate >= 0.8 ? 'var(--amber)' : 'var(--crimson)' }">
            <svg viewBox="0 0 36 36" class="eval__ring-svg">
              <circle class="eval__ring-bg" cx="18" cy="18" r="15.9" />
              <circle
                class="eval__ring-fg"
                cx="18"
                cy="18"
                r="15.9"
                :stroke-dasharray="`${passRate * 100} ${100 - passRate * 100}`"
              />
            </svg>
            <div class="eval__ring-center">
              <span class="tnum eval__ring-pct">{{ pct(passRate) }}</span>
              <span class="eval__ring-label">总成功率</span>
            </div>
          </div>
          <div class="eval__overall-nums">
            <div class="eval__num"><span class="tnum eval__num-val">{{ totalCases }}</span><span class="eval__num-key">总样例</span></div>
            <div class="eval__num"><span class="tnum eval__num-val eval__num-val--ok">{{ totalPassed }}</span><span class="eval__num-key">通过</span></div>
            <div class="eval__num"><span class="tnum eval__num-val" :class="{ 'eval__num-val--fail': totalFailed > 0 }">{{ totalFailed }}</span><span class="eval__num-key">失败</span></div>
            <div class="eval__num"><span class="tnum eval__num-val">{{ pct(failRate) }}</span><span class="eval__num-key">失败率</span></div>
            <div class="eval__num"><span class="tnum eval__num-val">{{ viewStages.length }}</span><span class="eval__num-key">环节数</span></div>
          </div>
        </div>

        <div class="eval__stages">
          <div
            v-for="s in viewStages"
            :key="s.stage"
            class="stage surface"
            :class="{ 'stage--full': rateOf(s) === 1, 'stage--fail': rateOf(s) < 1 }"
          >
            <div class="stage__head">
              <span class="mono stage__name">{{ s.stage }}</span>
              <span v-if="s.source" class="mono stage__source" :class="s.source === 'nacos' ? 'stage__source--nacos' : 'stage__source--local'">{{ s.source === 'nacos' ? 'NACOS' : 'LOCAL' }}</span>
              <span class="tnum stage__rate" :class="rateOf(s) === 1 ? 'stage__rate--ok' : 'stage__rate--warn'">{{ pct(rateOf(s)) }}</span>
            </div>
            <div class="stage__bar">
              <div class="stage__bar-pass" :style="{ width: `${rateOf(s) * 100}%` }" />
            </div>
            <div class="stage__counts">
              <span class="tnum">总数 {{ s.total }}</span>
              <span class="tnum stage__pass">通过 {{ s.passed }}</span>
              <span class="tnum stage__fail-count" :class="{ 'stage__fail-count--zero': failCountOf(s) === 0 }">失败 {{ failCountOf(s) }}（{{ pct(1 - rateOf(s)) }}）</span>
            </div>
            <div v-if="s.cases.some((c) => !c.passed)" class="stage__fails">
              <div v-for="c in s.cases.filter((x) => !x.passed)" :key="c.caseId" class="fail">
                <div class="mono fail__id">{{ c.caseId }}</div>
                <ul class="fail__mismatches">
                  <li v-for="(mm, i) in c.mismatches" :key="i">{{ mm }}</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 未运行：空态邀请行动 -->
      <div v-else-if="!progress?.running" class="eval__empty">
        <div class="eval__empty-title">运行一次黄金样例评测</div>
        <div class="eval__empty-sub">
          点击「运行评测」在后台逐环节执行（真 LLM 调用，本页实时显示进度）；加载 eval/*.json 黄金样例，
          逐环节比对话术短路、意图路由、RAG、工具、HITL、降级、审计的预期。
        </div>
        <div class="eval__stages-preview">
          <span v-for="s in ['injection', 'intent', 'routing', 'rag', 'rag-redteam', 'tool', 'hitl', 'degradation', 'audit']" :key="s" class="eval__stage-chip mono">{{ s }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.eval {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.eval__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--ink-600);
  flex-shrink: 0;
}
.eval__title-group {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}
.eval__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.eval__sub {
  font-size: 12px;
  color: var(--ink-300);
}
.eval__runner {
  display: flex;
  gap: var(--space-2);
}
.eval__token {
  width: 180px;
}

.eval__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.eval__err {
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 13px;
  max-width: 760px;
}

/* ---- 进度面板 ---- */
.eval__progress {
  padding: var(--space-4) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 820px;
}
.eval__progress-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ink-100);
}
.eval__progress-elapsed {
  font-size: 11px;
  color: var(--ink-300);
}
.eval__progress-bar {
  height: 6px;
  background: var(--ink-700);
  border-radius: 3px;
  overflow: hidden;
}
.eval__progress-fill {
  height: 100%;
  background: var(--signal);
  border-radius: 3px;
  transition: width 0.6s ease;
  box-shadow: 0 0 8px rgba(94, 234, 212, 0.5);
}
.eval__progress-stages {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.eval__progress-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 3px;
  border: 1px solid var(--ink-600);
  color: var(--ink-300);
}
.eval__progress-chip--ok {
  color: var(--signal);
  border-color: rgba(94, 234, 212, 0.4);
}
.eval__progress-chip--warn {
  color: var(--amber);
  border-color: rgba(245, 185, 65, 0.4);
}
.eval__progress-chip--skip {
  color: var(--crimson);
  border-color: rgba(248, 113, 113, 0.4);
}
.eval__progress-note {
  font-size: 11px;
  color: var(--ink-300);
  line-height: 1.6;
}

.eval__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--space-6);
  gap: 6px;
}
.eval__empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-100);
}
.eval__empty-sub {
  font-size: 12px;
  color: var(--ink-300);
  max-width: 520px;
  line-height: 1.6;
  margin-bottom: var(--space-4);
}
.eval__stages-preview {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: center;
  max-width: 560px;
}
.eval__stage-chip {
  font-size: 11px;
  color: var(--ink-300);
  border: 1px solid var(--ink-600);
  border-radius: 3px;
  padding: 2px 8px;
}

/* ---- 结果 ---- */
.eval__result {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}
.eval__overall {
  display: flex;
  align-items: center;
  gap: var(--space-6);
  padding: var(--space-5);
  max-width: 820px;
}
.eval__overall-ring {
  position: relative;
  width: 96px;
  height: 96px;
  flex-shrink: 0;
}
.eval__ring-svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.eval__ring-bg {
  fill: none;
  stroke: var(--ink-700);
  stroke-width: 3;
}
.eval__ring-fg {
  fill: none;
  stroke: var(--ring, var(--signal));
  stroke-width: 3;
  stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease;
}
.eval__ring-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.eval__ring-pct {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-100);
}
.eval__ring-label {
  font-size: 10px;
  color: var(--ink-300);
}
.eval__overall-nums {
  display: flex;
  gap: var(--space-5);
  flex-wrap: wrap;
}
.eval__num {
  display: flex;
  flex-direction: column;
}
.eval__num-val {
  font-size: 24px;
  font-weight: 600;
  color: var(--ink-100);
}
.eval__num-val--ok {
  color: var(--signal);
}
.eval__num-val--fail {
  color: var(--amber);
}
.eval__num-key {
  font-size: 11px;
  color: var(--ink-300);
}

.eval__stages {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--space-3);
}
.stage {
  padding: var(--space-3) var(--space-4);
  border-left: 3px solid var(--ink-600);
}
.stage--full {
  border-left-color: var(--signal);
}
.stage--fail {
  border-left-color: var(--amber);
}
.stage__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.stage__source {
  font-size: 9px;
  letter-spacing: 0.05em;
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid;
}
.stage__source--nacos {
  color: var(--signal);
  border-color: rgba(94, 234, 212, 0.4);
}
.stage__source--local {
  color: var(--ink-300);
  border-color: var(--ink-600);
}
.stage__name {
  font-size: 12px;
  color: var(--ink-100);
}
.stage__rate {
  font-size: 14px;
  font-weight: 600;
}
.stage__rate--ok {
  color: var(--signal);
}
.stage__rate--warn {
  color: var(--amber);
}
.stage__bar {
  height: 4px;
  background: var(--ink-700);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 6px;
}
.stage__bar-pass {
  height: 100%;
  background: var(--signal);
  transition: width 0.4s;
}
.stage__counts {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-300);
}
.stage__pass {
  color: var(--signal);
}
.stage__fail-count {
  color: var(--amber);
}
.stage__fail-count--zero {
  color: var(--ink-300);
}

.stage__fails {
  margin-top: var(--space-2);
  border-top: 1px solid var(--ink-600);
  padding-top: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.fail {
  padding: var(--space-2);
  background: rgba(245, 185, 65, 0.04);
  border-radius: var(--radius-sm);
}
.fail__id {
  font-size: 11px;
  color: var(--amber);
  margin-bottom: 4px;
}
.fail__mismatches {
  margin: 0;
  padding-left: 16px;
  font-size: 11px;
  color: var(--ink-300);
  line-height: 1.6;
}
.fail__mismatches li {
  word-break: break-word;
}
</style>
