import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, Download, Plus, Search, TriangleAlert } from 'lucide-react'
import { Badge, Button, Card, Input, Progress, Tabs } from '../components/ui'
import { PageHeader } from '../components/layout'
import { batchStatusMap } from '../data/mes'
import { useBatches } from '../api/batches'
import StalenessBadge from '../components/StalenessBadge'
import { cn } from '../lib/utils'

const TAB_ITEMS = [
  { key: 'all', label: '全部' },
  { key: 'running', label: '进行中' },
  { key: 'waiting', label: '待检' },
  { key: 'done', label: '已完成' },
  { key: 'abnormal', label: '异常' },
]

const STAGE_STYLE = {
  done: 'border-border text-muted-foreground',
  active: 'border-primary bg-primary-soft text-primary',
  fail: 'border-danger bg-danger-soft text-danger',
  todo: 'border-border text-faint',
  none: 'hidden',
}

function StageFlow({ stages, ...qoderProps }) {
  return (
    <div className={["flex flex-wrap items-center gap-y-1.5", qoderProps?.className].filter(Boolean).join(" ")} style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      {stages.map(([name, st], i) => {
        if (st === 'none') return null
        return (
          <div key={name} className="flex items-center" data-qoder-id="qel-flex-4c1d9c27" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-4c1d9c27&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:30,&quot;column&quot;:11}}">
            {i > 0 && <span className="mx-1.5 h-px w-4 bg-border-strong"  data-qoder-id="qel-mx-1-5-9e4c0b51" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mx-1-5-9e4c0b51&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;mx-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:31,&quot;column&quot;:23}}"/>}
            <span
              className={cn(
                'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium',
                STAGE_STYLE[st],
              )}
             data-qoder-id="qel-span-4433057d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-span-4433057d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;span&quot;,&quot;loc&quot;:{&quot;line&quot;:32,&quot;column&quot;:13}}">
              {st === 'active' && <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary"  data-qoder-id="qel-h-1-5-f5763580" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-1-5-f5763580&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;h-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:38,&quot;column&quot;:35}}"/>}
              {st === 'done' && <span className="num text-[10px]" data-qoder-id="qel-num-540087ad" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-540087ad&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:39,&quot;column&quot;:33}}">✓</span>}
              {st === 'fail' && <TriangleAlert className="h-3 w-3"  data-qoder-id="qel-h-3-b2e3fcfa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-3-b2e3fcfa&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;StageFlow&quot;,&quot;elementRole&quot;:&quot;h-3&quot;,&quot;loc&quot;:{&quot;line&quot;:40,&quot;column&quot;:33}}"/>}
              {name}
            </span>
          </div>
        )
      })}
    </div>
  )
}

