import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createClient, setToken, getToken, clearToken } from '../client'

describe('api client', () => {
  beforeEach(() => { sessionStorage.clear() })

  it('injects Bearer token into request', async () => {
    setToken('tok-123')
    const api = createClient(() => {})
    const cfg = await (api.interceptors.request as any).handlers[0].fulfilled({ headers: {} })
    expect(cfg.headers.Authorization).toBe('Bearer tok-123')
  })

  it('clears token and calls onUnauthorized on 401', () => {
    setToken('tok-x')
    const onUnauth = vi.fn()
    const api = createClient(onUnauth)
    const rejected = (api.interceptors.response as any).handlers[0].rejected
    return rejected({ response: { status: 401 } }).catch(() => {
      expect(onUnauth).toHaveBeenCalled()
      expect(getToken()).toBeNull()
    })
  })

  it('token round-trips through sessionStorage', () => {
    setToken('abc'); expect(getToken()).toBe('abc'); clearToken(); expect(getToken()).toBeNull()
  })
})
