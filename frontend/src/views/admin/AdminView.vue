<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getModels,
  getHitlTickets,
  confirmTicket,
  rejectTicket,
  getSessionHistory,
  type ModelSummary,
  type HitlTicketSummary,
  type ChatTurnSummary,
} from '../../api/admin'
import { ApiError } from '../../api/http'
import { clearAdminToken } from '../../api/auth'

/**
 * 管理台视图（Phase 19·T100）。
 *
 * <p>三职能：模型权重热视图、人工审批（HITL 确认/驳回）、会话历史回放。
 * 数据来自已测 api 模块（admin.ts）；视图层仅做呈现 + ①②降级兜底：
 * <ul>
 *   <li>每步降级：加载失败 → catch ApiError 展示话术 message（不暴露技术码），
 *       空列表 → 邀请行动的空态（非报错）；</li>
 *   <li>统一收口：强类型 ModelSummary/HitlTicketSummary/ChatTurnSummary（无 apiKey 外泄）。</li>
 * </ul>
 */
const activeTab = ref<'models' | 'hitl' | 'sessions'>('hitl')

const models = ref<ModelSummary[]>([])
const tickets = ref<HitlTicketSummary[]>([])
const modelsError = ref('')
const ticketsError = ref('')

const modelsLoading = ref(false)
const ticketsLoading = ref(false)
const acting = ref<string | null>(null)

