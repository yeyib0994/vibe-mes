import { useMemo, useState } from 'react'
import {
  ArrowRight,
  BookOpen,
  Check,
  GitBranch,
  History,
  Plus,
  ShieldAlert,
  Timer,
} from 'lucide-react'
import { Badge, Button, Card, Tabs } from '../components/ui'
import { PageHeader } from '../components/layout'
import { recipeStatusMap } from '../data/mes'
import { useRecipes } from '../api/recipes'
import { VersionControlPanel } from '../components/recipe-panel'
import StalenessBadge from '../components/StalenessBadge'
import { cn } from '../lib/utils'

const TAB_ITEMS = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '生效中' },
  { key: 'draft', label: '草稿' },
  { key: 'obsolete', label: '已停用' },
]

function StatCard({ label, value, unit, badge, sub }) {
  return (
    <Card className="card-pad" data-component="recipe-stat">
      <div className="flex items-center justify-between gap-2">
        <span className="label-tech">{label}</span>
        {badge}
      </div>
      <div className="mt-2.5 flex items-baseline gap-1.5">
        <span className="text-[28px] font-semibold leading-none tracking-tight">{value}</span>
        {unit && <span className="text-[13px] text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="num mt-2 text-[11px] text-faint">{sub}</div>}
    </Card>
  )
}

function RecipeRoute({ stages }) {
  return (
    <div className="flex flex-wrap items-center gap-y-2">
      {stages.map((s, i) => (
        <div key={s + i} className="flex items-center">
          {i > 0 && <ArrowRight className="mx-1 h-3 w-3 text-faint" />}
          <span className="inline-flex items-center rounded-full border border-border bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
            {i + 1}. {s}
          </span>
        </div>
      ))}
    </div>
  )
}

export default function Recipes() {
  const { data } = useRecipes()
  const recipes = data?.recipes ?? []
  const summary = data?.summary
  const activeBatchCount = data?.activeBatchCount ?? {}
  const [tab, setTab] = useState('all')
  const [key, setKey] = useState('R-CA-07@v3.2')

  const filtered = useMemo(
    () => recipes.filter((r) => tab === 'all' || r.status === tab),
    [recipes, tab],
  )
  const selected = useMemo(
    () => recipes.find((r) => `${r.code}@${r.version}` === key) ?? filtered[0] ?? recipes[0],
    [recipes, filtered, key],
  )
  const selId = selected ? `${selected.code}@${selected.version}` : null

  const counts = useMemo(() => {
    const c = { all: recipes.length }
    for (const r of recipes) c[r.status] = (c[r.status] || 0) + 1
    return c
  }, [recipes])
  const tabs = TAB_ITEMS.map((t) => ({ ...t, count: counts[t.key] || 0 }))

  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-recipes">
      <PageHeader
        title="配方管理"
        desc="工艺配方版本受控 · 关键参数与 HACCP 关键控制点绑定 · 变更留痕（合规 C2/C5）"
        actions={
          <>
            <Button variant="ghost">
              <History className="h-3.5 w-3.5" /> 变更审计
            </Button>
            <Button variant="primary">
              <Plus className="h-3.5 w-3.5" /> 新建配方版本
            </Button>
          </>
        }
      />
      <StalenessBadge generatedAt={data?.generatedAt} />

      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
        <StatCard
          label="受控配方"
          value={summary?.active ?? '—'}
          unit="个生效"
          badge={<Badge tone="accent" dot={false}><Check className="mr-0.5 h-3 w-3" />已验证</Badge>}
          sub={`草稿 ${summary?.draft ?? 0} · 停用 ${summary?.obsolete ?? 0}`}
        />
        <StatCard
          label="CCP 关键控制点"
          value={summary?.ccpTotal ?? '—'}
          unit="项"
          badge={<Badge tone="warn" dot={false}><ShieldAlert className="mr-0.5 h-3 w-3" />HACCP</Badge>}
          sub="灭菌温度/时间 · 中和终点 pH · 水分/湿度"
        />
        <StatCard
          label="平均配方版本数"
          value="2.4"
          unit="版/产品"
          badge={<Badge tone="primary" dot={false}>受控迭代</Badge>}
          sub="最新版本用于在产批次"
        />
        <StatCard
          label="配方电子化率"
          value="100"
          unit="%"
          badge={<Badge tone="accent" dot={false}>无纸化</Badge>}
          sub="配方下发至 MES 工单，禁止手工改参"
        />
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        {/* 配方列表 */}
        <Card data-component="recipe-list">
          <div className="flex items-center justify-between px-4 pb-1 pt-3">
            <Tabs items={tabs} active={tab} onChange={setTab} className="border-0" />
          </div>
          <div className="px-2 pb-2">
            {filtered.map((r) => {
              const id = `${r.code}@${r.version}`
              const isActive = id === selId
              const st = recipeStatusMap[r.status]
              return (
                <button
                  key={id}
                  onClick={() => setKey(id)}
                  className={cn(
                    'w-full rounded-md px-2.5 py-2.5 text-left transition-colors hover:bg-muted',
                    isActive && 'bg-muted',
                  )}
                >
                  <div className="flex items-center gap-2">
                    <span className="num text-xs text-primary">{r.code}</span>
                    <span className="num text-[11px] text-faint">{r.version}</span>
                    <Badge tone={st.tone} dot={false} className="ml-auto">{st.label}</Badge>
                  </div>
                  <div className="mt-1 truncate text-[13px] font-medium">{r.name}</div>
                  <div className="num mt-0.5 flex items-center gap-2 text-[11px] text-faint">
                    <span>{r.product}</span>
                    <span>·</span>
                    <span>投产 {r.usedBatches} 批</span>
                    {activeBatchCount[r.code] > 0 && (
                      <span className="text-accent">· 在制 {activeBatchCount[r.code]}</span>
                    )}
                  </div>
                </button>
              )
            })}
            {filtered.length === 0 && (
              <div className="px-2 py-8 text-center text-[13px] text-muted-foreground">该分类暂无配方</div>
            )}
          </div>
        </Card>

        {/* 配方详情 */}
        <div className="space-y-4 xl:col-span-2">
          {selected && (
            <>
              <Card className="card-pad" data-component="recipe-detail-head">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2.5">
                      <BookOpen className="h-4 w-4 text-primary" />
                      <span className="text-[17px] font-semibold tracking-tight">{selected.name}</span>
                      <Badge tone={recipeStatusMap[selected.status].tone} dot={false}>
                        {recipeStatusMap[selected.status].label}
                      </Badge>
                    </div>
                    <div className="num mt-1 text-xs text-muted-foreground">
                      {selected.code} · {selected.version} · 产品 {selected.product} · 理论收率 {selected.yield}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    {selected.status === 'draft' && <Button variant="primary" size="sm"><Check className="h-3.5 w-3.5" />批准生效</Button>}
                    {selected.status === 'active' && <Button variant="outline" size="sm">升版修订</Button>}
                    <Button variant="ghost" size="sm" disabled={selected.status !== 'active'}>下发工单</Button>
                  </div>
                </div>
                <div className="num mt-3 flex flex-wrap gap-x-6 gap-y-1 border-t border-border pt-3 text-[11px] text-faint">
                  <span>最近更新 {selected.updatedAt}</span>
                  <span>维护人 {selected.updatedBy}</span>
                  <span>累计投产 {selected.usedBatches} 批</span>
                </div>
              </Card>

              <Card className="card-pad" data-component="recipe-route">
                <div className="mb-3 text-[15px] font-semibold tracking-tight">工艺路线</div>
                <RecipeRoute stages={selected.stages} />
              </Card>

              <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
                {/* 关键参数 */}
                <Card className="card-pad lg:col-span-2" data-component="recipe-params">
                  <div className="mb-2 flex items-center justify-between">
                    <div className="text-[15px] font-semibold tracking-tight">关键工艺参数</div>
                    <span className="text-xs text-muted-foreground">
                      <span className="num text-foreground">{selected.params.filter((p) => p.ccp).length}</span> 项 CCP
                    </span>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-[13px]">
                      <thead>
                        <tr className="border-y border-border text-left text-xs text-muted-foreground">
                          <th className="py-2 pr-2 font-medium">工序</th>
                          <th className="py-2 pr-2 font-medium">参数</th>
                          <th className="py-2 pr-2 font-medium">控制范围</th>
                          <th className="py-2 font-medium">类型</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {selected.params.map((p) => (
                          <tr key={p.stage + p.name} className={cn(p.ccp && 'bg-warn-soft/40')}>
                            <td className="py-2.5 pr-2 text-muted-foreground">{p.stage}</td>
                            <td className="py-2.5 pr-2 font-medium">{p.name}</td>
                            <td className="num py-2.5 pr-2 text-[13px]">
                              {p.lo} ~ {p.hi}{p.unit && <span className="ml-1 text-[11px] text-faint">{p.unit}</span>}
                            </td>
                            <td className="py-2.5">
                              {p.ccp ? (
                                <span className="inline-flex items-center gap-1 text-xs font-medium text-warn">
                                  <ShieldAlert className="h-3 w-3" /> CCP
                                </span>
                              ) : (
                                <span className="text-xs text-faint">一般</span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>

                {/* 版本历史 */}
                <Card className="card-pad" data-component="recipe-history">
                  <div className="mb-2 flex items-center gap-1.5">
                    <GitBranch className="h-4 w-4 text-faint" />
                    <div className="text-[15px] font-semibold tracking-tight">版本历史</div>
                  </div>
                  <div className="space-y-0">
                    {selected.history.map((h, i) => (
                      <div key={h.v} className="relative flex gap-3 pb-4 last:pb-0">
                        {i < selected.history.length - 1 && (
                          <span className="absolute left-[5px] top-4 h-full w-px bg-border" />
                        )}
                        <span className={cn(
                          'mt-1 h-2.5 w-2.5 shrink-0 rounded-full ring-2 ring-card',
                          i === 0 ? 'bg-accent' : 'bg-border-strong',
                        )} />
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="num text-[13px] font-medium text-primary">{h.v}</span>
                            {i === 0 && <Badge tone="accent" dot={false}>当前</Badge>}
                          </div>
                          <div className="num mt-0.5 text-[11px] text-faint">{h.date} · {h.by}</div>
                          <div className="mt-1 text-xs text-muted-foreground">{h.note}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                  {selected.status === 'active' && (
                    <div className="num mt-1 flex items-center gap-1.5 rounded-md bg-muted px-2.5 py-2 text-[11px] text-faint">
                      <Timer className="h-3 w-3" /> 版本切换后旧批记录保持只读，不可回改
                    </div>
                  )}
                </Card>
              </div>

              {/* H2 · 版本受控：生命周期 + 变更影响 */}
              <VersionControlPanel code={selected.code} version={selected.version} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}
