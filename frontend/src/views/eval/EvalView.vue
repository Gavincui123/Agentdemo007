<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { runEval, type EvalReport, type StageReport } from '../../api/eval'
import { ApiError } from '../../api/http'

/**
 * 评测视图（Phase 19·T101）——③环节测评的前端呈现。
 *
 * <p>输入评测令牌 → POST /eval/run → 跑 8 stage 黄金样例 → 展示各环节通过率与失败对照。
 * 视图层 ①②降级：运行失败 catch ApiError 展示话术；未运行 → 邀请行动空态（非报错）。
 * 强类型 EvalReport/StageReport/CaseResult 收口（字段 cases/caseId 对齐后端 record）。
 */
const token = ref('dev-eval-token')
const report = ref<EvalReport | null>(null)
const running = ref(false)
const error = ref('')

const overallRate = computed(() => {
  if (!report.value || report.value.totalCases === 0) return 0
  return report.value.totalPassed / report.value.totalCases
})
const hasRun = computed(() => report.value !== null)

async function run(): Promise<void> {
  running.value = true
  error.value = ''
  report.value = null
  try {
    report.value = await runEval(token.value.trim())
    ElMessage.success('评测完成')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '评测运行失败，请稍后重试'
  } finally {
    running.value = false
  }
}

function rateOf(s: StageReport): number {
  return s.total === 0 ? 0 : s.passed / s.total
}

function pct(n: number): string {
  return `${(n * 100).toFixed(0)}%`
}
</script>

<template>
  <div class="eval">
    <header class="eval__bar">
      <div class="eval__title-group">
        <h2 class="eval__title">评测</h2>
        <span class="eval__sub">黄金样例集 · 8 环节通过率</span>
      </div>
      <div class="eval__runner">
        <el-input
          v-model="token"
          size="small"
          placeholder="评测令牌"
          class="eval__token"
          :disabled="running"
        />
        <el-button type="primary" size="small" :loading="running" @click="run">运行评测</el-button>
      </div>
    </header>

    <div class="eval__body">
      <div v-if="error" class="eval__err degradation-note">{{ error }}</div>

      <div v-if="running && !report" class="eval__running">
        <div class="eval__running-dots"><span /><span /><span /></div>
        <div>正在跑黄金样例集…</div>
      </div>

      <!-- 未运行：空态邀请行动 -->
      <div v-else-if="!hasRun" class="eval__empty">
        <div class="eval__empty-title">运行一次黄金样例评测</div>
        <div class="eval__empty-sub">
          点击「运行评测」，加载 eval/*.json 黄金样例，逐环节比对话术短路、意图路由、RAG、工具、HITL、降级、审计的预期。
        </div>
        <div class="eval__stages-preview">
          <span v-for="s in ['injection', 'intent', 'routing', 'rag', 'tool', 'hitl', 'degradation', 'audit']" :key="s" class="eval__stage-chip mono">{{ s }}</span>
        </div>
      </div>

      <!-- 评测结果 -->
      <div v-else-if="report" class="eval__result">
        <div class="eval__overall surface">
          <div class="eval__overall-ring" :style="{ '--ring': overallRate >= 0.95 ? 'var(--signal)' : overallRate >= 0.8 ? 'var(--amber)' : 'var(--crimson)' }">
            <svg viewBox="0 0 36 36" class="eval__ring-svg">
              <circle class="eval__ring-bg" cx="18" cy="18" r="15.9" />
              <circle
                class="eval__ring-fg"
                cx="18"
                cy="18"
                r="15.9"
                :stroke-dasharray="`${overallRate * 100} ${100 - overallRate * 100}`"
              />
            </svg>
            <div class="eval__ring-center">
              <span class="tnum eval__ring-pct">{{ pct(overallRate) }}</span>
              <span class="eval__ring-label">总通过率</span>
            </div>
          </div>
          <div class="eval__overall-nums">
            <div class="eval__num"><span class="tnum eval__num-val">{{ report.totalPassed }}</span><span class="eval__num-key">通过</span></div>
            <div class="eval__num"><span class="tnum eval__num-val">{{ report.totalCases }}</span><span class="eval__num-key">样例</span></div>
            <div class="eval__num"><span class="tnum eval__num-val">{{ report.stages.length }}</span><span class="eval__num-key">环节</span></div>
          </div>
        </div>

        <div class="eval__stages">
          <div
            v-for="s in report.stages"
            :key="s.stage"
            class="stage surface"
            :class="{ 'stage--full': rateOf(s) === 1, 'stage--fail': rateOf(s) < 1 }"
          >
            <div class="stage__head">
              <span class="mono stage__name">{{ s.stage }}</span>
              <span class="tnum stage__rate" :class="rateOf(s) === 1 ? 'stage__rate--ok' : 'stage__rate--warn'">{{ pct(rateOf(s)) }}</span>
            </div>
            <div class="stage__bar">
              <div class="stage__bar-pass" :style="{ width: `${rateOf(s) * 100}%` }" />
            </div>
            <div class="stage__counts">
              <span class="tnum stage__pass">{{ s.passed }}/{{ s.total }}</span>
              <span v-if="s.cases.some((c) => !c.passed)" class="stage__fail-count">{{ s.cases.filter((c) => !c.passed).length }} 例不符</span>
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
}

.eval__err {
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 13px;
  margin-bottom: var(--space-4);
  max-width: 760px;
}

.eval__running {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-6);
  color: var(--ink-300);
  font-size: 13px;
}
.eval__running-dots {
  display: flex;
  gap: 6px;
}
.eval__running-dots span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--signal);
  opacity: 0.4;
  animation: eval-blink 1.2s infinite;
}
.eval__running-dots span:nth-child(2) {
  animation-delay: 0.2s;
}
.eval__running-dots span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes eval-blink {
  0%, 60%, 100% { opacity: 0.3; }
  30% { opacity: 1; }
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
.stage__fail-count {
  color: var(--amber);
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

@media (prefers-reduced-motion: reduce) {
  .eval__running-dots span {
    animation: none;
    opacity: 0.6;
  }
}
</style>
