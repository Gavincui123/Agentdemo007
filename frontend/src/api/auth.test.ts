import { describe, it, expect, beforeEach } from 'vitest'
import {
  getAdminToken,
  setAdminToken,
  hasAdminToken,
  clearAdminToken,
  attachAdminToken,
} from './auth'

describe('admin token storage', () => {
  beforeEach(() => localStorage.clear())

  it('round-trips token through localStorage', () => {
    expect(getAdminToken()).toBeNull()
    expect(hasAdminToken()).toBe(false)

    setAdminToken('dev-admin-token')
    expect(getAdminToken()).toBe('dev-admin-token')
    expect(hasAdminToken()).toBe(true)

    clearAdminToken()
    expect(getAdminToken()).toBeNull()
    expect(hasAdminToken()).toBe(false)
  })

  it('empty string is treated as no token', () => {
    setAdminToken('')
    expect(hasAdminToken()).toBe(false)
  })
})

describe('attachAdminToken', () => {
  beforeEach(() => localStorage.clear())

  it('attaches X-Admin-Token header when token present, preserving others', () => {
    setAdminToken('dev-admin-token')
    const out = attachAdminToken({ headers: { 'Content-Type': 'application/json' } })
    const h = out.headers as Record<string, string>
    expect(h['X-Admin-Token']).toBe('dev-admin-token')
    expect(h['Content-Type']).toBe('application/json')
  })

  it('leaves config unchanged when no token', () => {
    const out = attachAdminToken({ headers: {} })
    expect((out.headers as Record<string, string>)['X-Admin-Token']).toBeUndefined()
  })

  it('adds headers object when absent', () => {
    setAdminToken('tok')
    const out = attachAdminToken({})
    expect((out.headers as Record<string, string>)['X-Admin-Token']).toBe('tok')
  })
})
