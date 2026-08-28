import { useEffect, useState } from 'react'
import {
  BellRing,
  Bell,
  BookOpen,
  ClipboardCheck,
  FlaskConical,
  History,
  LayoutDashboard,
  Network,
  Search,
} from 'lucide-react'
import { cn } from '../lib/utils'
import { Badge, Input } from './ui'
import { useUnackedCount } from '../api/alarms'

export const PAGE_META = {
  dashboard: { title: '生产驾驶舱', crumb: '实时监控' },
  batches: { title: '批次管理', crumb: '生产执行' },
  quality: { title: '质量管理', crumb: '质量管控' },
  alarms: { title: '报警中心', crumb: '设备与报警' },
}

const NAV = [
  {
    group: '生产执行',
    items: [
      { key: 'dashboard', label: '生产驾驶舱', icon: LayoutDashboard },
      { key: 'batches', label: '批次管理', icon: FlaskConical },
    ],
  },
  {
    group: '质量管控',
    items: [
      { key: 'quality', label: '质量管理', icon: ClipboardCheck },
      { key: 'alarms', label: '报警中心', icon: BellRing, badgeTone: 'danger' },
    ],
  },
  {
    group: '平台',
    items: [
      { key: 'equipment', label: '设备监控', icon: Network, soon: true },
      { key: 'recipe', label: '配方管理', icon: BookOpen, soon: true },
      { key: 'trace', label: '追溯查询', icon: History, soon: true },
    ],
  },
]

function BrandMark(qoderProps) {
  return (
    <svg viewBox="0 0 24 24" className={["h-6 w-6", qoderProps?.className].filter(Boolean).join(" ")} aria-hidden="true" style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      <path d="M12 3.5 21 20H3Z" fill="var(--foreground)"  data-qoder-id="qel-path-79be1477" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-path-79be1477&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;BrandMark&quot;,&quot;elementRole&quot;:&quot;path&quot;,&quot;loc&quot;:{&quot;line&quot;:51,&quot;column&quot;:7}}"/>
    </svg>
  )
}

