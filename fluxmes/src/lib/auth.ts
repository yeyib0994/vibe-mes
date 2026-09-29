// 会话管理：JWT + 角色（constitution C4 RBAC）。
// 登录态存 localStorage，http.ts 统一注入 Authorization；401 时清除会话回到登录页。
export type Session = {
  token: string
  username: string
  displayName: string
  role: 'ADMIN' | 'SUPERVISOR' | 'QC' | 'OPERATOR'
  userId?: number
}

const STORAGE_KEY = 'fluxmes.session'

/** 角色等级：数字越大权限越高；hasRole 判「不低于」该等级。 */
const ROLE_LEVEL: Record<string, number> = {
  OPERATOR: 1,
  QC: 2,
  SUPERVISOR: 3,
  ADMIN: 4,
}

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: '管理员',
  SUPERVISOR: '值班长',
  QC: '质检员',
  OPERATOR: '工艺员',
}

export function getSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export function setSession(s: Session) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
}

export function clearSession() {
  localStorage.removeItem(STORAGE_KEY)
}

export function getToken(): string | null {
  return getSession()?.token ?? null
}

/** 当前用户是否具备不低于 minRole 的权限；未登录一律 false。 */
export function hasRole(minRole: Session['role']): boolean {
  const s = getSession()
  if (!s) return false
  return (ROLE_LEVEL[s.role] ?? 0) >= (ROLE_LEVEL[minRole] ?? 99)
}
