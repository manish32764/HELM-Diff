import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { statusLabel, statusTone } from '../lib/labels'
import type { Tone } from '../lib/labels'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'lg'
}

export function Button({ variant = 'secondary', size, className = '', ...rest }: ButtonProps) {
  return <button className={`btn btn-${variant} ${size ? `btn-${size}` : ''} ${className}`} {...rest} />
}

export function Card({ title, subtitle, actions, children, flush, className = '' }: {
  title?: ReactNode; subtitle?: ReactNode; actions?: ReactNode; children?: ReactNode; flush?: boolean; className?: string
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          <div>
            {title && <h2 className="card-title">{title}</h2>}
            {subtitle && <div className="card-subtitle">{subtitle}</div>}
          </div>
          {actions && <div className="actions">{actions}</div>}
        </div>
      )}
      <div className={`card-body ${flush ? 'flush' : ''}`}>{children}</div>
    </section>
  )
}

export function Badge({ tone = 'gray', children, dot, title }: { tone?: Tone; children: ReactNode; dot?: boolean; title?: string }) {
  return <span title={title} className={`badge tone-${tone} ${dot ? 'badge-dot' : ''}`}>{children}</span>
}

export function StatusBadge({ code, title }: { code?: string; title?: string }) {
  if (!code) return null
  return <Badge tone={statusTone(code)} dot title={title}>{statusLabel(code)}</Badge>
}

export function Segmented<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string; count?: number }[]; onChange: (v: T) => void
}) {
  return (
    <div className="segmented" role="tablist">
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={o.value === value}
          className={o.value === value ? 'active' : ''} onClick={() => onChange(o.value)}>
          {o.label}{o.count !== undefined && <span className="count">{o.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function StatTile({ label, value, tone, hint, onClick, active }: {
  label: string; value: ReactNode; tone?: Tone; hint?: string; onClick?: () => void; active?: boolean
}) {
  const content = (
    <>
      <span className="tile-label">{tone && <span className={`tile-mark mark-${tone}`} />}{label}</span>
      <span className="tile-value">{value}</span>
      {hint && <span className="tile-hint">{hint}</span>}
    </>
  )
  return onClick
    ? <button className={`tile ${active ? 'active' : ''}`} onClick={onClick}>{content}</button>
    : <div className="tile">{content}</div>
}

export function EmptyState({ icon = '◌', title, text, action }: { icon?: string; title: string; text?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <div className="empty-title">{title}</div>
      {text && <div>{text}</div>}
      {action && <div className="mt">{action}</div>}
    </div>
  )
}

export function Spinner() {
  return <div className="center"><div className="spinner" /></div>
}

export function PageHeader({ eyebrow, title, subtitle, actions }: { eyebrow?: string; title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1 className="page-title">{title}</h1>
        {subtitle && <div className="page-subtitle">{subtitle}</div>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  )
}

function useEscape(open: boolean, onClose: () => void) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
}

export function Modal({ open, title, subtitle, onClose, children, footer, width }: {
  open: boolean; title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; width?: number
}) {
  useEscape(open, onClose)
  if (!open) return null
  return (
    <>
      <div className="overlay" onClick={onClose} />
      <div className="modal" role="dialog" style={width ? { width: `min(${width}px, calc(100vw - 32px))` } : undefined}>
        <div className="modal-head">
          <div>
            <h3 className="modal-title">{title}</h3>
            {subtitle && <div className="card-subtitle">{subtitle}</div>}
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </>
  )
}

export function Drawer({ open, title, subtitle, onClose, children }: {
  open: boolean; title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode
}) {
  useEscape(open, onClose)
  if (!open) return null
  return (
    <>
      <div className="overlay" onClick={onClose} />
      <aside className="drawer" role="dialog">
        <div className="drawer-head">
          <div style={{ minWidth: 0 }}>
            <h3 className="modal-title">{title}</h3>
            {subtitle && <div className="card-subtitle">{subtitle}</div>}
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="toggle">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" />
      {label}
    </label>
  )
}

export function SearchInput({ value, onChange, placeholder = 'Search' }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <div className="search">
      <input className="input" value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

export function StackBar({ segments }: { segments: { value: number; tone: Tone; label: string }[] }) {
  const total = segments.reduce((s, x) => s + x.value, 0)
  return (
    <div className="stack" role="img" aria-label={segments.map((s) => `${s.label}: ${s.value}`).join(', ')}>
      {total > 0 && segments.filter((s) => s.value > 0).map((s) => (
        <span key={s.label} className={`mark-${s.tone}`} style={{ width: `${(s.value / total) * 100}%` }}
          title={`${s.label}: ${s.value}`} />
      ))}
    </div>
  )
}

// ── toasts ──

type ShowToast = (message: string, kind?: 'info' | 'error') => void
const ToastContext = createContext<ShowToast>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string; kind: 'info' | 'error' }[]>([])
  const show = useCallback<ShowToast>((message, kind = 'info') => {
    const id = Date.now() + Math.random()
    setToasts((t) => [...t, { id, message, kind }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4200)
  }, [])
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toast-stack">
        {toasts.map((t) => <div key={t.id} className={`toast ${t.kind}`}>{t.message}</div>)}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
