import { describe, it, expect } from 'vitest'
import { extractTraceId, unwrapUnified, normalizeError, ApiError, type UnifiedResponse } from './http'

describe('extractTraceId', () => {
  it('reads X-Trace-Id header case-insensitively', () => {
    expect(extractTraceId({ 'x-trace-id': 'abc-123' })).toBe('abc-123')
    expect(extractTraceId({ 'X-TRACE-ID': 't-9' })).toBe('t-9')
  })

  it('returns null when absent', () => {
    expect(extractTraceId({ 'content-type': 'application/json' })).toBeNull()
  })
})

describe('unwrapUnified', () => {
  const ok: UnifiedResponse<{ reply: string }> = {
    code: 0,
    message: 'ok',
    traceId: 't1',
    timestamp: '2026-09-08T00:00:00Z',
    data: { reply: 'hi' },
  }

  it('returns inner data on code 0', () => {
    expect(unwrapUnified(ok)).toEqual({ reply: 'hi' })
  })

  it('throws ApiError on non-zero code carrying message/code/traceId', () => {
    const bad: UnifiedResponse = {
      code: 400,
      message: '请求参数错误',
      traceId: 't2',
      timestamp: '',
      data: null,
    }
    try {
      unwrapUnified(bad)
      throw new Error('should have thrown')
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError)
      const err = e as ApiError
      expect(err.code).toBe(400)
      expect(err.message).toBe('请求参数错误')
      expect(err.traceId).toBe('t2')
    }
  })

  it('uses fallback message when body message empty', () => {
    const bad: UnifiedResponse = { code: 500, message: '', traceId: 't3', timestamp: '', data: null }
    try {
      unwrapUnified(bad)
      throw new Error('should have thrown')
    } catch (e) {
      expect((e as ApiError).message).toBe('服务暂时不可用，请稍后重试')
    }
  })
})

describe('normalizeError', () => {
  it('derives ApiError from axios error with unified body', () => {
    const axiosError = {
      message: 'Request failed',
      response: {
        status: 400,
        data: { code: 400, message: '参数有误', traceId: 't4', timestamp: '', data: null },
      },
    }
    const err = normalizeError(axiosError)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe(400)
    expect(err.message).toBe('参数有误')
    expect(err.traceId).toBe('t4')
  })

  it('derives ApiError from network error without response', () => {
    const axiosError = { message: 'Network Error', request: {} }
    const err = normalizeError(axiosError)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe(0)
    expect(err.message).toBe('网络连接失败，请检查后重试')
  })
})
