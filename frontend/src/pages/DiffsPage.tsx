import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { api } from '../api/client'
import type { FileView, SideDiff } from '../api/types'
import { DiffText } from '../components/DiffText'
import { SideCards } from '../components/Sides'
import { Button, SearchInput, Segmented, Spinner } from '../components/ui'
import { closeTab, diffChannel, sideLetter, sidePath, sidesKey, sideTitle, useVisibleSides } from '../lib/sides'
import { useAsync } from '../lib/useAsync'
import { diffLabel, range } from './FileComparePage'

type Kind = 'ALL' | SideDiff['kind']

/** The logical differences of one file in their own tab; selecting one highlights it in the file compare tab. */
export function DiffsPage({ id, path }: { id: string; path: string }) {
  const view = useAsync(() => api.folderFile(id, path), [id, path])
  if (view.loading) return <Spinner />
  if (view.error) return <div className="page"><div className="banner banner-error">{view.error}</div></div>
  return <Diffs id={id} view={view.data!} />
}

function Diffs({ id, view }: { id: string; view: FileView }) {
  const { visible, toggle } = useVisibleSides(id, view.sides.length)
  const key = sidesKey(visible)
  const comparison = view.comparisons[key]
  const diffs = useMemo(() => comparison?.differences ?? [], [comparison])
  const titles = useMemo(() => view.sides.map(sideTitle), [view.sides])
  const [kind, setKind] = useState<Kind>('ALL')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const channel = useRef<BroadcastChannel | null>(null)

  useEffect(() => setSelected(null), [key])

  useEffect(() => {
    document.title = `Differences · ${view.name}`
    if (typeof BroadcastChannel === 'undefined') return
    const ch = new BroadcastChannel(diffChannel(id, view.path))
    ch.onmessage = (e) => {
      if (e.data?.type !== 'selected' || e.data.key !== key) return
      setSelected(e.data.diffId)
      document.getElementById(`dv-${e.data.diffId}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
    channel.current = ch
    return () => ch.close()
  }, [id, view.path, view.name, key])

  const list = useMemo(() => {
    const q = query.toLowerCase()
    return diffs.filter((d) => (kind === 'ALL' || d.kind === kind)
      && (!q || [d.path, d.description, ...d.values].some((t) => (t ?? '').toLowerCase().includes(q))))
  }, [diffs, kind, query])

  const choose = useCallback((d: SideDiff) => {
    setSelected(d.id)
    channel.current?.postMessage({ type: 'select', diffId: d.id, key })
    document.getElementById(`dv-${d.id}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [key])

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

  const count = (k: SideDiff['kind']) => diffs.filter((d) => d.kind === k).length

  return (
    <div className="dv-page">
      <header className="dv-head">
        <div style={{ minWidth: 0 }}>
          <div className="eyebrow">Logical differences · {diffs.length}</div>
          <h1 className="dv-title mono">{view.name}</h1>
          <div className="dv-files">
            {visible.map((s) => (
              <span key={s} className={`side-tone-${s}`}>
                <span className="side-letter sm">{sideLetter(s)}</span> <span className="mono">{sidePath(view.sides[s], view.path)}</span>
              </span>
            ))}
          </div>
        </div>
        <Button onClick={() => closeTab(`/folders/${id}/file?path=${encodeURIComponent(view.path)}`)}>Close tab</Button>
      </header>

      {view.sides.length > 2 && <SideCards sides={view.sides} visible={visible} onToggle={toggle} compact />}

      <div className="dv-toolbar">
        <Segmented value={kind} onChange={setKind} options={[
          { value: 'ALL', label: 'All', count: diffs.length },
          { value: 'CHANGED', label: 'Changed', count: count('CHANGED') },
          { value: 'MISSING', label: 'Not in every folder', count: count('MISSING') },
        ]} />
        <div className="spacer" />
        <span className="small faint">Click a difference to highlight it in the file tab · n / p</span>
        <SearchInput value={query} onChange={setQuery} placeholder="Find a setting or value" />
      </div>

      {diffs.length === 0 ? (
        <div className="dv-empty">{comparison?.status === 'IDENTICAL' || comparison?.status === 'LOGICALLY_IDENTICAL'
          ? 'No logical differences — the files produce the same Kubernetes configuration.' : comparison?.reason}</div>
      ) : list.length === 0 ? (
        <div className="dv-empty">No differences match.</div>
      ) : (
        <div className="dv-list">
          {list.map((d, i) => {
            const [label, cls] = diffLabel(d, titles)
            const shown = visible.filter((s) => d.sides.includes(s))
            const base = d.values[shown[0]]
            return (
              <article key={d.id} id={`dv-${d.id}`} className={`dv-card dv-${d.kind.toLowerCase()} ${d.id === selected ? 'selected' : ''}`}
                onClick={() => choose(d)}>
                <div className="dv-card-head">
                  <span className="dv-num">{i + 1}</span>
                  <span className={`tbadge ${cls}`}>{label}</span>
                  <span className="dv-path mono">{d.path}</span>
                  <span className="dv-lines">{shown.map((s) => lineText(titles[s], d.starts[s], d.ends[s], d.anchors[s])).filter(Boolean).join('  ·  ')}</span>
                </div>
                <div className="dv-values" style={{ '--cols': shown.length } as CSSProperties}>
                  {shown.map((s, k) => (
                    <div key={s}>
                      <div className={`dv-value-label side-tone-${s}`}><span className="side-letter sm">{sideLetter(s)}</span> {titles[s]}</div>
                      {d.values[s] == null
                        ? <div className="dv-absent">Not present</div>
                        : <pre className={`dv-pre ${k === 0 ? 'old' : 'new'}`}>
                          {k > 0 && d.kind === 'CHANGED' && base != null ? <DiffText value={d.values[s]!} against={base} /> : d.values[s]}
                        </pre>}
                    </div>
                  ))}
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

function lineText(title: string, start: number, end: number, anchor: number) {
  if (start > 0) return `${title} line ${range(start, end)}`
  return anchor > 0 ? `${title} after line ${anchor}` : ''
}