// 会话历史检索
const sessionIdInput = ref('')
const history = ref<ChatTurnSummary[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const searched = ref(false)
const authError = ref(false)
const router = useRouter()

const maxWeight = computed(() => models.value.reduce((m, x) => Math.max(m, x.weight), 0) || 1)
const pendingCount = computed(() => tickets.value.filter((t) => t.status === 'PENDING').length)

async function loadModels(): Promise<void> {
  modelsLoading.value = true
  modelsError.value = ''
  authError.value = false
  try {
    models.value = await getModels()
  } catch (e) {
    modelsError.value = errMsg(e)
    authError.value = e instanceof ApiError && e.code === 401
  } finally {
    modelsLoading.value = false
  }
}

async function loadTickets(): Promise<void> {
  ticketsLoading.value = true
  ticketsError.value = ''
  try {
    tickets.value = await getHitlTickets()
  } catch (e) {
    ticketsError.value = errMsg(e)
    authError.value = e instanceof ApiError && e.code === 401
  } finally {
    ticketsLoading.value = false
  }
}

async function approve(id: string): Promise<void> {
  acting.value = id
  try {
    const updated = await confirmTicket(id)
    const i = tickets.value.findIndex((t) => t.id === id)
    if (i >= 0) tickets.value[i] = updated
    ElMessage.success('已确认')
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    acting.value = null
  }
}

async function reject(id: string): Promise<void> {
  acting.value = id
  try {
    const updated = await rejectTicket(id)
    const i = tickets.value.findIndex((t) => t.id === id)
    if (i >= 0) tickets.value[i] = updated
    ElMessage.success('已驳回')
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    acting.value = null
  }
}

async function searchHistory(): Promise<void> {
  const sid = sessionIdInput.value.trim()
  if (!sid) return
  historyLoading.value = true
  historyError.value = ''
  history.value = []
  searched.value = true
  try {
    history.value = await getSessionHistory(sid)
  } catch (e) {
    historyError.value = errMsg(e)
    authError.value = e instanceof ApiError && e.code === 401
  } finally {
    historyLoading.value = false
  }
}

function errMsg(e: unknown): string {
  return e instanceof ApiError ? e.message : '操作失败，请稍后重试'
}

/** 令牌失效（401）→ 清除并回录入页（话术短路：前端不校验，由后端 401 触发恢复）。 */
function reAuth(): void {
  clearAdminToken()
  router.replace('/auth?redirect=/admin')
}

function ticketStatusType(s: HitlTicketSummary['status']): string {
  switch (s) {
    case 'PENDING':
      return 'warning'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'info'
  }
}

function ticketStatusLabel(s: HitlTicketSummary['status']): string {
  return { PENDING: '待审批', APPROVED: '已确认', REJECTED: '已驳回', TIMEOUT: '已超时' }[s]
}

function fmtTime(iso: string | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

onMounted(() => {
  void loadModels()
  void loadTickets()
})
</script>

<template>
  <div class="admin">
    <header class="admin__bar">
      <div class="admin__title-group">
        <h2 class="admin__title">管理台</h2>
        <span v-if="pendingCount" class="admin__pill">{{ pendingCount }} 待审批</span>
      </div>
      <div class="admin__actions">
        <el-button size="small" plain :loading="modelsLoading || ticketsLoading" @click="loadModels(); loadTickets()">
          刷新
        </el-button>
      </div>
    </header>

    <div v-if="authError" class="admin__auth-banner degradation-note">
      <span>令牌无效或已失效，管理台拒绝访问。</span>
      <el-button size="small" plain @click="reAuth">重新录入令牌</el-button>
    </div>

    <el-tabs v-model="activeTab" class="admin__tabs">
      <!-- 人工审批 -->
      <el-tab-pane name="hitl">
        <template #label>
          人工审批<span v-if="pendingCount" class="admin__count">{{ pendingCount }}</span>
        </template>

        <div v-if="ticketsError" class="admin__err degradation-note">{{ ticketsError }}</div>
        <div v-else-if="ticketsLoading" class="admin__loading">加载工单…</div>
        <div v-else-if="tickets.length === 0" class="admin__empty">
          <div class="admin__empty-title">没有待审批的工单</div>
          <div class="admin__empty-sub">高风险操作触发人工审批时，工单会出现在这里</div>
        </div>
        <div v-else class="admin__tickets">
          <div v-for="t in tickets" :key="t.id" class="ticket surface">
            <div class="ticket__top">
              <el-tag size="small" :type="ticketStatusType(t.status) as 'warning' | 'success' | 'danger' | 'info' | undefined" effect="dark">
                {{ ticketStatusLabel(t.status) }}
              </el-tag>
              <span class="mono ticket__id">{{ t.id }}</span>
              <span class="mono ticket__time">{{ fmtTime(t.createdAt) }}</span>
            </div>
            <div class="ticket__query">{{ t.query }}</div>
            <div class="ticket__reason">原因 · {{ t.reason }}</div>
            <div class="mono ticket__session">session:{{ t.sessionId }}</div>
            <div v-if="t.resolvedAt" class="mono ticket__resolved">解决于 {{ fmtTime(t.resolvedAt) }}</div>
            <div v-if="t.status === 'PENDING'" class="ticket__actions">
              <el-button size="small" type="success" plain :loading="acting === t.id" @click="approve(t.id)">确认执行</el-button>
              <el-button size="small" type="danger" plain :loading="acting === t.id" @click="reject(t.id)">驳回</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 模型权重 -->
      <el-tab-pane name="models" label="模型权重">
        <div v-if="modelsError" class="admin__err degradation-note">{{ modelsError }}</div>
        <div v-else-if="modelsLoading" class="admin__loading">加载模型…</div>
        <div v-else-if="models.length === 0" class="admin__empty">
          <div class="admin__empty-title">未注册模型</div>
          <div class="admin__empty-sub">模型注册后，权重与状态会出现在这里</div>
        </div>
        <div v-else class="admin__models">
          <div v-for="m in models" :key="m.id" class="model surface" :class="{ 'model--off': !m.enabled }">
            <div class="model__head">
              <span class="model__name">{{ m.name }}</span>
              <span class="model__provider">{{ m.provider }}</span>
              <span
                class="model__status"
                :class="m.enabled ? 'model__status--on' : 'model__status--off'"
              >{{ m.enabled ? '启用' : '停用' }}</span>
            </div>
            <div class="model__weight">
              <span class="model__weight-label">权重</span>
              <div class="model__weight-bar">
                <div class="model__weight-fill" :style="{ width: `${(m.weight / maxWeight) * 100}%` }" />
              </div>
              <span class="tnum model__weight-num">{{ m.weight }}</span>
            </div>
            <div class="model__meta">
              <span v-for="tag in m.tags" :key="tag" class="model__tag mono">{{ tag }}</span>
            </div>
            <div class="model__foot mono">
              <span>maxTok {{ m.maxTokens }}</span>
              <span>¥{{ m.costPer1KTokens }}/1K</span>
              <span class="model__endpoint" :title="m.endpoint">{{ m.endpoint }}</span>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 会话历史 -->
      <el-tab-pane name="sessions" label="会话历史">
        <div class="admin__search">
          <el-input
            v-model="sessionIdInput"
            placeholder="输入会话 ID 检索历史轮次"
            clearable
            @keyup.enter="searchHistory"
          />
          <el-button type="primary" plain :loading="historyLoading" @click="searchHistory">检索</el-button>
        </div>

        <div v-if="historyError" class="admin__err degradation-note">{{ historyError }}</div>
        <div v-else-if="historyLoading" class="admin__loading">检索轮次…</div>
        <div v-else-if="searched && history.length === 0" class="admin__empty">
          <div class="admin__empty-title">该会话没有轮次记录</div>
          <div class="admin__empty-sub">换一个会话 ID，或确认该会话已产生对话</div>
        </div>
        <div v-else-if="history.length" class="admin__turns">
          <div v-for="turn in history" :key="turn.id" class="turn surface" :class="{ 'turn--degraded': turn.degraded }">
            <div class="turn__top">
              <span class="mono turn__trace">trace:{{ turn.traceId }}</span>
              <span v-if="turn.intent" class="mono turn__intent">{{ turn.intent }}</span>
              <span v-if="turn.scenario" class="mono turn__scenario">{{ turn.scenario }}</span>
              <span v-if="turn.degraded" class="turn__degraded-tag">降级</span>
              <span class="mono turn__time">{{ fmtTime(turn.timestamp) }}</span>
            </div>
            <div class="turn__in"><span class="turn__role">用户</span>{{ turn.rawInput }}</div>
            <div class="turn__out"><span class="turn__role turn__role--assistant">回复</span>{{ turn.finalReply }}</div>
          </div>
        </div>
        <div v-else class="admin__empty admin__empty--hint">
          <div class="admin__empty-title">回放一次会话</div>
          <div class="admin__empty-sub">输入会话 ID，逐轮查看原始输入、最终回复与降级标记</div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.admin__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--ink-600);
  flex-shrink: 0;
}
.admin__title-group {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.admin__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.admin__pill {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--amber);
  border: 1px solid var(--amber-dim);
  border-radius: 10px;
  padding: 1px 8px;
}
.admin__tabs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 var(--space-5) var(--space-5);
}
.admin__tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-4);
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--ink-900);
}
.admin__count {
  margin-left: 4px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--amber);
}

