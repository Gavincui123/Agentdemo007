import { ref } from 'vue'

/**
 * 演示身份（Phase 21 客户等级可见性演示用）——前端身份切换器的数据源。
 *
 * <p>项目未接真鉴权（身份即 mock 数据，仅为展示）：切换器选定 userId 后随每轮对话请求
 * 带给后端，后端经 {@code MemberLevelService} 解析会员等级并决定知识库可见档位
 * （匿名 V0 只出公开档，V1~V5 递升）。映射与后端 mock 用户表对齐（10086/10010/10012/10013/10014）。
 *
 * <p>持久化于 localStorage（跨刷新记忆）；存储失败仅退化为内存态。模块级 ref 单例：
 * 切换后所有新对话轮即时生效（后端每请求重解析等级，无需刷新）。
 */
export interface DemoIdentity {
  /** 后端会员服务可解析的 userId；''=游客（不传 userId，匿名 V0 口径）。 */
  userId: string
  name: string
  /** 后端解析出的知识库可见档位（展示口径，与 KbLevel 对齐）。 */
  level: string
  desc: string
}

const GUEST: DemoIdentity = {
  userId: '',
  name: '游客',
  level: 'V0 公开',
  desc: '未登录：仅公开档知识可见（fail-closed）',
}

export const DEMO_IDENTITIES: DemoIdentity[] = [
  GUEST,
  { userId: '10010', name: '李四', level: 'V1 注册', desc: '注册客户：公开档 + V1 档' },
  { userId: '10012', name: '王五', level: 'V2 白银', desc: '白银会员：V2 及以下档' },
  { userId: '10013', name: '赵六', level: 'V3 黄金', desc: '黄金会员：V3 及以下档' },
  { userId: '10014', name: '钱七', level: 'V4 铂金', desc: '铂金会员：V4 及以下档' },
  { userId: '10086', name: '张三', level: 'V5 全量', desc: '全量会员：所有档位可见' },
]

const STORAGE_KEY = 'agentdemo.identity.userId'

function loadStoredUserId(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return '' // 存储不可用（隐私模式等）：退化为默认游客
  }
}

const userId = ref<string>(loadStoredUserId())

/** 当前身份展示对象（存储了未知 id 时回退游客展示——后端对未知主体同样 fail-closed V0）。 */
export function currentIdentity(): DemoIdentity {
  return DEMO_IDENTITIES.find((i) => i.userId === userId.value) ?? GUEST
}

/** 当前身份 userId（''=游客，请求体不带 userId 字段）。 */
export function identityUserId(): string {
  return userId.value
}

/** 切换演示身份并持久化。 */
export function setIdentity(id: string): void {
  userId.value = id
  try {
    localStorage.setItem(STORAGE_KEY, id)
  } catch {
    /* 存储失败仅内存态生效 */
  }
}
