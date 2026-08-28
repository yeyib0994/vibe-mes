import { useState } from 'react'
import { Sidebar, Topbar } from './components/layout'
import Dashboard from './pages/Dashboard'
import Batches from './pages/Batches'
import Quality from './pages/Quality'
import Alarms from './pages/Alarms'
import { useAlarmRealtime } from './api/alarms'

const PAGES = {
  dashboard: Dashboard,
  batches: Batches,
  quality: Quality,
  alarms: Alarms,
}

export default function App(qoderProps) {
  useAlarmRealtime()
  const [page, setPage] = useState('dashboard')
  const Page = PAGES[page] || Dashboard

  return (
    <div className={["flex h-full min-h-0 bg-background text-foreground", qoderProps?.className].filter(Boolean).join(" ")} data-component="app-shell" style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      <Sidebar active={page} onSelect={setPage}  data-qoder-id="qel-sidebar-ce86794e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-sidebar-ce86794e&quot;,&quot;filePath&quot;:&quot;react-vite/src/App.jsx&quot;,&quot;componentName&quot;:&quot;App&quot;,&quot;elementRole&quot;:&quot;sidebar&quot;,&quot;loc&quot;:{&quot;line&quot;:21,&quot;column&quot;:7}}"/>
      <div className="flex min-w-0 flex-1 flex-col" data-qoder-id="qel-flex-ff232593" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-ff232593&quot;,&quot;filePath&quot;:&quot;react-vite/src/App.jsx&quot;,&quot;componentName&quot;:&quot;App&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:22,&quot;column&quot;:7}}">
        <Topbar page={page}  data-qoder-id="qel-topbar-ebfe5a44" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-topbar-ebfe5a44&quot;,&quot;filePath&quot;:&quot;react-vite/src/App.jsx&quot;,&quot;componentName&quot;:&quot;App&quot;,&quot;elementRole&quot;:&quot;topbar&quot;,&quot;loc&quot;:{&quot;line&quot;:23,&quot;column&quot;:9}}"/>
        <main className="min-h-0 flex-1 overflow-y-auto" data-qoder-id="qel-min-h-0-c925c172" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-min-h-0-c925c172&quot;,&quot;filePath&quot;:&quot;react-vite/src/App.jsx&quot;,&quot;componentName&quot;:&quot;App&quot;,&quot;elementRole&quot;:&quot;min-h-0&quot;,&quot;loc&quot;:{&quot;line&quot;:24,&quot;column&quot;:9}}">
          <Page onNavigate={setPage}  data-qoder-id="qel-page-ea9e6a90" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-page-ea9e6a90&quot;,&quot;filePath&quot;:&quot;react-vite/src/App.jsx&quot;,&quot;componentName&quot;:&quot;App&quot;,&quot;elementRole&quot;:&quot;page&quot;,&quot;loc&quot;:{&quot;line&quot;:25,&quot;column&quot;:11}}"/>
        </main>
      </div>
    </div>
  )
}
