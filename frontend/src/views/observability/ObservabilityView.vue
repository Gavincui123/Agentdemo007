<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { getObservabilitySummary, type ObservabilitySummary } from '../../api/obs'
import { ApiError } from '../../api/http'
import { clearAdminToken } from '../../api/auth'
import BaseChart from '../../components/BaseChart.vue'

/**
 * 可观测视图（Phase 19·T102）——全链路遥测快照。
 *
 * <p>GET /api/obs/summary → 16 字段 {@link ObservabilitySummary}（强类型收口，④），
 * 视图分四组呈现：流量读数、结局分布（ECharts 环图）、RAG 命中（环图）、降级分场景（条形图）。
 * ①②降级：加载失败 → catch ApiError 展示话术；空指标 → 全 0 读数 + 空态说明（端点恒可用，不抛）。
 */
const summary = ref<ObservabilitySummary | null>(null)
const loading = ref(false)
const error = ref('')
const lastUpdated = ref('')
const authError = ref(false)
const router = useRouter()

const empty: ObservabilitySummary = {
  chatRequests: 0,
  degradationTotal: 0,
  degradationByScenario: {},
  outcomeOk: 0,
  outcomeDegraded: 0,
  outcomeShortCircuit: 0,
  ragHit: 0,
  ragMiss: 0,
  toolFailure: 0,
  hitlTriggered: 0,
  failoverExhausted: 0,
  modelCount: 0,
  modelEnabled: 0,
  hitlPendingTickets: 0,
  totalTurns: 0,
  degradedTurns: 0,
}

const s = computed<ObservabilitySummary>(() => summary.value ?? empty)
const hasData = computed(() => s.value.chatRequests > 0 || s.value.totalTurns > 0)

const degradRate = computed(() => {
  if (s.value.chatRequests === 0) return 0
  return s.value.degradationTotal / s.value.chatRequests
})

const outcomeTotal = computed(() => s.value.outcomeOk + s.value.outcomeDegraded + s.value.outcomeShortCircuit)
const ragTotal = computed(() => s.value.ragHit + s.value.ragMiss)
const scenarios = computed(() => Object.keys(s.value.degradationByScenario))
const scenarioCounts = computed(() => scenarios.value.map((k) => s.value.degradationByScenario[k] ?? 0))

const outcomeOption = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)', textStyle: { color: '#e8ecf5' } },
  legend: { bottom: 2, textStyle: { color: '#9aa4bd', fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
  series: [
    {
      type: 'pie',
      radius: ['58%', '78%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: false,
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: '#0b1020', borderWidth: 2 },
      data: [
        { value: s.value.outcomeOk, name: '正常', itemStyle: { color: '#5eead4' } },
        { value: s.value.outcomeDegraded, name: '降级', itemStyle: { color: '#f5b941' } },
        { value: s.value.outcomeShortCircuit, name: '短路', itemStyle: { color: '#f87171' } },
      ],
    },
  ],
}) as EChartsOption)

const ragOption = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)', textStyle: { color: '#e8ecf5' } },
  legend: { bottom: 2, textStyle: { color: '#9aa4bd', fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
  series: [
    {
      type: 'pie',
      radius: ['58%', '78%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: false,
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: '#0b1020', borderWidth: 2 },
      data: [
        { value: s.value.ragHit, name: '命中', itemStyle: { color: '#5eead4' } },
        { value: s.value.ragMiss, name: '未命中', itemStyle: { color: '#3a4670' } },
      ],
    },
  ],
}) as EChartsOption)

const scenarioOption = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, textStyle: { color: '#e8ecf5' } },
  grid: { left: 8, right: 16, top: 8, bottom: 8, containLabel: true },
  xAxis: {
    type: 'value',
    axisLine: { lineStyle: { color: '#2a3556' } },
    axisLabel: { color: '#9aa4bd', fontSize: 10 },
    splitLine: { lineStyle: { color: '#1c2540' } },
  },
  yAxis: {
    type: 'category',
    data: scenarios.value,
    axisLine: { lineStyle: { color: '#2a3556' } },
    axisLabel: { color: '#9aa4bd', fontSize: 11 },
  },
  series: [
    {
      type: 'bar',
      data: scenarioCounts.value,
      itemStyle: { color: '#f5b941', borderRadius: [0, 3, 3, 0] },
      barMaxWidth: 14,
    },
  ],
}) as EChartsOption)

