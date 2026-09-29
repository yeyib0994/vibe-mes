import { useState } from 'react'
import { FlaskConical, LogIn, ShieldCheck } from 'lucide-react'
import { Badge, Button, Input } from '../components/ui'
import { useLogin } from '../api/auth'
import { ROLE_LABEL } from '../lib/auth'

const DEMO = [
  { u: 'admin', p: 'admin123', role: 'ADMIN' },
  { u: 'supervisor', p: 'super123', role: 'SUPERVISOR' },
  { u: 'qc', p: 'qc12345', role: 'QC' },
  { u: 'operator', p: 'op12345', role: 'OPERATOR' },
]

export default function Login({ onSuccess }: { onSuccess: (s: unknown) => void }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('admin123')
  const { login, pending, error } = useLogin(onSuccess)

  const submit = (e) => {
    e.preventDefault()
    login({ username, password })
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 text-foreground">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-md bg-primary-soft">
            <FlaskConical className="h-5 w-5 text-primary" />
          </div>
          <div className="leading-none">
            <div className="text-[15px] font-semibold tracking-tight">FluxMES</div>
            <div className="num mt-1 text-[10px] uppercase tracking-wide text-faint">
              Process Manufacturing
            </div>
          </div>
        </div>

        <form onSubmit={submit} className="card card-pad space-y-3">
          <div>
            <h1 className="text-[15px] font-semibold tracking-tight">登录</h1>
            <p className="mt-1 text-[13px] text-muted-foreground">食品级流程制造执行系统</p>
          </div>

          <label className="block">
            <span className="label-tech">用户名</span>
            <Input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="mt-1"
              autoComplete="username"
            />
          </label>

          <label className="block">
            <span className="label-tech">密码</span>
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-1"
              autoComplete="current-password"
            />
          </label>

          {error && (
            <div className="rounded-md border border-danger-soft bg-danger-soft px-2.5 py-2 text-[13px] text-danger">
              {error}
            </div>
          )}

          <Button type="submit" variant="primary" className="w-full" disabled={pending}>
            <LogIn className="h-4 w-4" />
            {pending ? '登录中…' : '登录'}
          </Button>
        </form>

        <div className="card mt-3 p-3">
          <div className="flex items-center gap-1.5 pb-2">
            <ShieldCheck className="h-3.5 w-3.5 text-muted-foreground" />
            <span className="label-tech">演示账号（RBAC 四角色）</span>
          </div>
          <div className="space-y-1">
            {DEMO.map((d) => (
              <button
                key={d.u}
                type="button"
                onClick={() => {
                  setUsername(d.u)
                  setPassword(d.p)
                }}
                className="flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-[13px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              >
                <span className="num">{d.u}</span>
                <Badge tone="muted" dot={false}>
                  {ROLE_LABEL[d.role]}
                </Badge>
                <span className="num ml-auto text-faint">{d.p}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
