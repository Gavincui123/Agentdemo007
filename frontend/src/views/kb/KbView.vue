<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  KB_SUPPORTED_EXTENSIONS,
  KB_LEVEL_OPTIONS,
  deleteDocument,
  getDocument,
  ingestDocument,
  listDocuments,
  previewDocument,
  type KbDocumentDetail,
  type KbDocumentSummary,
  type KbIngestResult,
} from '../../api/kb'
import { ApiError } from '../../api/http'

/**
 * 知识库视图（[[kb-ingest-design]]·任务2/3 前端）——RAG 录入/版本/命名空间管理台。
 *
 * <p>三区：①录入面板（选择文件 + 元数据表单：文档号/标题/命名空间/可见主体/知识域/版本说明；
 * 试运行=解析清洗切块预览不入库，确认后正式录入=嵌入入库+版本收口）②文档台账
 * （namespace/status 过滤，换版历史可见：SUPERSEDED 灰显）③详情（全部切块面包屑/正文）。
 * 视图层①②降级：录入失败 catch ApiError 话术展示；空态邀请行动非报错。
 */
const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const submitting = ref(false)
const loading = ref(false)
const preview = ref<KbIngestResult | null>(null)
const docs = ref<KbDocumentSummary[]>([])
const detail = ref<KbDocumentDetail | null>(null)
const detailLoading = ref(false)
const filterNamespace = ref('')

const form = ref({
  docNo: '',
  title: '',
  namespace: 'PUBLIC' as 'PUBLIC' | 'PRIVATE',
  requiredLevel: '',
  allowedPrincipals: '',
  domain: '',
  versionNote: '',
})

const visibleDocs = computed(() =>
  docs.value.filter((d) => !filterNamespace.value || d.namespace === filterNamespace.value),
)
const statusLabel: Record<string, string> = {
  ACTIVE: '生效中',
  SUPERSEDED: '已换版',
  DELETED: '已下架',
}

/** 可见等级档位展示（对齐后端 KbLevel V0~V5）。 */
const levelLabel: Record<number, string> = {
  0: 'V0 公开',
  1: 'V1 注册',
  2: 'V2 白银',
  3: 'V3 黄金',
  4: 'V4 铂金',
  5: 'V5 全量',
}

function pickFile(): void {
  fileInput.value?.click()
}

function onFileChange(e: Event): void {
  const input = e.target as HTMLInputElement
  file.value = input.files?.[0] ?? null
  preview.value = null
}

function validate(): string | null {
  if (!file.value) return '请先选择知识文件'
  if (!form.value.requiredLevel) return '请选择文档可见等级（V0 公开 ~ V5 全量）'
  if (form.value.namespace === 'PRIVATE' && !form.value.allowedPrincipals.trim()) {
    return '私有知识需填写可见主体（逗号分隔的用户 ID），否则对话侧将检索不到'
  }
  return null
}

function ingestForm() {
  return {
    docNo: form.value.docNo,
    title: form.value.title,
    namespace: form.value.namespace,
    requiredLevel: form.value.requiredLevel,
    allowedPrincipals: form.value.allowedPrincipals,
    domain: form.value.domain,
    versionNote: form.value.versionNote,
  }
}

