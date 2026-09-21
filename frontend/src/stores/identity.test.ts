import { describe, it, expect, beforeEach } from 'vitest'
import { DEMO_IDENTITIES, currentIdentity, identityUserId, setIdentity } from './identity'

describe('identity store（演示身份）', () => {
  beforeEach(() => {
    setIdentity('')
  })

  it('defaults to guest anonymous（V0 口径，请求体不带 userId）', () => {
    expect(identityUserId()).toBe('')
    expect(currentIdentity().level).toBe('V0 公开')
  })

  it('switches identity and persists to localStorage', () => {
    setIdentity('10086')
    expect(identityUserId()).toBe('10086')
    expect(currentIdentity().name).toBe('张三')
    expect(currentIdentity().level).toBe('V5 全量')
    expect(localStorage.getItem('agentdemo.identity.userId')).toBe('10086')
  })

  it('unknown stored id falls back to guest display（后端对未知主体 fail-closed V0）', () => {
    setIdentity('nobody')
    expect(currentIdentity().userId).toBe('')
    expect(identityUserId()).toBe('nobody')
  })

  it('offers the full demo ladder（游客+五档会员）', () => {
    expect(DEMO_IDENTITIES.map((i) => i.userId)).toEqual(['', '10010', '10012', '10013', '10014', '10086'])
  })
})