export function Sidebar({ active, onSelect, ...qoderProps }) {
  const { data: unacked = 0 } = useUnackedCount()
  return (
    <aside className={["flex w-60 shrink-0 flex-col border-r border-border", qoderProps?.className].filter(Boolean).join(" ")} data-component="sidebar" data-qoder-id="qel-sidebar-a6acd57e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-sidebar-a6acd57e&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;sidebar&quot;,&quot;loc&quot;:{&quot;line&quot;:58,&quot;column&quot;:5}}" style={qoderProps?.style}>
      <div className="flex items-center gap-2.5 px-4 pb-5 pt-5" data-qoder-id="qel-flex-72b67aff" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-72b67aff&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:59,&quot;column&quot;:7}}">
        <BrandMark  data-qoder-id="qel-brandmark-c643abdc" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-brandmark-c643abdc&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;brandmark&quot;,&quot;loc&quot;:{&quot;line&quot;:60,&quot;column&quot;:9}}"/>
        <div className="leading-none" data-qoder-id="qel-leading-none-05d6c9c1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-leading-none-05d6c9c1&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;leading-none&quot;,&quot;loc&quot;:{&quot;line&quot;:61,&quot;column&quot;:9}}">
          <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-e32c6c41" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-e32c6c41&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:62,&quot;column&quot;:11}}">FluxMES</div>
          <div className="num mt-1 text-[10px] uppercase tracking-wide text-faint" data-qoder-id="qel-num-dedffbfc" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-dedffbfc&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:63,&quot;column&quot;:11}}">Process Manufacturing</div>
        </div>
      </div>

      <nav className="flex-1 space-y-5 overflow-y-auto px-3 pb-4" data-qoder-id="qel-flex-1-3cb9ecd0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-1-3cb9ecd0&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;flex-1&quot;,&quot;loc&quot;:{&quot;line&quot;:67,&quot;column&quot;:7}}">
        {NAV.map((group) => (
          <div key={group.group} data-qoder-id="qel-div-1ee5ad5c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-1ee5ad5c&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:69,&quot;column&quot;:11}}">
            <div className="label-tech px-2.5 pb-1.5" data-qoder-id="qel-label-tech-dba30f75" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-dba30f75&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:70,&quot;column&quot;:13}}">{group.group}</div>
            <div className="space-y-0.5" data-qoder-id="qel-space-y-0-5-19c62bf6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-space-y-0-5-19c62bf6&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;space-y-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:71,&quot;column&quot;:13}}">
              {group.items.map((item) => {
                const Icon = item.icon
                const isActive = active === item.key
                if (item.soon) {
                  return (
                    <div
                      key={item.key}
                      className="flex cursor-not-allowed items-center gap-2.5 rounded-md px-2.5 py-1.5 text-[13px] font-medium text-muted-foreground opacity-50"
                     data-qoder-id="qel-flex-c93726e9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-c93726e9&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:77,&quot;column&quot;:21}}">
                      <Icon className="h-4 w-4"  data-qoder-id="qel-h-4-8535c3fa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-4-8535c3fa&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;h-4&quot;,&quot;loc&quot;:{&quot;line&quot;:81,&quot;column&quot;:23}}"/>
                      {item.label}
                      <span className="num ml-auto rounded border border-border px-1 py-px text-[10px] text-faint" data-qoder-id="qel-num-6cf2e895" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-6cf2e895&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:83,&quot;column&quot;:23}}">规划中</span>
                    </div>
                  )
                }
                return (
                  <button
                    key={item.key}
                    onClick={() => onSelect(item.key)}
                    className={cn(
                      'flex w-full items-center gap-2.5 rounded-md px-2.5 py-1.5 text-[13px] font-medium transition-colors',
                      isActive ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                    )}
                   data-qoder-id="qel-button-afc6577e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-afc6577e&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:88,&quot;column&quot;:19}}">
                    <Icon className="h-4 w-4"  data-qoder-id="qel-h-4-8435c267" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-4-8435c267&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;h-4&quot;,&quot;loc&quot;:{&quot;line&quot;:96,&quot;column&quot;:21}}"/>
                    {item.label}
                    {(() => {
                      const badgeValue = item.key === 'alarms' ? unacked : item.badge
                      return badgeValue ? (
                        <span className="num ml-auto rounded-full bg-danger-soft px-1.5 text-[11px] font-medium text-danger" data-qoder-id="qel-num-6ff2ed4e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-6ff2ed4e&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:99,&quot;column&quot;:23}}">
                          {badgeValue}
                        </span>
                      ) : null
                    })()}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
      </nav>

      <div className="px-3 pb-4" data-qoder-id="qel-px-3-c99abd6e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-c99abd6e&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:111,&quot;column&quot;:7}}">
        <div className="card card-pad" data-qoder-id="qel-card-7f62192c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-card-7f62192c&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;card&quot;,&quot;loc&quot;:{&quot;line&quot;:112,&quot;column&quot;:9}}">
          <div className="flex items-center justify-between" data-qoder-id="qel-flex-573ec238" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-573ec238&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:113,&quot;column&quot;:11}}">
            <span className="label-tech" data-qoder-id="qel-label-tech-ec8cfafa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-ec8cfafa&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:114,&quot;column&quot;:13}}">当前班次</span>
            <Badge tone="accent" data-qoder-id="qel-badge-1b26c1e2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-badge-1b26c1e2&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;badge&quot;,&quot;loc&quot;:{&quot;line&quot;:115,&quot;column&quot;:13}}">白班</Badge>
          </div>
          <div className="num mt-1.5 text-[13px]" data-qoder-id="qel-num-26b2308a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-26b2308a&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:117,&quot;column&quot;:11}}">08:00 – 16:00</div>
          <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-a76d8127" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-a76d8127&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:118,&quot;column&quot;:11}}">值班工艺员 · 陈志远</div>
        </div>
        <p className="num mt-3 text-center text-[10px] text-faint" data-qoder-id="qel-num-84eb8bf1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-84eb8bf1&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Sidebar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:120,&quot;column&quot;:9}}">FluxMES v4.2.1 · 清禾基地</p>
      </div>
    </aside>
  )
}