async function runPreview(): Promise<void> {
  const invalid = validate()
  if (invalid || !file.value) {
    if (invalid) ElMessage.warning(invalid)
    return
  }
  submitting.value = true
  try {
    preview.value = await previewDocument(file.value, ingestForm())
    ElMessage.success(`解析成功：将切为 ${preview.value.totalChunks} 块（共 ${preview.value.charCount} 字）`)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '解析失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function runIngest(): Promise<void> {
  const invalid = validate()
  if (invalid || !file.value) {
    if (invalid) ElMessage.warning(invalid)
    return
  }
  submitting.value = true
  try {
    const r = await ingestDocument(file.value, ingestForm())
    preview.value = r
    ElMessage.success(
      r.supersededVersion != null
        ? `已录入 v${r.version}（v${r.supersededVersion} 已换版下线）`
        : `已录入 v${r.version}`,
    )
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '录入失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    docs.value = await listDocuments()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '台账加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(d: KbDocumentSummary): Promise<void> {
  detailLoading.value = true
  try {
    detail.value = await getDocument(d.id)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function removeDoc(d: KbDocumentSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `下架后对话侧立即检索不到该文档（v${d.version}），历史记录保留。确认下架「${d.title}」？`,
      '下架确认',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteDocument(d.id)
    ElMessage.success('已下架')
    if (detail.value?.document.id === d.id) detail.value = null
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '下架失败')
  }
}

function fmtTime(t: string | null): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(refresh)
</script>

<template>
  <div class="kb">
    <header class="kb__bar">
      <div class="kb__title-group">
        <h2 class="kb__title">知识库</h2>
        <span class="kb__sub">RAG 录入 · 多格式解析 · 命名空间/权限 · 版本管理</span>
      </div>
      <el-button size="small" :loading="loading" @click="refresh">刷新台账</el-button>
    </header>

    <div class="kb__body">
      <!-- ① 录入面板 -->
      <section class="kb__ingest surface">
        <div class="kb__ingest-head">
          <h3 class="kb__ingest-title">录入新文档 / 换版</h3>
          <span class="kb__ingest-hint">同「命名空间 + 文档号」重灌即升版本，旧版自动下线</span>
        </div>
        <div class="kb__ingest-grid">
          <div class="kb__field kb__field--file">
            <label class="kb__label">知识文件</label>
            <input ref="fileInput" type="file" :accept="KB_SUPPORTED_EXTENSIONS" class="kb__file" @change="onFileChange" />
            <button class="kb__pick" type="button" @click="pickFile">{{ file ? file.name : '选择文件' }}</button>
            <span class="kb__supported mono">md / txt / html / pdf / docx / xlsx / csv / tsv / json</span>
          </div>
          <div class="kb__field">
            <label class="kb__label">文档号（可空=取文件名）</label>
            <el-input v-model="form.docNo" size="small" placeholder="如 return-faq（同号重灌即换版）" />
          </div>
          <div class="kb__field">
            <label class="kb__label">标题（可空）</label>
            <el-input v-model="form.title" size="small" placeholder="默认取文件名/文内一级标题" />
          </div>
          <div class="kb__field">
            <label class="kb__label">命名空间</label>
            <el-select v-model="form.namespace" size="small">
              <el-option label="公开（所有人可检索）" value="PUBLIC" />
              <el-option label="私有（名单主体可检索）" value="PRIVATE" />
            </el-select>
          </div>
          <div class="kb__field">
            <label class="kb__label">可见等级（必选）</label>
            <el-select v-model="form.requiredLevel" size="small" placeholder="客户等级 ≥ 此档才可检索">
              <el-option v-for="o in KB_LEVEL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </div>
          <div v-if="form.namespace === 'PRIVATE'" class="kb__field">
            <label class="kb__label">可见主体（逗号分隔用户 ID）</label>
            <el-input v-model="form.allowedPrincipals" size="small" placeholder="如 10086,10010" />
          </div>
          <div class="kb__field">
            <label class="kb__label">知识域（可空）</label>
            <el-input v-model="form.domain" size="small" placeholder="如 after_sale_policy（供路由窄化）" />
          </div>
          <div class="kb__field">
            <label class="kb__label">版本说明（可空）</label>
            <el-input v-model="form.versionNote" size="small" placeholder="本版改了什么" />
          </div>
        </div>
        <div class="kb__ingest-actions">
          <el-button size="small" :loading="submitting" @click="runPreview">试运行·切块预览</el-button>
          <el-button type="primary" size="small" :loading="submitting" @click="runIngest">正式录入</el-button>
        </div>

        <!-- ② 切块预览（试运行/录入共用） -->
        <div v-if="preview" class="kb__preview">
          <div class="kb__preview-meta">
            <span class="mono">{{ preview.docNo }}</span>
            <span class="kb__badge" :class="preview.namespace === 'PUBLIC' ? 'kb__badge--public' : 'kb__badge--private'">
              {{ preview.namespace === 'PUBLIC' ? '公开' : '私有' }}
            </span>
            <span class="kb__badge kb__badge--level">{{ levelLabel[preview.requiredLevel] ?? `V${preview.requiredLevel}` }}</span>
            <span class="tnum kb__preview-stat">{{ preview.totalChunks }} 块 · {{ preview.charCount }} 字 · sha256[:8] {{ preview.checksum.slice(0, 8) }}</span>
            <span v-if="preview.indexed" class="kb__badge kb__badge--ok">已入库</span>
            <span v-else class="kb__badge kb__badge--warn">试运行·未入库</span>
          </div>
          <el-table :data="preview.previews" size="small" max-height="320" class="kb__table">
            <el-table-column prop="seq" label="#" width="56" />
            <el-table-column prop="heading" label="面包屑" min-width="180" show-overflow-tooltip />
            <el-table-column prop="excerpt" label="切块正文" min-width="360" show-overflow-tooltip />
            <el-table-column prop="charCount" label="字符" width="72" />
          </el-table>
          <div v-if="preview.totalChunks > preview.previews.length" class="kb__preview-more tnum">
            仅显示前 {{ preview.previews.length }} 块 / 共 {{ preview.totalChunks }} 块
          </div>
        </div>
      </section>

      <!-- ③ 文档台账 -->
      <section class="kb__docs surface">
        <div class="kb__docs-head">
          <h3 class="kb__docs-title">文档台账</h3>
          <div class="kb__docs-filters">
            <el-select v-model="filterNamespace" size="small" class="kb__filter" placeholder="全部命名空间" clearable>
              <el-option label="公开" value="PUBLIC" />
              <el-option label="私有" value="PRIVATE" />
            </el-select>
            <span class="tnum kb__docs-count">{{ visibleDocs.length }} 份</span>
          </div>
        </div>
        <el-table
          v-loading="loading"
          :data="visibleDocs"
          size="small"
          class="kb__table"
          :row-class-name="({ row }: { row: KbDocumentSummary }) => (row.status !== 'ACTIVE' ? 'kb__row--dim' : '')"
        >
          <el-table-column prop="docNo" label="文档号" min-width="130" show-overflow-tooltip />
          <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
          <el-table-column prop="version" label="版本" width="64">
            <template #default="{ row }"><span class="tnum">v{{ row.version }}</span></template>
          </el-table-column>
          <el-table-column label="命名空间" width="140">
            <template #default="{ row }">
              <span class="kb__badge" :class="row.namespace === 'PUBLIC' ? 'kb__badge--public' : 'kb__badge--private'">
                {{ row.namespace === 'PUBLIC' ? '公开' : '私有' }}
              </span>
              <span v-if="row.namespace === 'PRIVATE' && row.allowedPrincipals" class="mono kb__principals">{{ row.allowedPrincipals }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可见等级" width="96">
            <template #default="{ row }">
              <span class="kb__badge kb__badge--level">{{ levelLabel[row.requiredLevel] ?? `V${row.requiredLevel}` }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="docType" label="类型" width="70" class-name="mono" />
          <el-table-column prop="domain" label="知识域" min-width="120" show-overflow-tooltip class-name="mono" />
          <el-table-column prop="chunkCount" label="切块" width="64" />
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <span class="kb__badge" :class="row.status === 'ACTIVE' ? 'kb__badge--ok' : row.status === 'DELETED' ? 'kb__badge--fail' : 'kb__badge--warn'">
                {{ statusLabel[row.status] ?? row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="录入时间" width="150">
            <template #default="{ row }"><span class="tnum kb__time">{{ fmtTime(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'ACTIVE'" link size="small" type="danger" @click="removeDoc(row)">下架</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="kb__empty">还没有知识文档——从上方录入第一份知识开始</div>
          </template>
        </el-table>
        <div v-if="detailLoading" class="kb__detail-loading">加载详情…</div>
      </section>

      <!-- ④ 文档详情（切块全量） -->
      <section v-if="detail" class="kb__detail surface">
        <div class="kb__detail-head">
          <h3 class="kb__detail-title">
            {{ detail.document.title }}
            <span class="tnum kb__detail-sub">v{{ detail.document.version }} · {{ detail.chunks.length }} 块 · {{ statusLabel[detail.document.status] }}</span>
          </h3>
          <el-button size="small" @click="detail = null">收起</el-button>
        </div>
        <div v-for="c in detail.chunks" :key="c.seq" class="kb__chunk">
          <div class="kb__chunk-head">
            <span class="tnum kb__chunk-seq">#{{ c.seq }}</span>
            <span class="mono kb__chunk-heading">{{ c.heading || '（无面包屑）' }}</span>
            <span class="tnum kb__chunk-chars">{{ c.charCount }} 字</span>
          </div>
          <pre class="kb__chunk-text">{{ c.text }}</pre>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.kb {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px 24px 40px;
}
.kb__bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}
.kb__title {
  margin: 0;
  font-size: 22px;
  letter-spacing: 0.02em;
}
.kb__sub {
  display: block;
  margin-top: 4px;
  color: var(--ink-300);
  font-size: 12px;
}
.kb__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---- 录入面板 ---- */
.kb__ingest-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}
.kb__ingest-title,
.kb__docs-title,
.kb__detail-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.kb__ingest-hint {
  color: var(--ink-300);
  font-size: 12px;
}
.kb__ingest-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px 16px;
}
.kb__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kb__field--file {
  grid-column: 1 / -1;
}
.kb__label {
  font-size: 12px;
  color: var(--ink-300);
}
.kb__file {
  display: none;
}
.kb__pick {
  width: fit-content;
  max-width: 420px;
  overflow: hidden;
  padding: 6px 14px;
  border: 1px dashed var(--ink-500);
  border-radius: 6px;
  background: var(--ink-700);
  color: var(--ink-100);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.kb__pick:hover {
  border-color: var(--signal);
  color: var(--signal);
}
.kb__supported {
  font-size: 11px;
  color: var(--ink-300);
}
.kb__ingest-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}

/* ---- 预览 ---- */
.kb__preview {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb__preview-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}
.kb__preview-stat {
  color: var(--ink-300);
}
.kb__preview-more {
  color: var(--ink-300);
  font-size: 12px;
}
.kb__badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--ink-500);
  font-size: 11px;
  color: var(--ink-100);
}
.kb__badge--public {
  border-color: var(--signal);
  color: var(--signal);
}
.kb__badge--private {
  border-color: var(--amber);
  color: var(--amber);
}
.kb__badge--level {
  border-color: var(--ink-500);
  color: var(--ink-100);
  background: var(--ink-700);
}
.kb__badge--ok {
  border-color: var(--signal);
  color: var(--signal);
}
.kb__badge--warn {
  border-color: var(--amber);
  color: var(--amber);
}
.kb__badge--fail {
  border-color: var(--crimson);
  color: var(--crimson);
}

/* ---- 台账 ---- */
.kb__docs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.kb__docs-filters {
  display: flex;
  align-items: center;
  gap: 10px;
}
.kb__filter {
  width: 160px;
}
.kb__docs-count {
  color: var(--ink-300);
  font-size: 12px;
}
.kb__principals {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ink-300);
}
.kb__time {
  font-size: 11px;
  color: var(--ink-300);
}
:deep(.kb__row--dim) {
  opacity: 0.5;
}
.kb__empty {
  padding: 24px 0;
  color: var(--ink-300);
  font-size: 13px;
}

/* ---- 详情 ---- */
.kb__detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.kb__detail-sub {
  margin-left: 10px;
  color: var(--ink-300);
  font-size: 12px;
  font-weight: 400;
}
.kb__detail-loading {
  margin-top: 8px;
  color: var(--ink-300);
  font-size: 12px;
}
.kb__chunk {
  margin-bottom: 12px;
}
.kb__chunk-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
  font-size: 12px;
}
.kb__chunk-seq {
  color: var(--signal);
}
.kb__chunk-heading {
  color: var(--ink-300);
}
.kb__chunk-chars {
  margin-left: auto;
  color: var(--ink-300);
}
.kb__chunk-text {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--ink-600);
  border-radius: 6px;
  background: var(--ink-900);
  color: var(--ink-100);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
