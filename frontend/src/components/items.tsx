import type { ConfigItem, FieldChange } from '../api/types'
import { SOURCE_LABEL, sourceTone } from '../lib/labels'
import { useSourceViewer } from './SourceViewer'
import { Badge } from './ui'

export function SourceBadge({ item }: { item?: ConfigItem }) {
  if (!item) return <Badge tone="gray">Not present</Badge>
  if (item.block && item.source === 'BLOCK') return <Badge tone={item.enabled ? 'gray' : 'orange'}>{item.enabled ? 'Configured' : 'Disabled'}</Badge>
  return (
    <Badge tone={sourceTone(item)} title={item.sensitive ? 'Sensitive configuration' : undefined}>
      {item.sensitive && item.source === 'LITERAL' ? '⚠ ' : ''}{SOURCE_LABEL[item.source]}
    </Badge>
  )
}

/** Short value cell for tables. */
export function ItemValue({ item }: { item?: ConfigItem }) {
  if (!item) return <span className="faint">—</span>
  return (
    <div className="stack-v" style={{ gap: 4 }}>
      <div><SourceBadge item={item} /></div>
      <span className="value">{item.display}</span>
    </div>
  )
}

/** Full configuration of one item in one chart, with navigation to its Helm source. */
export function ItemPanel({ title, item, chartId }: { title: string; item?: ConfigItem; chartId?: string }) {
  const openSource = useSourceViewer()
  const fields = item ? Object.entries(item.fields) : []
  const showFields = item && (item.block || fields.length > 1)
  return (
    <div className={`item-panel ${item ? '' : 'absent'}`}>
      <div className="item-panel-head">
        <span className="muted" style={{ fontWeight: 500 }}>{title}</span>
        <SourceBadge item={item} />
      </div>
      <div className="item-panel-body">
        {!item && 'Not present in this chart'}
        {item && (
          <div className="stack-v" style={{ gap: 8 }}>
            <div className="value">{item.display}</div>
            {showFields && (
              <dl className="kv">
                {fields.map(([k, v]) => (
                  <div key={k} style={{ display: 'contents' }}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
            )}
            {item.locations.length > 0 && (
              <div className="small">
                {item.locations.slice(0, 4).map((l, i) => (
                  <div key={i}>
                    {chartId
                      ? <a href="#" onClick={(e) => { e.preventDefault(); openSource(chartId, l.file, l.line) }}>↗ {l.file}:{l.line}</a>
                      : <span className="faint">{l.file}:{l.line}</span>}
                    {l.path && <span className="key"> · {l.path}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export function FieldChanges({ changes, leftLabel = 'Before', rightLabel = 'After' }: { changes: FieldChange[]; leftLabel?: string; rightLabel?: string }) {
  if (changes.length === 0) return null
  return (
    <ul className="change-list">
      <li className="faint" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.05em' }}>
        <span>Setting</span><span>{leftLabel}</span><span /><span>{rightLabel}</span>
      </li>
      {changes.map((c) => (
        <li key={c.path}>
          <span className="key" style={{ color: 'var(--text)' }}>{c.path}</span>
          <span>{c.left !== undefined && c.left !== null ? <span className="old">{c.left}</span> : <span className="faint">—</span>}</span>
          <span className="faint">→</span>
          <span>{c.right !== undefined && c.right !== null ? <span className="new">{c.right}</span> : <span className="faint">—</span>}</span>
        </li>
      ))}
    </ul>
  )
}