function BatchDetail({ batch, ...qoderProps }) {
  return (
    <div
      className={["card-pad border-b border-border", qoderProps?.className].filter(Boolean).join(" ")}
      style={{ ...({ background: 'color-mix(in srgb, var(--seed-surface) 38%, var(--seed-bg))' }), ...(qoderProps?.style) }}
     data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3" data-qoder-id="qel-grid-1ab00fe0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-grid-1ab00fe0&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;grid&quot;,&quot;loc&quot;:{&quot;line&quot;:56,&quot;column&quot;:7}}">
        <div className="lg:col-span-2" data-qoder-id="qel-lg-col-span-2-aa626910" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-lg-col-span-2-aa626910&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;lg-col-span-2&quot;,&quot;loc&quot;:{&quot;line&quot;:57,&quot;column&quot;:9}}">
          <div className="label-tech mb-2" data-qoder-id="qel-label-tech-df75cfe4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-df75cfe4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:58,&quot;column&quot;:11}}">工艺路线</div>
          <StageFlow stages={batch.stages}  data-qoder-id="qel-stageflow-9a30f189" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-stageflow-9a30f189&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;stageflow&quot;,&quot;loc&quot;:{&quot;line&quot;:59,&quot;column&quot;:11}}"/>
        </div>
        <div data-qoder-id="qel-div-a3266622" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-a3266622&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:61,&quot;column&quot;:9}}">
          <div className="label-tech mb-2" data-qoder-id="qel-label-tech-dc75cb2b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-dc75cb2b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:62,&quot;column&quot;:11}}">批次档案</div>
          <ul className="space-y-1 text-xs text-muted-foreground" data-qoder-id="qel-space-y-1-8efdb1d5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-space-y-1-8efdb1d5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;space-y-1&quot;,&quot;loc&quot;:{&quot;line&quot;:63,&quot;column&quot;:11}}">
            {batch.meta.map((m) => (
              <li key={m} className="flex items-start gap-2" data-qoder-id="qel-flex-6ac28873" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-6ac28873&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:65,&quot;column&quot;:15}}">
                <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-faint"  data-qoder-id="qel-mt-1-5-9cbe8159" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-1-5-9cbe8159&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;mt-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:66,&quot;column&quot;:17}}"/>
                {m}
              </li>
            ))}
          </ul>
          <div className="mt-3 flex gap-2" data-qoder-id="qel-mt-3-12b24003" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-3-12b24003&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;mt-3&quot;,&quot;loc&quot;:{&quot;line&quot;:71,&quot;column&quot;:11}}">
            <Button size="sm" data-qoder-id="qel-button-21d5b840" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-21d5b840&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:72,&quot;column&quot;:13}}">批生产记录</Button>
            <Button size="sm" variant="ghost" data-qoder-id="qel-button-18ceee50" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-18ceee50&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:73,&quot;column&quot;:13}}">
              <Download className="h-3.5 w-3.5"  data-qoder-id="qel-h-3-5-6f589405" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-3-5-6f589405&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;BatchDetail&quot;,&quot;elementRole&quot;:&quot;h-3-5&quot;,&quot;loc&quot;:{&quot;line&quot;:74,&quot;column&quot;:15}}"/> 导出
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function Batches() {
  const { data: batchesRaw } = useBatches()
  const batches = batchesRaw?.batches ?? []
  const [tab, setTab] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [expanded, setExpanded] = useState('B-260826-014')

  const counts = useMemo(() => {
    const c = { all: batches.length }
    for (const b of batches) c[b.status] = (c[b.status] || 0) + 1
    return c
  }, [])

  const tabs = TAB_ITEMS.map((t) => ({ ...t, count: counts[t.key] || 0 }))

  const filtered = batches.filter((b) => {
    const okTab = tab === 'all' || b.status === tab
    const kw = keyword.trim().toLowerCase()
    const okKw = !kw || `${b.id}${b.product}${b.equipment}${b.recipe}`.toLowerCase().includes(kw)
    return okTab && okKw
  })

  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-batches" data-qoder-id="qel-page-batches-0c728656" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-page-batches-0c728656&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;page-batches&quot;,&quot;loc&quot;:{&quot;line&quot;:104,&quot;column&quot;:5}}">
      <PageHeader
        title="批次管理"
        desc="本月已开工 24 批 · 平均批周期 38.5 h · 批记录电子化率 100%"
        actions={
          <>
            <Button variant="ghost">
              <Download className="h-3.5 w-3.5" /> 导入生产计划
            </Button>
            <Button variant="primary">
              <Plus className="h-3.5 w-3.5" /> 新建批次
            </Button>
          </>
        }
       data-qoder-id="qel-pageheader-77e411e2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pageheader-77e411e2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;pageheader&quot;,&quot;loc&quot;:{&quot;line&quot;:105,&quot;column&quot;:7}}"/>
      <StalenessBadge generatedAt={batchesRaw?.generatedAt} />

      <Card data-component="batch-table" data-qoder-id="qel-batch-table-9a9c8a8c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-batch-table-9a9c8a8c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;batch-table&quot;,&quot;loc&quot;:{&quot;line&quot;:120,&quot;column&quot;:7}}">
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 pt-3" data-qoder-id="qel-flex-56c2d2d8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-56c2d2d8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:121,&quot;column&quot;:9}}">
          <Tabs items={tabs} active={tab} onChange={setTab} className="border-0"  data-qoder-id="qel-border-0-6e475314" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-0-6e475314&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;border-0&quot;,&quot;loc&quot;:{&quot;line&quot;:122,&quot;column&quot;:11}}"/>
          <div className="relative w-64 pb-1" data-qoder-id="qel-relative-ed9f1ac3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-relative-ed9f1ac3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;relative&quot;,&quot;loc&quot;:{&quot;line&quot;:123,&quot;column&quot;:11}}">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-faint"  data-qoder-id="qel-absolute-d3437020" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-absolute-d3437020&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;absolute&quot;,&quot;loc&quot;:{&quot;line&quot;:124,&quot;column&quot;:13}}"/>
            <Input
              placeholder="按批号 / 产品 / 设备筛选"
              className="pl-8"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
             data-qoder-id="qel-pl-8-d89c854d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pl-8-d89c854d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;pl-8&quot;,&quot;loc&quot;:{&quot;line&quot;:125,&quot;column&quot;:13}}"/>
          </div>
        </div>

        <div className="overflow-x-auto" data-qoder-id="qel-overflow-x-auto-11855f84" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-overflow-x-auto-11855f84&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;overflow-x-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:134,&quot;column&quot;:9}}">
          <table className="w-full text-[13px]" data-qoder-id="qel-w-full-0ee229e8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-w-full-0ee229e8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;w-full&quot;,&quot;loc&quot;:{&quot;line&quot;:135,&quot;column&quot;:11}}">
            <thead data-qoder-id="qel-thead-597a7664" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-thead-597a7664&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;thead&quot;,&quot;loc&quot;:{&quot;line&quot;:136,&quot;column&quot;:13}}">
              <tr className="border-y border-border text-left text-xs text-muted-foreground" data-qoder-id="qel-border-y-d8aec419" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-y-d8aec419&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;border-y&quot;,&quot;loc&quot;:{&quot;line&quot;:137,&quot;column&quot;:15}}">
                <th className="px-4 py-2.5 font-medium" data-qoder-id="qel-px-4-51458561" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-51458561&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:138,&quot;column&quot;:17}}">批次号</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-b0e36049" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-b0e36049&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:139,&quot;column&quot;:17}}">产品</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-ade35b90" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-ade35b90&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:140,&quot;column&quot;:17}}">配方</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-aee35d23" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-aee35d23&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:141,&quot;column&quot;:17}}">设备</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-bbe3719a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-bbe3719a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:142,&quot;column&quot;:17}}">当前工序</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-bce3732d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-bce3732d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:143,&quot;column&quot;:17}}">关键参数</th>
                <th className="w-40 px-3 py-2.5 font-medium" data-qoder-id="qel-w-40-631ef999" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-w-40-631ef999&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;w-40&quot;,&quot;loc&quot;:{&quot;line&quot;:144,&quot;column&quot;:17}}">进度</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-c4ea3b8a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-c4ea3b8a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:145,&quot;column&quot;:17}}">状态</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-c3ea39f7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-c3ea39f7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:146,&quot;column&quot;:17}}">开工时间</th>
                <th className="px-4 py-2.5"  data-qoder-id="qel-px-4-c23de87f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-c23de87f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:147,&quot;column&quot;:17}}"/>
              </tr>
            </thead>
            <tbody className="divide-y divide-border" data-qoder-id="qel-divide-y-e2d718a2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-divide-y-e2d718a2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;divide-y&quot;,&quot;loc&quot;:{&quot;line&quot;:150,&quot;column&quot;:13}}">
              {filtered.map((b) => {
                const st = batchStatusMap[b.status]
                const isOpen = expanded === b.id
                return (
                  <FragmentRow
                    key={b.id}
                    batch={b}
                    st={st}
                    isOpen={isOpen}
                    onToggle={() => setExpanded(isOpen ? null : b.id)}
                   data-qoder-id="qel-fragmentrow-50a193d2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-fragmentrow-50a193d2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;fragmentrow&quot;,&quot;loc&quot;:{&quot;line&quot;:155,&quot;column&quot;:19}}"/>
                )
              })}
            </tbody>
          </table>
          {filtered.length === 0 && (
            <div className="px-4 py-10 text-center text-[13px] text-muted-foreground" data-qoder-id="qel-px-4-2e4c0ea9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-2e4c0ea9&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;Batches&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:167,&quot;column&quot;:13}}">没有匹配的批次</div>
          )}
        </div>
      </Card>
    </div>
  )
}