.admin__err {
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 13px;
  margin-bottom: var(--space-4);
}
.admin__auth-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin: var(--space-3) var(--space-5) 0;
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 13px;
}
.admin__loading {
  padding: var(--space-6);
  text-align: center;
  color: var(--ink-300);
  font-size: 13px;
}
.admin__empty {
  padding: var(--space-6);
  text-align: center;
}
.admin__empty--hint {
  padding-top: var(--space-6);
}
.admin__empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-100);
}
.admin__empty-sub {
  font-size: 12px;
  color: var(--ink-300);
  margin-top: 6px;
}

/* ---- HITL 工单 ---- */
.admin__tickets {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  max-width: 760px;
}
.ticket {
  padding: var(--space-3) var(--space-4);
}
.ticket__top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.ticket__id {
  font-size: 11px;
  color: var(--ink-300);
}
.ticket__time {
  font-size: 11px;
  color: var(--ink-300);
  margin-left: auto;
}
.ticket__query {
  font-size: 14px;
  color: var(--ink-100);
  line-height: 1.5;
  word-break: break-word;
}
.ticket__reason {
  font-size: 12px;
  color: var(--amber);
  margin-top: 6px;
}
.ticket__session {
  font-size: 10px;
  color: var(--ink-300);
  margin-top: var(--space-2);
}
.ticket__resolved {
  font-size: 10px;
  color: var(--ink-300);
}
.ticket__actions {
  margin-top: var(--space-3);
  display: flex;
  gap: var(--space-2);
}

/* ---- 模型 ---- */
.admin__models {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--space-3);
}
.model {
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.model--off {
  opacity: 0.6;
}
.model__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.model__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-100);
}
.model__provider {
  font-size: 11px;
  color: var(--ink-300);
}
.model__status {
  margin-left: auto;
  font-size: 10px;
  font-family: var(--font-mono);
  padding: 1px 6px;
  border-radius: 3px;
}
.model__status--on {
  color: var(--green);
  background: rgba(74, 222, 128, 0.1);
}
.model__status--off {
  color: var(--ink-300);
  background: var(--ink-700);
}
.model__weight {
  display: flex;
  align-items: center;
  gap: 8px;
}
.model__weight-label {
  font-size: 11px;
  color: var(--ink-300);
}
.model__weight-bar {
  flex: 1;
  height: 4px;
  background: var(--ink-700);
  border-radius: 2px;
  overflow: hidden;
}
.model__weight-fill {
  height: 100%;
  background: var(--signal);
  transition: width 0.3s;
}
.model__weight-num {
  font-size: 13px;
  color: var(--signal);
  min-width: 24px;
  text-align: right;
}
.model__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.model__tag {
  font-size: 10px;
  color: var(--ink-300);
  border: 1px solid var(--ink-600);
  border-radius: 3px;
  padding: 0 4px;
}
.model__foot {
  display: flex;
  gap: var(--space-3);
  font-size: 10px;
  color: var(--ink-300);
  flex-wrap: wrap;
}
.model__endpoint {
  color: var(--ink-300);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 会话历史 ---- */
.admin__search {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  max-width: 560px;
}
.admin__turns {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 820px;
}
.turn {
  padding: var(--space-2) var(--space-4);
  border-left: 3px solid var(--ink-600);
}
.turn--degraded {
  border-left-color: var(--amber);
  background: rgba(245, 185, 65, 0.04);
}
.turn__top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.turn__trace,
.turn__time {
  font-size: 10px;
  color: var(--ink-300);
}
.turn__time {
  margin-left: auto;
}
.turn__intent {
  font-size: 10px;
  color: var(--signal);
  background: rgba(94, 234, 212, 0.08);
  border-radius: 3px;
  padding: 0 4px;
}
.turn__scenario {
  font-size: 10px;
  color: var(--ink-300);
}
.turn__degraded-tag {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--amber);
}
.turn__in,
.turn__out {
  font-size: 13px;
  line-height: 1.5;
  display: flex;
  gap: 8px;
  word-break: break-word;
}
.turn__in {
  color: var(--ink-100);
}
.turn__out {
  color: var(--ink-100);
  margin-top: 2px;
}
.turn__role {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--ink-300);
  flex-shrink: 0;
  min-width: 28px;
}
.turn__role--assistant {
  color: var(--signal);
}
</style>
