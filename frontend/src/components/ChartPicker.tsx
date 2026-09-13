import type { ChartRecord, Environment } from '../api/types'
import { envLabel, formatDate } from '../lib/labels'
import { Field } from './ui'

export function ChartPicker({ label, charts, value, onChange, env, optional, hint }: {
  label: string
  charts: ChartRecord[]
  value?: string
  onChange: (id: string) => void
  env?: Environment
  optional?: boolean
  hint?: string
}) {
  const sorted = [...charts].sort((a, b) =>
    a.app.localeCompare(b.app) || a.version.localeCompare(b.version, undefined, { numeric: true })
    || (env ? Number(b.environment === env) - Number(a.environment === env) : 0)
    || b.uploadedAt.localeCompare(a.uploadedAt))
  const apps = [...new Set(sorted.map((c) => c.app))]
  const selected = charts.find((c) => c.id === value)
  const envMismatch = env && selected && selected.environment !== env

  return (
    <Field label={label} hint={envMismatch ? `⚠ This chart is ${envLabel(selected.environment)}` : hint}>
      <select className="select" value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
        <option value="">{optional ? '— None —' : 'Select a chart…'}</option>
        {apps.map((app) => (
          <optgroup key={app} label={app}>
            {sorted.filter((c) => c.app === app).map((c) => (
              <option key={c.id} value={c.id}>
                {c.version} · {envLabel(c.environment)} · {c.revision} · {formatDate(c.uploadedAt)}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
    </Field>
  )
}
