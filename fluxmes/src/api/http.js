// 轻量 API 客户端：统一 baseURL、JSON 编解码与错误降级（constitution「接口失败须有降级展示」）。
// 开发期经 Vite proxy 走同源 /api（见 vite.config.js），也可用 VITE_API_BASE 指定后端地址。
const BASE = import.meta.env.VITE_API_BASE ?? ''

export async function request(path, { method = 'GET', body, signal } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    signal,
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const data = await res.json()
      if (data?.message) message = data.message
    } catch {
      /* 非 JSON 错误体，保留状态码信息 */
    }
    const err = new Error(message)
    err.status = res.status
    throw err
  }
  if (res.status === 204) return null
  return res.json()
}
