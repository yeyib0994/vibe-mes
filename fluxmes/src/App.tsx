import { useEffect, useState } from 'react'
import { Sidebar, Topbar } from './components/layout'
import Dashboard from './pages/Dashboard'
import Batches from './pages/Batches'
import Quality from './pages/Quality'
import Alarms from './pages/Alarms'
import Equipment from './pages/Equipment'
import Recipes from './pages/Recipes'
import Trace from './pages/Trace'
import FoodSafety from './pages/FoodSafety'
import Materials from './pages/Materials'
import Weighing from './pages/Weighing'
import Execution from './pages/Execution'
import Personnel from './pages/Personnel'
import Capa from './pages/Capa'
import Audit from './pages/Audit'
import Login from './pages/Login'
import { useAlarmRealtime } from './api/alarms'
import { getSession, type Session } from './lib/auth'
import { onUnauthorized } from './api/http'
import { queryClient } from './lib/queryClient'

/** 页面键（与 Sidebar 的 NAV 项一一对应）。 */
export type PageKey =
  | 'dashboard'
  | 'batches'
  | 'quality'
  | 'foodsafety'
  | 'materials'
  | 'weighing'
  | 'execution'
  | 'personnel'
  | 'capa'
  | 'audit'
  | 'alarms'
  | 'equipment'
  | 'recipe'
  | 'trace'

/** 所有页面共享的最小 props 契约（页面按需声明自己用到的部分）。 */
export type PageProps = {
  onNavigate?: (key: PageKey | string) => void
  session?: Session | null
}

const PAGES: Record<PageKey, React.ComponentType<PageProps>> = {
  dashboard: Dashboard,
  batches: Batches,
  quality: Quality,
  foodsafety: FoodSafety,
  materials: Materials,
  weighing: Weighing,
  execution: Execution,
  personnel: Personnel,
  capa: Capa,
  audit: Audit,
  alarms: Alarms,
  equipment: Equipment,
  recipe: Recipes,
  trace: Trace,
}

export default function App(qoderProps: { className?: string; style?: React.CSSProperties } = {}) {
  // 会话守卫（轻量路由守卫）：无 JWT 会话时渲染登录页；
  // http 层遇 401（token 过期/失效）回调 onUnauthorized → 清除会话回到登录页。
  const [session, setSession] = useState<Session | null>(() => getSession())
  const [page, setPage] = useState<PageKey>('dashboard')
  useEffect(() => onUnauthorized(() => setSession(null)), [])
  useAlarmRealtime()

  if (!session) {
    return (
      <Login
        onSuccess={(s: Session) => {
          queryClient.clear() // 换账号后清空旧数据缓存
          setSession(s)
        }}
      />
    )
  }

  const Page = PAGES[page] || Dashboard

  return (
    <div
      className={['flex h-full min-h-0 bg-background text-foreground', qoderProps?.className]
        .filter(Boolean)
        .join(' ')}
      data-component="app-shell"
      style={qoderProps?.style}
    >
      <Sidebar active={page} onSelect={setPage} session={session} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar page={page} session={session} />
        <main className="min-h-0 flex-1 overflow-y-auto">
          <Page onNavigate={(key) => setPage(key as PageKey)} session={session} />
        </main>
      </div>
    </div>
  )
}
