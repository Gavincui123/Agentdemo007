import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))

import { http } from './http'
import {
  fetchGateStatus,
  gateLogin,
  setAccessCode,
  getAccessCode,
  clearAccessCode,
  hasAccessCode,
} from './gate'

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('gate api（2026-09-18 部署闸门）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('fetchGateStatus GETs /api/gate/status and caches within session', async () => {
    getMock.mockResolvedValue({ enabled: true, dailyLimit: 20 })
    const s1 = await fetchGateStatus()
    const s2 = await fetchGateStatus()
    expect(getMock).toHaveBeenCalledTimes(1) // 会话内缓存
    expect(s1.enabled).toBe(true)
    expect(s2).toBe(s1)
    const s3 = await fetchGateStatus(true) // force 刷新
    expect(getMock).toHaveBeenCalledTimes(2)
    expect(s3).toEqual({ enabled: true, dailyLimit: 20 })
  })

  it('gateLogin POSTs code and returns remaining', async () => {
    postMock.mockResolvedValue({ remaining: 17 })
    const res = await gateLogin('my-code')
    expect(postMock).toHaveBeenCalledWith('/api/gate/login', { code: 'my-code' })
    expect(res.remaining).toBe(17)
  })

  it('access code persists in localStorage', () => {
    expect(hasAccessCode()).toBe(false)
    setAccessCode('my-code')
    expect(getAccessCode()).toBe('my-code')
    expect(hasAccessCode()).toBe(true)
    clearAccessCode()
    expect(getAccessCode()).toBeNull()
    expect(hasAccessCode()).toBe(false)
  })
})
