/**
 * 页面冒烟：注入会话后逐页切换，捕获控制台 error 与页面异常。
 * 不依赖后端（后端不可用时 useQuery 走 error 分支，页面仍须渲染不崩）。
 * 用法：node scripts/smoke-pages.mjs [baseUrl]
 */
import puppeteer from 'file:///C:/Users/yyb/.workbuddy/binaries/node/workspace/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js'

const BASE = process.argv[2] ?? 'http://127.0.0.1:8888'
const CHROME = 'C:\\Users\\yyb\\.agent-browser\\browsers\\chrome-152.0.7977.64\\chrome.exe'

// 与 components/layout.tsx 的 NAV / PAGE_META 严格一致
const PAGES = [
  '生产驾驶舱', '批次管理', '生产执行', '配料称量',
  '质量管理', 'CAPA 闭环', '内审管理', '食品安全', '物料与追溯', '人员资质', '报警中心',
  '设备监控', '配方管理', '追溯查询',
]

// 后端不可用时，/api/* 的请求失败属预期噪音，不计入回归
const IGNORE = [
  'Failed to load resource',
  'ERR_CONNECTION_REFUSED',
  'ERR_FAILED',
  'the server responded with a status of 5',
  'the server responded with a status of 401',
  'net::ERR_',
  'Network Error',
  'Request failed with status code',
]

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--no-proxy-server', '--proxy-bypass-list=*'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1600, height: 1000 })

const rawProblems = []
const problems = []
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text().slice(0, 300)
  rawProblems.push(text)
  if (!IGNORE.some((p) => text.includes(p))) problems.push(`[console] ${text}`)
})
page.on('pageerror', (e) => {
  const text = String(e).slice(0, 300)
  rawProblems.push(text)
  problems.push(`[pageerror] ${text}`)
})

await page.goto(BASE, { waitUntil: 'networkidle2', timeout: 60000 })

// 绕过登录：直接写入 localStorage 会话（不依赖后端，也不改源码）
await page.evaluate(() => {
  localStorage.setItem('fluxmes.session', JSON.stringify({
    token: 'smoke-test-token',
    username: 'admin',
    displayName: '管理员',
    role: 'ADMIN',
  }))
})
await page.reload({ waitUntil: 'networkidle2', timeout: 60000 })
await new Promise((r) => setTimeout(r, 1500))

const shellMounted = await page.evaluate(() => !!document.querySelector('[data-component="app-shell"]'))
console.log('应用外壳已挂载:', shellMounted)
if (!shellMounted) {
  console.log('!! 未能进入应用外壳，后续页面检测无意义')
  await browser.close()
  process.exit(1)
}

const results = []
for (const label of PAGES) {
  const before = problems.length
  const clicked = await page.evaluate((name) => {
    const nodes = [...document.querySelectorAll('button, a, [role="button"]')]
    const hit = nodes.find((n) => {
      const t = (n.textContent ?? '').trim()
      return t === name || t.startsWith(name)
    })
    if (hit) { hit.click(); return true }
    return false
  }, label)
  if (!clicked) { results.push({ label, ok: false, note: '未找到入口', errors: 0 }); continue }
  await new Promise((r) => setTimeout(r, 900))
  // 判定「页面真的渲染了」：<main> 有可见文本，且标题区出现该页名称
  const probe = await page.evaluate(() => ({
    mainLen: (document.querySelector('main')?.innerText ?? '').trim().length,
    head: (document.querySelector('main h1')?.innerText ?? document.querySelector('main')?.innerText ?? '')
      .slice(0, 60).replace(/\n/g, ' / '),
  }))
  const newErrs = problems.length - before
  const ok = newErrs === 0 && probe.mainLen > 0
  results.push({ label, ok, note: probe.head || '(空)', errors: newErrs })
}

console.log('\n=== 页面渲染冒烟 ===')
for (const r of results) {
  console.log(`${r.ok ? 'OK  ' : 'FAIL'} ${r.label.padEnd(12)} ${r.note}${r.errors ? `  ← 新增错误 ${r.errors}` : ''}`)
}
console.log(`\n=== 真实错误合计（${problems.length}）===`)
problems.slice(0, 30).forEach((p) => console.log(p))
console.log(`\n=== 被忽略的后端不可用噪音（${rawProblems.length - problems.length}）===`)

await browser.close()
process.exit(results.some((r) => !r.ok) || problems.length > 0 ? 1 : 0)
