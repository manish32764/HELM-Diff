import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import type { FileView, LogicalDiff, SideState } from '../api/types'
import { CodePane, scrollToLine } from '../components/CodePane'
import type { LineMark } from '../components/CodePane'
import { Button, Spinner, Toggle } from '../components/ui'
import { modeFor } from '../lib/highlight'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

const STATUS_TEXT: Record<string, [string, string]> = {
  IDENTICAL: ['Identical', 'tb-green'],
  LOGICALLY_IDENTICAL: ['Logically same', 'tb-teal'],
  DIFFERS: ['Differs', 'tb-orange'],
  LEFT_ONLY: ['Left only', 'tb-solid-orange'],
  RIGHT_ONLY: ['Right only', 'tb-solid-blue'],
}

type Side = 'left' | 'right'

/** `initialDiff` (from `&diff=` in the URL) opens the page with differences shown: "all" or a difference id. */
export function FileComparePage({ id, path, initialDiff }: { id: string; path: string; initialDiff?: string }) {
  const view = useAsync(() => api.folderFile(id, path), [id, path])
  const back = useCallback(() => navigate(`/folders/${id}`), [id])

  if (view.loading) return <Spinner />
  if (view.error) {
    return (
      <div className="page">
        <Button onClick={back}>← Back to folders</Button>
        <div className="banner banner-error mt">{view.error}</div>
      </div>
    )
  }
  return <FileCompare id={id} view={view.data!} onBack={back} initialDiff={initialDiff} />
}

