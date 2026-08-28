/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        border: 'var(--border)',
        'border-strong': 'var(--border-strong)',
        input: 'var(--border-strong)',
        ring: 'var(--ring)',
        background: 'var(--background)',
        foreground: 'var(--foreground)',
        card: { DEFAULT: 'var(--card)' },
        muted: { DEFAULT: 'var(--muted)', foreground: 'var(--muted-foreground)' },
        faint: 'var(--faint)',
        primary: { DEFAULT: 'var(--seed-primary)', soft: 'var(--primary-soft)' },
        accent: { DEFAULT: 'var(--seed-accent)', soft: 'var(--accent-soft)' },
        warn: { DEFAULT: 'var(--warn)', soft: 'var(--warn-soft)' },
        danger: { DEFAULT: 'var(--danger)', soft: 'var(--danger-soft)' },
      },
      borderRadius: {
        lg: 'var(--seed-radius)',
        md: 'calc(var(--seed-radius) - 4px)',
        sm: 'calc(var(--seed-radius) - 6px)',
      },
      fontFamily: {
        sans: ['Geist', '-apple-system', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', 'sans-serif'],
        mono: ['Geist Mono', 'ui-monospace', 'SFMono-Regular', 'JetBrains Mono', 'Menlo', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
}