function fmtClock(iso: string): string {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

async function refresh(): Promise<void> {
  loading.value = true
  error.value = ''
  authError.value = false
  try {
    const data = await getObservabilitySummary()
    summary.value = data
    lastUpdated.value = new Date().toISOString()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '指标加载失败，请稍后重试'
    authError.value = e instanceof ApiError && e.code === 401
  } finally {
    loading.value = false
  }
}

/** 令牌失效（401）→ 清除并回录入页（话术短路：前端不校验，由后端 401 触发恢复路径）。 */
function reAuth(): void {
  clearAdminToken()
  router.replace('/auth?redirect=/obs')
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="obs">
    <header class="obs__bar">
      <div class="obs__title-group">
        <h2 class="obs__title">可观测</h2>
        <span class="obs__sub">全链路遥测快照</span>
      </div>
      <div class="obs__actions">
        <span v-if="lastUpdated" class="mono obs__updated">更新于 {{ fmtClock(lastUpdated) }}</span>
        <el-button size="small" plain :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </header>

    <div class="obs__body">
      <div v-if="error" class="obs__err degradation-note">
        <span>{{ error }}</span>
        <el-button v-if="authError" size="small" plain @click="reAuth">重新录入令牌</el-button>
      </div>

      <div v-if="loading && !summary" class="obs__loading">采集指标…</div>

      <template v-else>
        <!-- 流量读数 -->
        <div class="obs__readouts">
          <div class="readout surface">
            <span class="readout__label">对话请求</span>
            <span class="tnum readout__value">{{ s.chatRequests }}</span>
          </div>
          <div class="readout surface">
            <span class="readout__label">总轮次</span>
            <span class="tnum readout__value">{{ s.totalTurns }}</span>
          </div>
          <div class="readout surface" :class="{ 'readout--warn': s.degradedTurns > 0 }">
            <span class="readout__label">降级轮次</span>
            <span class="tnum readout__value">{{ s.degradedTurns }}</span>
          </div>
          <div class="readout surface" :class="{ 'readout--warn': s.degradationTotal > 0 }">
            <span class="readout__label">降级总次数</span>
            <span class="tnum readout__value">{{ s.degradationTotal }}</span>
            <span v-if="s.chatRequests > 0" class="tnum readout__rate">({{ (degradRate * 100).toFixed(1) }}%)</span>
          </div>
        </div>

        <!-- 空态 -->
        <div v-if="!hasData" class="obs__empty">
          <div class="obs__empty-title">系统就绪，尚无流量</div>
          <div class="obs__empty-sub">发起对话后，计数器与结局分布会在此实时聚合</div>
        </div>

        <!-- 图表区 -->
        <template v-else>
          <div class="obs__charts">
            <div class="chart-card surface">
              <div class="chart-card__head"><span class="chart-card__title">结局分布</span><span class="mono chart-card__hint">pipeline outcome</span></div>
              <div class="chart-card__body">
                <div class="chart-card__plot">
                  <BaseChart :option="outcomeOption" />
                  <div class="chart-card__center">
                    <span class="tnum chart-card__center-val">{{ outcomeTotal }}</span>
                    <span class="chart-card__center-label">总结局</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="chart-card surface">
              <div class="chart-card__head"><span class="chart-card__title">RAG 命中</span><span class="mono chart-card__hint">rag</span></div>
              <div class="chart-card__body">
                <div class="chart-card__plot">
                  <BaseChart :option="ragOption" />
                  <div class="chart-card__center">
                    <span class="tnum chart-card__center-val">{{ ragTotal > 0 ? ((s.ragHit / ragTotal) * 100).toFixed(0) + '%' : '—' }}</span>
                    <span class="chart-card__center-label">命中率</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="scenarios.length > 0" class="chart-card surface obs__degrad">
            <div class="chart-card__head"><span class="chart-card__title">降级分场景</span><span class="mono chart-card__hint">degradation by scenario</span></div>
            <div class="chart-card__body chart-card__body--bar">
              <BaseChart :option="scenarioOption" />
            </div>
          </div>
        </template>

        <!-- 信号读数 -->
        <div class="obs__signals">
          <div class="signal surface">
            <span class="signal__label">工具失败</span>
            <span class="tnum signal__value" :class="{ 'signal__value--warn': s.toolFailure > 0 }">{{ s.toolFailure }}</span>
          </div>
          <div class="signal surface">
            <span class="signal__label">HITL 触发</span>
            <span class="tnum signal__value">{{ s.hitlTriggered }}</span>
          </div>
          <div class="signal surface">
            <span class="signal__label">故障转移耗尽</span>
            <span class="tnum signal__value" :class="{ 'signal__value--danger': s.failoverExhausted > 0 }">{{ s.failoverExhausted }}</span>
          </div>
          <div class="signal surface">
            <span class="signal__label">待审批工单</span>
            <span class="tnum signal__value" :class="{ 'signal__value--warn': s.hitlPendingTickets > 0 }">{{ s.hitlPendingTickets }}</span>
          </div>
          <div class="signal surface">
            <span class="signal__label">模型</span>
            <span class="tnum signal__value">{{ s.modelEnabled }}<span class="signal__of">/{{ s.modelCount }}</span></span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.obs {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.obs__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--ink-600);
  flex-shrink: 0;
}
.obs__title-group {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}
.obs__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.obs__sub {
  font-size: 12px;
  color: var(--ink-300);
}
.obs__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.obs__updated {
  font-size: 10px;
  color: var(--ink-300);
}

.obs__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.obs__err {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 13px;
}
.obs__loading {
  padding: var(--space-6);
  text-align: center;
  color: var(--ink-300);
  font-size: 13px;
}
.obs__empty {
  padding: var(--space-6);
  text-align: center;
}
.obs__empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-100);
}
.obs__empty-sub {
  font-size: 12px;
  color: var(--ink-300);
  margin-top: 6px;
}

