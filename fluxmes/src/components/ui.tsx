import type { CSSProperties, HTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'
import { cn } from '../lib/utils'

/**
 * qoder 工作台生成的源码映射属性，透传到原生元素上，不影响行为。
 * 各组件统一继承宿主元素的 props，避免调用点出现「className 必填」这类误报。
 */
type QoderProps = {
  style?: CSSProperties
  'data-qoder-id'?: string
  'data-qoder-source'?: string
}

export type BadgeTone = 'muted' | 'primary' | 'accent' | 'warn' | 'warning' | 'danger' | 'success'
export type BarTone = 'primary' | 'accent' | 'warn' | 'danger'
export type ButtonVariant = 'primary' | 'outline' | 'ghost'
export type ButtonSize = 'sm' | 'md' | 'lg'

/* ---------- Card ---------- */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement> & QoderProps) {
  return <div className={cn('card', className)} {...props}  style={props?.style} data-qoder-id={props?.["data-qoder-id"]} data-qoder-source={props?.["data-qoder-source"]}/>
}

/* ---------- Badge ---------- */
// 注意：'warning' 是历史沿用名（全仓约 25 处调用），'warn' 为同一语义色的规范名；
// 两者都保留，避免一次性改动所有调用点。'success' 此前缺失，导致 Badge 落到 undefined 样式。
const badgeTones: Record<BadgeTone, string> = {
  muted: 'bg-muted text-muted-foreground',
  primary: 'bg-primary-soft text-primary',
  accent: 'bg-accent-soft text-accent',
  warn: 'bg-warn-soft text-warn',
  warning: 'bg-warn-soft text-warn',
  danger: 'bg-danger-soft text-danger',
  success: 'bg-success-soft text-success',
}
const dotTones: Record<BadgeTone, string> = {
  muted: 'bg-faint',
  primary: 'bg-primary',
  accent: 'bg-accent',
  warn: 'bg-warn',
  warning: 'bg-warn',
  danger: 'bg-danger',
  success: 'bg-success',
}

export function Badge({
  tone = 'muted',
  dot = true,
  pulse = false,
  className = '',
  children,
  ...qoderProps
}: {
  tone?: BadgeTone
  dot?: boolean
  pulse?: boolean
  className?: string
  children?: ReactNode
} & QoderProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium',
        badgeTones[tone],
        className,
      )}
     style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      {dot && <span className={cn('h-1.5 w-1.5 rounded-full', dotTones[tone], pulse && 'animate-pulse')}  data-qoder-id="qel-span-ef2482a5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-span-ef2482a5&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/ui.jsx&quot;,&quot;componentName&quot;:&quot;Badge&quot;,&quot;elementRole&quot;:&quot;span&quot;,&quot;loc&quot;:{&quot;line&quot;:33,&quot;column&quot;:15}}"/>}
      {children}
    </span>
  )
}

/* ---------- Button ---------- */
const btnVariants: Record<ButtonVariant, string> = {
  primary: 'bg-foreground text-background hover:bg-[var(--fg-hover)]',
  outline: 'border border-border-strong bg-transparent text-foreground hover:bg-muted',
  ghost: 'bg-transparent text-muted-foreground hover:bg-muted hover:text-foreground',
}
const btnSizes: Record<ButtonSize, string> = {
  sm: 'h-7 px-2.5 text-xs',
  md: 'h-8 px-3 text-[13px]',
  lg: 'h-9 px-4 text-sm',
}

export function Button({
  variant = 'outline',
  size = 'md',
  className,
  ...props
}: {
  variant?: ButtonVariant
  size?: ButtonSize
} & React.ButtonHTMLAttributes<HTMLButtonElement> & QoderProps) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50',
        btnVariants[variant],
        btnSizes[size],
        className,
      )}
      {...props}
     style={props?.style} data-qoder-id={props?.["data-qoder-id"]} data-qoder-source={props?.["data-qoder-source"]}/>
  )
}

/* ---------- Progress ---------- */
const barTones: Record<BarTone, string> = { primary: 'bg-primary', accent: 'bg-accent', warn: 'bg-warn', danger: 'bg-danger' }

export function Progress({ value = 0, tone = 'primary', className, ...qoderProps }: {
  value?: number
  tone?: BarTone
  className?: string
} & QoderProps) {
  return (
    <div className={cn('h-1.5 w-full overflow-hidden rounded-full bg-muted', className)} style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      <div
        className={cn('h-full rounded-full transition-all', barTones[tone])}
        style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
       data-qoder-id="qel-div-b84c8924" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-b84c8924&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/ui.jsx&quot;,&quot;componentName&quot;:&quot;Progress&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:71,&quot;column&quot;:7}}"/>
    </div>
  )
}

/* ---------- Input ---------- */
export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement> & QoderProps) {
  return (
    <input
      className={cn(
        'h-8 w-full rounded-md border border-border-strong bg-background px-3 text-[13px] text-foreground placeholder:text-faint focus:outline-none focus:ring-2 focus:ring-ring',
        className,
      )}
      {...props}
     style={props?.style} data-qoder-id={props?.["data-qoder-id"]} data-qoder-source={props?.["data-qoder-source"]}/>
  )
}

/* ---------- Tabs（Vercel 风格下划线选项卡） ---------- */
export type TabItem = { key: string; label: ReactNode; count?: number }

export function Tabs({ items, active, onChange, className, ...qoderProps }: {
  items: TabItem[]
  active: string
  onChange: (key: string) => void
  className?: string
} & QoderProps) {
  return (
    <div className={cn('flex items-center gap-1 border-b border-border ', className)} style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}>
      {items.map((t) => {
        const isActive = active === t.key
        return (
          <button
            key={t.key}
            onClick={() => onChange(t.key)}
            className={cn(
              'relative px-3 pb-2 pt-1 text-[13px] font-medium transition-colors',
              isActive ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
            )}
           data-qoder-id="qel-button-0fe39182" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-0fe39182&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/ui.jsx&quot;,&quot;componentName&quot;:&quot;Tabs&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:99,&quot;column&quot;:11}}">
            {t.label}
            {typeof t.count === 'number' && (
              <span className={cn('num ml-1.5 text-xs', isActive ? 'text-primary' : 'text-faint')} data-qoder-id="qel-span-63917692" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-span-63917692&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/ui.jsx&quot;,&quot;componentName&quot;:&quot;Tabs&quot;,&quot;elementRole&quot;:&quot;span&quot;,&quot;loc&quot;:{&quot;line&quot;:109,&quot;column&quot;:15}}">{t.count}</span>
            )}
            {isActive && <span className="absolute inset-x-1 -bottom-px h-0.5 rounded-full bg-foreground"  data-qoder-id="qel-absolute-6266ab72" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-absolute-6266ab72&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/ui.jsx&quot;,&quot;componentName&quot;:&quot;Tabs&quot;,&quot;elementRole&quot;:&quot;absolute&quot;,&quot;loc&quot;:{&quot;line&quot;:111,&quot;column&quot;:26}}"/>}
          </button>
        )
      })}
    </div>
  )
}

/* ---------- Status Dot ---------- */
export function Dot({ tone = 'muted', pulse = false, className, ...qoderProps }: {
  tone?: BadgeTone
  pulse?: boolean
  className?: string
} & QoderProps) {
  return <span className={cn('inline-block h-2 w-2 shrink-0 rounded-full', dotTones[tone], pulse && 'animate-pulse', className)}  style={qoderProps?.style} data-qoder-id={qoderProps?.["data-qoder-id"]} data-qoder-source={qoderProps?.["data-qoder-source"]}/>
}