export function Topbar({ page, ...qoderProps }) {
  const meta = PAGE_META[page]
  const { data: unacked = 0 } = useUnackedCount()
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  const time = now.toLocaleTimeString('zh-CN', { hour12: false })

  return (
    <header className={["flex h-14 shrink-0 items-center gap-3 border-b border-border px-5", qoderProps?.className].filter(Boolean).join(" ")} data-component="topbar" data-qoder-id="qel-topbar-d1b09e61" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-topbar-d1b09e61&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;topbar&quot;,&quot;loc&quot;:{&quot;line&quot;:136,&quot;column&quot;:5}}" style={qoderProps?.style}>
      <div className="flex items-center gap-2 text-[13px]" data-qoder-id="qel-flex-218cf761" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-218cf761&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:137,&quot;column&quot;:7}}">
        <span className="text-muted-foreground" data-qoder-id="qel-text-muted-foreground-9aa8794b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-muted-foreground-9aa8794b&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;text-muted-foreground&quot;,&quot;loc&quot;:{&quot;line&quot;:138,&quot;column&quot;:9}}">清禾基地 · 一车间</span>
        <span className="text-faint" data-qoder-id="qel-text-faint-6ce57068" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-faint-6ce57068&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;text-faint&quot;,&quot;loc&quot;:{&quot;line&quot;:139,&quot;column&quot;:9}}">/</span>
        <span className="font-medium" data-qoder-id="qel-font-medium-01d6459c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-font-medium-01d6459c&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;font-medium&quot;,&quot;loc&quot;:{&quot;line&quot;:140,&quot;column&quot;:9}}">{meta.title}</span>
      </div>

      <div className="relative ml-auto hidden w-72 md:block" data-qoder-id="qel-relative-ba9e8f73" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-relative-ba9e8f73&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;relative&quot;,&quot;loc&quot;:{&quot;line&quot;:143,&quot;column&quot;:7}}">
        <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-faint"  data-qoder-id="qel-absolute-2df7cef2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-absolute-2df7cef2&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;absolute&quot;,&quot;loc&quot;:{&quot;line&quot;:144,&quot;column&quot;:9}}"/>
        <Input placeholder="搜索批次、设备、报警…" className="pl-8 pr-12"  data-qoder-id="qel-pl-8-bf7af7dd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pl-8-bf7af7dd&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;pl-8&quot;,&quot;loc&quot;:{&quot;line&quot;:145,&quot;column&quot;:9}}"/>
        <kbd className="num absolute right-2 top-1/2 -translate-y-1/2 rounded border border-border px-1.5 py-0.5 text-[10px] text-faint" data-qoder-id="qel-num-5487da0a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-5487da0a&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:146,&quot;column&quot;:9}}">
          ⌘K
        </kbd>
      </div>

      <div className="num hidden items-center gap-1.5 rounded-full border border-border px-3 py-1 text-xs text-muted-foreground lg:flex" data-qoder-id="qel-num-bfbfd907" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-bfbfd907&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:151,&quot;column&quot;:7}}">
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent"  data-qoder-id="qel-h-1-5-21345dc3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-1-5-21345dc3&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;h-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:152,&quot;column&quot;:9}}"/>
        2026-08-26 {time}
      </div>

      <button className="relative rounded-md p-2 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground" data-qoder-id="qel-relative-00013b1c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-relative-00013b1c&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;relative&quot;,&quot;loc&quot;:{&quot;line&quot;:156,&quot;column&quot;:7}}">
        <Bell className="h-4 w-4"  data-qoder-id="qel-h-4-c1579639" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-4-c1579639&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;h-4&quot;,&quot;loc&quot;:{&quot;line&quot;:157,&quot;column&quot;:9}}"/>
        {unacked > 0 && (
        <span className="num absolute right-1 top-1 flex h-3.5 min-w-3.5 items-center justify-center rounded-full bg-danger px-0.5 text-[9px] font-medium text-white" data-qoder-id="qel-num-886096f9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-886096f9&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:158,&quot;column&quot;:9}}">
          {unacked}
        </span>
        )}
      </button>

      <div className="flex items-center gap-2 border-l border-border pl-3" data-qoder-id="qel-flex-9c9b308c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-9c9b308c&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:163,&quot;column&quot;:7}}">
        <div className="flex h-7 w-7 items-center justify-center rounded-full bg-primary-soft text-xs font-medium text-primary" data-qoder-id="qel-flex-9f9b3545" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-9f9b3545&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:164,&quot;column&quot;:9}}">
          陈
        </div>
        <div className="hidden text-xs leading-tight xl:block" data-qoder-id="qel-hidden-f1cf1a1b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-hidden-f1cf1a1b&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;hidden&quot;,&quot;loc&quot;:{&quot;line&quot;:167,&quot;column&quot;:9}}">
          <div className="font-medium" data-qoder-id="qel-font-medium-3fee59d7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-font-medium-3fee59d7&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;font-medium&quot;,&quot;loc&quot;:{&quot;line&quot;:168,&quot;column&quot;:11}}">陈志远</div>
          <div className="text-faint" data-qoder-id="qel-text-faint-627bf6b9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-faint-627bf6b9&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;Topbar&quot;,&quot;elementRole&quot;:&quot;text-faint&quot;,&quot;loc&quot;:{&quot;line&quot;:169,&quot;column&quot;:11}}">值班工艺员</div>
        </div>
      </div>
    </header>
  )
}

export function PageHeader({ title, desc, actions, ...qoderProps }) {
  return (
    <div className={["mb-4 flex flex-wrap items-end justify-between gap-4", qoderProps?.className].filter(Boolean).join(" ")} data-component="page-header" style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      <div data-qoder-id="qel-div-d6609734" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-d6609734&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;PageHeader&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:179,&quot;column&quot;:7}}">
        <h1 className="text-xl font-semibold tracking-tight" data-qoder-id="qel-text-xl-f8da58dc" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-xl-f8da58dc&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;PageHeader&quot;,&quot;elementRole&quot;:&quot;text-xl&quot;,&quot;loc&quot;:{&quot;line&quot;:180,&quot;column&quot;:9}}">{title}</h1>
        <p className="mt-1 text-[13px] text-muted-foreground" data-qoder-id="qel-mt-1-b3aa144d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-1-b3aa144d&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;PageHeader&quot;,&quot;elementRole&quot;:&quot;mt-1&quot;,&quot;loc&quot;:{&quot;line&quot;:181,&quot;column&quot;:9}}">{desc}</p>
      </div>
      {actions && <div className="flex items-center gap-2" data-qoder-id="qel-flex-a370ca54" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-a370ca54&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/layout.jsx&quot;,&quot;componentName&quot;:&quot;PageHeader&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:183,&quot;column&quot;:19}}">{actions}</div>}
    </div>
  )
}