function FragmentRow({ batch: b, st, isOpen, onToggle }) {
  const progressTone = b.status === 'abnormal' ? 'danger' : b.status === 'waiting' ? 'warn' : b.status === 'done' ? 'accent' : 'primary'
  return (
    <>
      <tr
        className={cn('cursor-pointer transition-colors hover:bg-muted', isOpen && 'bg-muted')}
        onClick={onToggle}
       data-qoder-id="qel-tr-11143a74" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tr-11143a74&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;tr&quot;,&quot;loc&quot;:{&quot;line&quot;:179,&quot;column&quot;:7}}">
        <td className="num px-4 py-3 font-medium text-primary" data-qoder-id="qel-num-fd4a7373" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-fd4a7373&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:183,&quot;column&quot;:9}}">{b.id}</td>
        <td className="px-3 py-3" data-qoder-id="qel-px-3-d913f7ce" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-d913f7ce&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:184,&quot;column&quot;:9}}">{b.product}</td>
        <td className="num px-3 py-3 text-muted-foreground" data-qoder-id="qel-num-ff483802" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-ff483802&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:185,&quot;column&quot;:9}}">{b.recipe}</td>
        <td className="px-3 py-3 text-muted-foreground" data-qoder-id="qel-px-3-4d10dcd3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-4d10dcd3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:186,&quot;column&quot;:9}}">{b.equipment}</td>
        <td className="px-3 py-3" data-qoder-id="qel-px-3-4e10de66" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-4e10de66&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:187,&quot;column&quot;:9}}">{b.stage}</td>
        <td className="num px-3 py-3 text-xs text-muted-foreground" data-qoder-id="qel-num-fe48366f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-fe48366f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:188,&quot;column&quot;:9}}">{b.params}</td>
        <td className="px-3 py-3" data-qoder-id="qel-px-3-5010e18c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-5010e18c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:189,&quot;column&quot;:9}}">
          <div className="flex items-center gap-2" data-qoder-id="qel-flex-2b5550df" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-2b5550df&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:190,&quot;column&quot;:11}}">
            <Progress value={b.progress} tone={progressTone} className="flex-1"  data-qoder-id="qel-flex-1-7e9ca996" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-1-7e9ca996&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;flex-1&quot;,&quot;loc&quot;:{&quot;line&quot;:191,&quot;column&quot;:13}}"/>
            <span className="num w-9 text-right text-xs" data-qoder-id="qel-num-a2f9519f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-a2f9519f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:192,&quot;column&quot;:13}}">{b.progress}%</span>
          </div>
        </td>
        <td className="px-3 py-3" data-qoder-id="qel-px-3-5410e7d8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-5410e7d8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:195,&quot;column&quot;:9}}">
          <Badge tone={st.tone} pulse={b.status === 'abnormal'} data-qoder-id="qel-badge-b8c413f7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-badge-b8c413f7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;badge&quot;,&quot;loc&quot;:{&quot;line&quot;:196,&quot;column&quot;:11}}">{st.label}</Badge>
        </td>
        <td className="num px-3 py-3 text-xs text-muted-foreground" data-qoder-id="qel-num-79452679" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-79452679&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:198,&quot;column&quot;:9}}">{b.start}</td>
        <td className="px-4 py-3 text-right" data-qoder-id="qel-px-4-415d8dc7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-415d8dc7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:199,&quot;column&quot;:9}}">
          {isOpen ? (
            <ChevronDown className="ml-auto h-4 w-4 text-faint"  data-qoder-id="qel-ml-auto-da9bb171" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ml-auto-da9bb171&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;ml-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:201,&quot;column&quot;:13}}"/>
          ) : (
            <ChevronRight className="ml-auto h-4 w-4 text-faint"  data-qoder-id="qel-ml-auto-4960f07c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ml-auto-4960f07c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;ml-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:203,&quot;column&quot;:13}}"/>
          )}
        </td>
      </tr>
      {isOpen && (
        <tr data-qoder-id="qel-tr-00189cdf" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tr-00189cdf&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;tr&quot;,&quot;loc&quot;:{&quot;line&quot;:208,&quot;column&quot;:9}}">
          <td colSpan={10} className="p-0" data-qoder-id="qel-p-0-b257fd8d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-p-0-b257fd8d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;p-0&quot;,&quot;loc&quot;:{&quot;line&quot;:209,&quot;column&quot;:11}}">
            <BatchDetail batch={b}  data-qoder-id="qel-batchdetail-b16efd09" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-batchdetail-b16efd09&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Batches.jsx&quot;,&quot;componentName&quot;:&quot;FragmentRow&quot;,&quot;elementRole&quot;:&quot;batchdetail&quot;,&quot;loc&quot;:{&quot;line&quot;:210,&quot;column&quot;:13}}"/>
          </td>
        </tr>
      )}
    </>
  )
}
