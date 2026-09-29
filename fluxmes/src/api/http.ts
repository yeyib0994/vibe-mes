// 轻量 API 客户端：统一 baseURL、JSON 编解码、JWT 注入与错误降级（constitution「接口失败须有降级展示」）。
// 开发期经 Vite proxy 走同源 /api（见 vite.config.js），也可用 VITE_API_BASE 指定后端地址。
const BASE = import.meta.env.VITE_API_BASE ?? ''

import { clearSession, getToken } from '../lib/auth'

/** 401 订阅者：http 层不直接操作路由，由 App 监听决定渲染登录页。 */
const unauthorizedListeners = new Set<() => void>()
export function onUnauthorized(fn: () => void): () => void {
  unauthorizedListeners.add(fn)
  return () => {
    unauthorizedListeners.delete(fn)
  }
}

/**
 * 泛型化请求：调用点通过 `request<T>` 声明期望的响应体类型，
 * 未显式指定时回落为 `unknown`（禁止隐式 any 扩散）。
 */
export async function request<T = unknown>(
  path: string,
  { method = 'GET', body, signal }: { method?: string; body?: unknown; signal?: AbortSignal } = {},
): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
  })

  if (res.status === 401) {
    clearSession()
    unauthorizedListeners.forEach((fn) => fn())
    throw Object.assign(new Error('登录已失效，请重新登录'), { status: 401 })
  }

  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const data = await res.json()
      if (data?.message) message = data.message
    } catch {
      /* 非 JSON 错误体，保留状态码信息 */
    }
    const err = Object.assign(new Error(message), { status: res.status })
    throw err
  }
  if (res.status === 204) return null as T
  return res.json() as Promise<T>
}
