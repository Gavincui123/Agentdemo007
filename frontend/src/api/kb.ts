import { http } from './http'

/**
 * 知识库 API 模块（[[kb-ingest-design]]·任务2/3 前端对接）。
 *
 * <p>对接后端 {@code /admin/kb/*}（KbAdminController）：试运行预览（解析→清洗→切块，
 * 不入库）、正式录入（嵌入+版本收口）、文档台账/详情、下架。强类型接口对齐后端 record
 * 投影（④收口：禁止 Map，字段一一映射）。经 {@link http} 拦截器解包 UnifiedResponse，
 * 失败抛 ApiError（话术 message）；请求注入 X-Admin-Token（/admin/** 鉴权）。
 */

/** 切块预览项——对齐后端 {@code KbIngestService.ChunkPreview}。 */
export interface KbChunkPreview {
  seq: number
  heading: string
  excerpt: string
  charCount: number
}

/** 录入/试运行结果——对齐后端 {@code KbIngestService.KbIngestResult}。 */
export interface KbIngestResult {
  documentId: number | null
  docNo: string
  title: string
  namespace: 'PUBLIC' | 'PRIVATE'
  requiredLevel: number
  version: number
  supersededVersion: number | null
  docType: string
  domain: string | null
  chunkCount: number
  charCount: number
  checksum: string
  indexed: boolean
  previews: KbChunkPreview[]
  totalChunks: number
}

/** 文档台账项——对齐后端 {@code KbAdminController.KbDocumentSummary}。 */
export interface KbDocumentSummary {
  id: number
  docNo: string
  title: string
  namespace: 'PUBLIC' | 'PRIVATE'
  requiredLevel: number
  allowedPrincipals: string | null
  docType: string
  domain: string | null
  version: number
  status: 'ACTIVE' | 'SUPERSEDED' | 'DELETED'
  chunkCount: number
  charCount: number
  versionNote: string | null
  createdBy: string | null
  createdAt: string | null
  supersededAt: string | null
}

/** 切块详情——对齐后端 {@code KbAdminController.KbChunkView}。 */
export interface KbChunkView {
  seq: number
  heading: string | null
  text: string
  charCount: number
  createdAt: string | null
}

/** 文档详情——对齐后端 {@code KbAdminController.KbDocumentDetail}。 */
export interface KbDocumentDetail {
  document: KbDocumentSummary
  chunks: KbChunkView[]
}

/** 录入表单字段（文件单独传）。requiredLevel 必选（Phase 21 三层保险第一层）。 */
export interface KbIngestForm {
  docNo?: string
  title?: string
  namespace: 'PUBLIC' | 'PRIVATE'
  requiredLevel: string
  allowedPrincipals?: string
  domain?: string
  versionNote?: string
}

/** 可见等级选项（对齐后端 KbLevel 枚举 V0~V5 有界词表）。 */
export const KB_LEVEL_OPTIONS = [
  { value: 'V0', label: 'V0 公开（匿名可见）' },
  { value: 'V1', label: 'V1 注册客户' },
  { value: 'V2', label: 'V2 白银会员' },
  { value: 'V3', label: 'V3 黄金会员' },
  { value: 'V4', label: 'V4 铂金会员' },
  { value: 'V5', label: 'V5 全量' },
] as const

/** 支持的文件扩展名（与后端解析器注册表一致；输入框 accept 用）。 */
export const KB_SUPPORTED_EXTENSIONS = '.md,.markdown,.txt,.html,.htm,.pdf,.docx,.xlsx,.xlsm,.csv,.tsv,.json'

function toFormData(file: File, form: KbIngestForm): FormData {
  const fd = new FormData()
  fd.append('file', file)
  if (form.docNo?.trim()) fd.append('docNo', form.docNo.trim())
  if (form.title?.trim()) fd.append('title', form.title.trim())
  fd.append('namespace', form.namespace)
  fd.append('requiredLevel', form.requiredLevel)
  if (form.allowedPrincipals?.trim()) fd.append('allowedPrincipals', form.allowedPrincipals.trim())
  if (form.domain?.trim()) fd.append('domain', form.domain.trim())
  if (form.versionNote?.trim()) fd.append('versionNote', form.versionNote.trim())
  return fd
}

/** 试运行：解析→清洗→切块预览（不入库不索引）。 */
export async function previewDocument(file: File, form: KbIngestForm): Promise<KbIngestResult> {
  return http.post('/admin/kb/preview', toFormData(file, form)) as unknown as Promise<KbIngestResult>
}

/** 正式录入（同 namespace+docNo 重灌即换版：旧版 SUPERSEDED + 向量删除）。 */
export async function ingestDocument(file: File, form: KbIngestForm): Promise<KbIngestResult> {
  return http.post('/admin/kb/documents', toFormData(file, form)) as unknown as Promise<KbIngestResult>
}

/** 文档台账（namespace/status 过滤）。 */
export async function listDocuments(params?: {
  namespace?: string
  status?: string
}): Promise<KbDocumentSummary[]> {
  return http.get('/admin/kb/documents', { params }) as unknown as Promise<KbDocumentSummary[]>
}

/** 文档详情（含全部切块）。 */
export async function getDocument(id: number): Promise<KbDocumentDetail> {
  return http.get(`/admin/kb/documents/${id}`) as unknown as Promise<KbDocumentDetail>
}

/** 下架文档（幂等：删向量 + DELETED + 目录快照移除）。返回删除向量条数口径。 */
export async function deleteDocument(id: number): Promise<number> {
  const r = await http.delete(`/admin/kb/documents/${id}`) as unknown as { removedVectorCount: number }
  return r.removedVectorCount
}
