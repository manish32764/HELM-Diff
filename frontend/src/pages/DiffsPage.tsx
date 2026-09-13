import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import type { FileView, LogicalDiff } from '../api/types'
import { DiffText } from '../components/DiffText'
import { Button, SearchInput, Segmented, Spinner } from '../components/ui'
import { closeTab, diffChannel, sidePath, sideTitle } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

type Kind = 'ALL' | LogicalDiff['kind']

const KIND: Record<LogicalDiff['kind'], [string, string]> = {
  CHANGED: ['Changed', 'tb-amber'],
  ADDED: ['Only in right', 'tb-green'],
  REMOVED: ['Only in left', 'tb-red'],
}

/** The logical differences of one file in their own tab; selecting one highlights it in the file compare tab. */
export function DiffsPage({ id, path }: { id: string; path: string }) {
  const view = useAsync(() => api.folderFile(id, path), [id, path])
  if (view.loading) return <Spinner />
  if (view.error) return <div className="page"><div className="banner banner-error">{view.error}</div></div>
  return <Diffs id={id} view={view.data!} />
}

function Diffs({ id, view }: { id: string; view: FileView }) {
  const [kind, setKind] = useState<Kind>('ALL')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const channel = useRef<BroadcastChannel | null>(null)
  const diffs = view.differences
  const leftTitle = sideTitle(view.leftName, view.leftLabel)
  const rightTitle = sideTitle(view.rightName, view.rightLabel)

  useEffect(() => {
    document.title = `Differences · ${view.name}`
    if (typeof BroadcastChannel === 'undefined') return
    const ch = new BroadcastChannel(diffChannel(id, view.path))
    ch.onmessage = (e) => {
      if (e.data?.type !== 'selected') return
      setSelected(e.data.diffId)
      document.getElementById(`dv-${e.data.diffId}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
    channel.current = ch
    return () => ch.close()
  }, [id, view.path, view.name])

  const list = useMemo(() => {
    const q = query.toLowerCase()
    return diffs.filter((d) => (kind === 'ALL' || d.kind === kind)
      && (!q || [d.path, d.left, d.right, d.description].some((t) => (t ?? '').toLowerCase().includes(q))))
  }, [diffs, kind, query])

  const choose = useCallback((d: LogicalDiff) => {
    setSelected(d.id)
    channel.current?.postMessage({ type: 'select', diffId: d.id })
    document.getElementById(`dv-${d.id}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === 'INPUT' || list.length === 0) return
      if (e.key !== 'n' && e.key !== 'p') return
      const i = list.findIndex((d) => d.id === selected)
      const next = i < 0 ? (e.key === 'n' ? 0 : list.length - 1) : (i + (e.key === 'n' ? 1 : -1) + list.length) % list.length
      choose(list[next])
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [list, selected, choose])

  const count = (k: LogicalDiff['kind']) => diffs.filter((d) => d.kind === k).length

  return (
    <div className="dv-page">
      <header className="dv-head">
        <div style={{ minWidth: 0 }}>
          <div className="eyebrow">Logical differences · {diffs.length}</div>
          <h1 className="dv-title mono">{view.name}</h1>
          <div className="dv-files">
            <span><span className="dv-side-tag left">Left</span><span className="mono">{sidePath(view.leftName, view.leftLabel, view.path)}</span></span>
            <span><span className="dv-side-tag right">Right</span><span className="mono">{sidePath(view.rightName, view.rightLabel, view.path)}</span></span>
          </div>
        </div>
        <Button onClick={() => closeTab(`/folders/${id}/file?path=${encodeURIComponent(view.path)}`)}>Close tab</Button>
      </header>

      <div className="dv-toolbar">
        <Segmented value={kind} onChange={setKind} options={[
          { value: 'ALL', label: 'All', count: diffs.length },
          { value: 'CHANGED', label: 'Changed', count: count('CHANGED') },
          { value: 'REMOVED', label: `Only in ${leftTitle}`, count: count('REMOVED') },
          { value: 'ADDED', label: `Only in ${rightTitle}`, count: count('ADDED') },
        ]} />
        <div className="spacer" />
        <span className="small faint">Click a difference to highlight it in the file tab · n / p</span>
        <SearchInput value={query} onChange={setQuery} placeholder="Find a setting or value" />
      </div>

      {diffs.length === 0 ? (
        <div className="dv-empty">{view.status === 'DIFFERS' ? view.reason : 'No logical differences — both files produce the same Kubernetes configuration.'}</div>
      ) : list.length === 0 ? (
        <div className="dv-empty">No differences match.</div>
      ) : (
        <div className="dv-list">
          {list.map((d, i) => {
            const [label, cls] = KIND[d.kind]
            return (
              <article key={d.id} id={`dv-${d.id}`} className={`dv-card dv-${d.kind.toLowerCase()} ${d.id === selected ? 'selected' : ''}`}
                onClick={() => choose(d)}>
                <div className="dv-card-head">
                  <span className="dv-num">{i + 1}</span>
                  <span className={`tbadge ${cls}`}>{label}</span>
                  <span className="dv-path mono">{d.path}</span>
                  <span className="dv-lines">
                    {lineText(leftTitle, d.leftStart, d.leftEnd, d.leftAnchor)}
                    {(d.leftStart || d.leftAnchor) && (d.rightStart || d.rightAnchor) ? '  ·  ' : ''}
                    {lineText(rightTitle, d.rightStart, d.rightEnd, d.rightAnchor)}
                  </span>
                </div>
                <div className="dv-values">
                  <DiffValue title={leftTitle} value={d.left} against={d.kind === 'CHANGED' ? d.right : undefined} tone="old" />
                  <DiffValue title={rightTitle} value={d.right} against={d.kind === 'CHANGED' ? d.left : undefined} tone="new" />
                </div>
                {d.description && d.description !== d.path && <div className="dv-desc">{d.description}</div>}
              </article>
            )
          })}
        </div>
      )}
    </div>
  )
}

function DiffValue({ title, value, against, tone }: { title: string; value?: string; against?: string; tone: 'old' | 'new' }) {
  return (
    <div>
      <div className="dv-value-label">{title}</div>
      {value === undefined || value === null
        ? <div className="dv-absent">Not present</div>
        : <pre className={`dv-pre ${tone}`}>{against != null ? <DiffText value={value} against={against} /> : value}</pre>}
    </div>
  )
}

function lineText(title: string, start: number, end: number, anchor: number) {
  if (start > 0) return `${title} line ${range(start, end)}`
  return anchor > 0 ? `${title} after line ${anchor}` : ''
}

function range(a: number, b: number) {
  return a === b || b <= 0 ? `${a}` : `${a}–${b}`
}
