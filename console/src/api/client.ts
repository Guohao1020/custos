import axios, { type AxiosInstance } from 'axios'

const TOKEN_KEY = 'custos_admin_token'
// baseURL：构建期可由 VITE_API_BASE 覆盖；默认同源（nginx 反代）或 dev 直连
const baseURL = import.meta.env.VITE_API_BASE ?? ''

export function getToken(): string | null { return sessionStorage.getItem(TOKEN_KEY) }
export function setToken(t: string): void { sessionStorage.setItem(TOKEN_KEY, t) }
export function clearToken(): void { sessionStorage.removeItem(TOKEN_KEY) }

export function createClient(onUnauthorized: () => void): AxiosInstance {
  const c = axios.create({ baseURL })
  c.interceptors.request.use((cfg) => {
    const t = getToken()
    if (t) cfg.headers.Authorization = `Bearer ${t}`
    return cfg
  })
  c.interceptors.response.use(
    (r) => r,
    (err) => {
      if (err?.response?.status === 401) { clearToken(); onUnauthorized() }
      return Promise.reject(err)
    },
  )
  return c
}