/* ---- 读数条 ---- */
.obs__readouts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--space-3);
}
.readout {
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.readout--warn {
  border-color: var(--amber-dim);
  background: rgba(245, 185, 65, 0.04);
}
.readout__label {
  font-size: 11px;
  color: var(--ink-300);
}
.readout__value {
  font-size: 26px;
  font-weight: 600;
  color: var(--ink-100);
}
.readout--warn .readout__value {
  color: var(--amber);
}
.readout__rate {
  font-size: 12px;
  color: var(--ink-300);
}

/* ---- 图表 ---- */
.obs__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--space-3);
}
.chart-card {
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.chart-card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.chart-card__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-100);
}
.chart-card__hint {
  font-size: 10px;
  color: var(--ink-300);
}
.chart-card__body {
  height: 220px;
}
.chart-card__body--bar {
  height: 180px;
}
.chart-card__plot {
  position: relative;
  height: 100%;
}
.chart-card__center {
  position: absolute;
  top: 38%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  pointer-events: none;
}
.chart-card__center-val {
  font-size: 22px;
  font-weight: 600;
  color: var(--ink-100);
}
.chart-card__center-label {
  font-size: 10px;
  color: var(--ink-300);
}

.obs__degrad {
  margin-top: 0;
}

/* ---- 信号读数 ---- */
.obs__signals {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-3);
}
.signal {
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.signal__label {
  font-size: 11px;
  color: var(--ink-300);
}
.signal__value {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-100);
}
.signal__value--warn {
  color: var(--amber);
}
.signal__value--danger {
  color: var(--crimson);
}
.signal__of {
  font-size: 13px;
  color: var(--ink-300);
  font-weight: 400;
}
</style>