function FileCompare({ id, view, onBack, initialDiff }: { id: string; view: FileView; onBack: () => void; initialDiff?: string }) {
  const [showDiffs, setShowDiffs] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [sync, setSync] = useState(true)
  const leftRef = useRef<HTMLDivElement>(null)
  const rightRef = useRef<HTMLDivElement>(null)
  const splitRef = useRef<HTMLDivElement>(null)
  /** The pane the user is interacting with; only it drives the other pane, so they never fight. */
  const activePane = useRef<Side | null>(null)
  const programmatic = useRef(false)
  const diffs = view.differences
  const mode = modeFor(view.name)

  const marks = useMemo(() => {
    const left = new Map<number, LineMark>()
    const right = new Map<number, LineMark>()
    if (!showDiffs) return { left, right }
    const put = (map: Map<number, LineMark>, start: number, end: number, kind: LineMark['kind'], d: LogicalDiff) => {
      if (start <= 0) return
      for (let n = start; n <= Math.max(start, end); n++) {
        const existing = map.get(n)
        if (existing) {
          existing.ids.push(d.id)
          existing.selected ||= d.id === selected
          if (existing.kind !== kind) existing.kind = 'changed'
        } else {
          map.set(n, { kind, ids: [d.id], selected: d.id === selected })
        }
      }
    }
    for (const d of diffs) {
      put(left, d.leftStart, d.leftEnd, d.kind === 'CHANGED' ? 'changed' : 'removed', d)
      put(right, d.rightStart, d.rightEnd, d.kind === 'CHANGED' ? 'changed' : 'added', d)
    }
    return { left, right }
  }, [diffs, showDiffs, selected])

  const select = useCallback((d: LogicalDiff) => {
    setShowDiffs(true)
    setSelected(d.id)
    programmatic.current = true
    requestAnimationFrame(() => {
      scrollToLine(leftRef.current, d.leftStart)
      scrollToLine(rightRef.current, d.rightStart)
      setTimeout(() => { programmatic.current = false }, 120)
    })
    document.querySelector(`[data-diff="${d.id}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [])

  useEffect(() => {
    if (!initialDiff) return
    const d = view.differences.find((x) => x.id === initialDiff)
    if (d) select(d)
    else setShowDiffs(true)
  }, [initialDiff, view.differences, select])

  const index = diffs.findIndex((d) => d.id === selected)
  const step = useCallback((delta: number) => {
    if (diffs.length === 0) return
    const next = index < 0 ? (delta > 0 ? 0 : diffs.length - 1) : (index + delta + diffs.length) % diffs.length
    select(diffs[next])
  }, [diffs, index, select])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') return
      if (e.key === 'n') step(1)
      if (e.key === 'p') step(-1)
      if (e.key === 'Backspace' || (e.key === 'ArrowLeft' && e.altKey)) onBack()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step, onBack])

  const onScroll = (source: Side) => {
    if (!sync || programmatic.current) return
    if (activePane.current !== null && activePane.current !== source) return
    const from = source === 'left' ? leftRef.current : rightRef.current
    const to = source === 'left' ? rightRef.current : leftRef.current
    if (from && to && to.scrollTop !== from.scrollTop) to.scrollTop = from.scrollTop
  }

  const onLineClick = (ids: string[]) => {
    const d = diffs.find((x) => x.id === ids[0])
    if (d) select(d)
  }

  const openEnv = () => navigate(`/folders/${id}/env?path=${encodeURIComponent(view.path)}&scope=FILE`)
  const codeOnly = () => splitRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })

  const [statusText, statusClass] = STATUS_TEXT[view.status] ?? [view.status, 'tb-gray']

  return (
    <div className="fc-page">
      <div className="fc-bar">
        <Button onClick={onBack}>← Back to folders</Button>
        <div className="fc-path" title={view.path}>
          <span className="faint">{view.leftName} ↔ {view.rightName} /</span> <b className="mono">{view.path}</b>
        </div>
        <span className={`tbadge ${statusClass}`}>{statusText}</span>
      </div>

      <div className="fill-toolbar">
        <span className="small muted fc-reason">{view.reason}</span>
        <div className="spacer" />
        <Toggle checked={sync} onChange={setSync} label="Scroll together" />
        <Button onClick={openEnv} title="Compare environment variables and secrets side by side">⊞ Env variables &amp; secrets</Button>
        <Button onClick={codeOnly} title="Scroll down so only the two files are visible">⤓ Code only</Button>
        {showDiffs && diffs.length > 0 && (
          <div className="row" style={{ gap: 6 }}>
            <Button size="sm" onClick={() => step(-1)} title="Previous difference (p)">▲</Button>
            <span className="small muted nowrap">{index >= 0 ? index + 1 : '–'} / {diffs.length}</span>
            <Button size="sm" onClick={() => step(1)} title="Next difference (n)">▼</Button>
          </div>
        )}
        <Button variant={showDiffs ? 'primary' : 'secondary'} onClick={() => { setShowDiffs((v) => !v); setSelected(null) }}>
          {showDiffs ? 'Hide logical differences' : `Show logical differences${diffs.length ? ` (${diffs.length})` : ''}`}
        </Button>
      </div>

      {showDiffs && <DiffPanel view={view} selected={selected} onSelect={select} />}

      <div className="split-view screen" ref={splitRef}>
        <PaneHeader side="Left" root={view.leftName} label={view.leftLabel} state={view.leftState} />
        <div className="split-divider" />
        <PaneHeader side="Right" root={view.rightName} label={view.rightLabel} state={view.rightState} />

        <CodePane lines={view.leftLines} mode={mode} marks={marks.left} paneRef={leftRef}
          placeholder={placeholder(view, 'left')} onScroll={() => onScroll('left')}
          onActivate={() => { activePane.current = 'left' }} onLineClick={onLineClick} />
        <div className="split-divider" />
        <CodePane lines={view.rightLines} mode={mode} marks={marks.right} paneRef={rightRef}
          placeholder={placeholder(view, 'right')} onScroll={() => onScroll('right')}
          onActivate={() => { activePane.current = 'right' }} onLineClick={onLineClick} />
      </div>
    </div>
  )
}

function placeholder(view: FileView, side: Side): ReactNode | undefined {
  const state = side === 'left' ? view.leftState : view.rightState
  const root = side === 'left' ? view.leftName : view.rightName
  if (state === 'MISSING') return <><b>File does not exist</b><span>{view.path} is not in {root}</span></>
  if (view.binary) return <><b>Binary file</b><span>Content cannot be shown</span></>
  if (state === 'EMPTY') return <><b>File is empty</b></>
  return undefined
}

function PaneHeader({ side, root, label, state }: { side: string; root: string; label?: string; state: SideState }) {
  return (
    <div className="pane-head">
      <span className="pane-side">{side}</span>
      <span className="mono pane-root" title={root}>{root}</span>
      {label && <span className="faint small">({label})</span>}
      {state === 'MISSING' && <span className="tbadge tb-gray">Does not exist</span>}
      {state === 'EMPTY' && <span className="tbadge tb-gray">File empty</span>}
    </div>
  )
}

function DiffPanel({ view, selected, onSelect }: { view: FileView; selected: string | null; onSelect: (d: LogicalDiff) => void }) {
  const diffs = view.differences
  return (
    <div className="diff-panel">
      <div className="diff-panel-head">
        <b>Logical differences</b>
        <span className="small muted">Click a row to highlight its lines in both files</span>
        <div className="spacer" />
        <span className="legend">
          <span><span className="swatch sw-added" />Added (right)</span>
          <span><span className="swatch sw-removed" />Removed (left)</span>
          <span><span className="swatch sw-changed" />Changed</span>
        </span>
      </div>
      {diffs.length === 0 ? (
        <div className="diff-empty">
          {view.status === 'LEFT_ONLY' || view.status === 'RIGHT_ONLY'
            ? 'The file exists on one side only.'
            : view.status === 'DIFFERS'
              ? view.reason
              : 'No logical differences — both files produce the same Kubernetes configuration.'}
        </div>
      ) : (
        <div className="diff-list">
          <div className="diff-row diff-header">
            <span>Change</span>
            <span>Configuration</span>
            <span>Left value · {view.leftName}</span>
            <span>Right value · {view.rightName}</span>
            <span>Lines</span>
          </div>
          {diffs.map((d) => (
            <button key={d.id} data-diff={d.id} className={`diff-row diff-item ${d.id === selected ? 'selected' : ''}`} onClick={() => onSelect(d)}>
              <span>
                <span className={`tbadge ${d.kind === 'ADDED' ? 'tb-green' : d.kind === 'REMOVED' ? 'tb-red' : 'tb-amber'}`}>
                  {d.kind === 'ADDED' ? 'Added' : d.kind === 'REMOVED' ? 'Removed' : 'Changed'}
                </span>
              </span>
              <span className="diff-cell mono" title={d.path}>{d.path}</span>
              <span className="diff-cell">
                {d.left !== undefined && d.left !== null ? <span className="old">{d.left}</span> : <span className="faint">—</span>}
              </span>
              <span className="diff-cell">
                {d.right !== undefined && d.right !== null ? <span className="new">{d.right}</span> : <span className="faint">—</span>}
              </span>
              <span className="small faint nowrap">
                {d.leftStart > 0 ? `L${range(d.leftStart, d.leftEnd)}` : ''}
                {d.leftStart > 0 && d.rightStart > 0 ? ' ↔ ' : ''}
                {d.rightStart > 0 ? `R${range(d.rightStart, d.rightEnd)}` : ''}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function range(a: number, b: number) {
  return a === b || b <= 0 ? `${a}` : `${a}–${b}`
}
