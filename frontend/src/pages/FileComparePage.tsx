import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import type { FileView, LogicalDiff, SideState } from '../api/types'
import { alignBlocks, CodePane } from '../components/CodePane'
import type { LineMark } from '../components/CodePane'
import { ChevronDownIcon, ChevronUpIcon, MaximizeIcon, PopOutIcon, RestoreIcon } from '../components/Icons'
import { Button, Spinner, Toggle } from '../components/ui'
import { modeFor } from '../lib/highlight'
import { navigate, openTab } from '../lib/router'
import { diffChannel, sidePath, sideTitle } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

const STATUS_TEXT: Record<string, [string, string]> = {
  IDENTICAL: ['Identical', 'tb-green'],
  LOGICALLY_IDENTICAL: ['Logically same', 'tb-teal'],
  DIFFERS: ['Differs', 'tb-orange'],
  LEFT_ONLY: ['Left only', 'tb-solid-orange'],
  RIGHT_ONLY: ['Right only', 'tb-solid-blue'],
}

type Side = 'left' | 'right'
type PanelState = 'normal' | 'max' | 'collapsed'

/** `initialDiff` (from `&diff=` in the URL) opens the page with differences shown: "all" or a difference id. */
export function FileComparePage({ id, path, initialDiff }: { id: string; path: string; initialDiff?: string }) {
  const view = useAsync(() => api.folderFile(id, path), [id, path])
  const top = path.includes('/') ? path.slice(0, path.indexOf('/')) : ''
  const back = useCallback(() => navigate(top ? `/folders/${id}/chart?root=${encodeURIComponent(top)}` : `/folders/${id}`), [id, top])

  if (view.loading) return <Spinner />
  if (view.error) {
    return (
      <div className="page">
        <Button onClick={back}>← Back</Button>
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
  const [codeOnly, setCodeOnly] = useState(false)
  const [panel, setPanel] = useState<PanelState>('normal')
  /** Size to return to when the collapsed panel is expanded again. */
  const openSize = useRef<'normal' | 'max'>('normal')
  const leftRef = useRef<HTMLDivElement>(null)
  const rightRef = useRef<HTMLDivElement>(null)
  /** The pane the user is interacting with; only it drives the other pane, so they never fight. */
  const activePane = useRef<Side | null>(null)
  const programmatic = useRef(false)
  const lastTop = useRef<Record<Side, number>>({ left: 0, right: 0 })
  const channel = useRef<BroadcastChannel | null>(null)
  const diffs = view.differences
  const mode = modeFor(view.name)
  const leftTitle = sideTitle(view.leftName, view.leftLabel)
  const rightTitle = sideTitle(view.rightName, view.rightLabel)

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
    // where the selected block is missing, mark the line it would follow
    const current = diffs.find((d) => d.id === selected)
    if (current) {
      if (current.leftStart === 0 && current.leftAnchor > 0 && !left.has(current.leftAnchor)) {
        left.set(current.leftAnchor, { kind: 'anchor', ids: [current.id], selected: true })
      }
      if (current.rightStart === 0 && current.rightAnchor > 0 && !right.has(current.rightAnchor)) {
        right.set(current.rightAnchor, { kind: 'anchor', ids: [current.id], selected: true })
      }
    }
    return { left, right }
  }, [diffs, showDiffs, selected])

  const select = useCallback((d: LogicalDiff) => {
    setShowDiffs(true)
    setSelected(d.id)
    programmatic.current = true
    requestAnimationFrame(() => {
      alignBlocks([
        { pane: leftRef.current, start: d.leftStart || d.leftAnchor || d.rightStart, end: d.leftStart ? d.leftEnd : 0 },
        { pane: rightRef.current, start: d.rightStart || d.rightAnchor || d.leftStart, end: d.rightStart ? d.rightEnd : 0 },
      ])
      // the scroll events of this alignment may arrive late: they must not be replayed on the other pane
      lastTop.current = { left: leftRef.current?.scrollTop ?? 0, right: rightRef.current?.scrollTop ?? 0 }
      setTimeout(() => { programmatic.current = false }, 150)
    })
    document.querySelector(`[data-diff="${d.id}"]`)?.scrollIntoView({ block: 'nearest' })
    channel.current?.postMessage({ type: 'selected', diffId: d.id })
  }, [])

  // a differences tab opened from here selects differences in this tab
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return
    const ch = new BroadcastChannel(diffChannel(id, view.path))
    ch.onmessage = (e) => {
      if (e.data?.type !== 'select') return
      const d = view.differences.find((x) => x.id === e.data.diffId)
      if (d) select(d)
    }
    channel.current = ch
    return () => {
      ch.close()
      channel.current = null
    }
  }, [id, view.path, view.differences, select])

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

  const resizePanel = (next: PanelState) => {
    if (next !== 'collapsed') openSize.current = next
    setPanel(next)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') return
      if (e.key === 'n') step(1)
      if (e.key === 'p') step(-1)
      if (e.key === 'Escape') {
        if (panel === 'max') resizePanel('normal')
        else if (codeOnly) setCodeOnly(false)
      }
      if (e.key === 'Backspace' || (e.key === 'ArrowLeft' && e.altKey)) onBack()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step, onBack, panel, codeOnly])

  /** Scrolling together moves both panes by the same distance, so blocks aligned by Next / Previous stay level. */
  const onScroll = (source: Side) => {
    const from = source === 'left' ? leftRef.current : rightRef.current
    const to = source === 'left' ? rightRef.current : leftRef.current
    if (!from) return
    const delta = from.scrollTop - lastTop.current[source]
    lastTop.current[source] = from.scrollTop
    if (!sync || programmatic.current || !to || delta === 0) return
    if (activePane.current !== null && activePane.current !== source) return
    to.scrollTop += delta
    lastTop.current[source === 'left' ? 'right' : 'left'] = to.scrollTop
  }

  const onLineClick = (ids: string[]) => {
    const d = diffs.find((x) => x.id === ids[0])
    if (d) select(d)
  }

  const toggleDiffs = (show: boolean) => {
    setShowDiffs(show)
    if (!show) setSelected(null)
  }
  const openEnv = () => openTab(`/folders/${id}/env?path=${encodeURIComponent(view.path)}&scope=FILE`)
  const popOut = () => {
    setShowDiffs(true)
    resizePanel('collapsed')
    openTab(`/folders/${id}/diffs?path=${encodeURIComponent(view.path)}`)
  }

  const [statusText, statusClass] = STATUS_TEXT[view.status] ?? [view.status, 'tb-gray']

  const nav = diffs.length > 0 && (
    <div className="diff-nav" role="group" aria-label="Navigate differences">
      <button className="icon-btn" onClick={() => step(-1)} title="Previous difference (p)" aria-label="Previous difference"><ChevronUpIcon /></button>
      <span className="small muted nowrap">{index >= 0 ? index + 1 : '–'} / {diffs.length}</span>
      <button className="icon-btn" onClick={() => step(1)} title="Next difference (n)" aria-label="Next difference"><ChevronDownIcon /></button>
    </div>
  )

  return (
    <div className={`fc-page ${codeOnly ? 'code-only' : ''}`}>
      {!codeOnly && (
        <div className="fc-bar">
          <Button onClick={onBack}>← Back to chart</Button>
          <div className="fc-path" title={view.path}>
            <span className="faint">{leftTitle} ↔ {rightTitle} /</span> <b className="mono">{view.path}</b>
          </div>
          <span className={`tbadge ${statusClass}`}>{statusText}</span>
        </div>
      )}

      {!codeOnly && (
        <div className="fill-toolbar">
          <span className="small muted fc-reason">{view.reason}</span>
          <div className="spacer" />
          <Toggle checked={sync} onChange={setSync} label="Scroll together" />
          <Button onClick={openEnv} title="Opens in a new tab">⊞ Env variables &amp; secrets</Button>
          <Button onClick={() => { setCodeOnly(true); window.scrollTo({ top: 0 }) }} title="Show only the two files">⤢ Code only</Button>
          {nav}
          <Button variant={showDiffs ? 'primary' : 'secondary'} onClick={() => toggleDiffs(!showDiffs)}>
            {showDiffs ? 'Hide logical differences' : `Show logical differences${diffs.length ? ` (${diffs.length})` : ''}`}
          </Button>
        </div>
      )}

      {codeOnly && (
        <div className="code-only-bar">
          <Button size="sm" onClick={() => setCodeOnly(false)} title="Back to the full view (Esc)">← Exit code only</Button>
          <span className={`tbadge ${statusClass}`}>{statusText}</span>
          <div className="spacer" />
          <Toggle checked={showDiffs} onChange={toggleDiffs} label="Highlight differences" />
          <Toggle checked={sync} onChange={setSync} label="Scroll together" />
          {nav}
        </div>
      )}

      {!codeOnly && showDiffs && (
        <DiffPanel view={view} selected={selected} onSelect={select} state={panel}
          onResize={resizePanel} onExpand={() => resizePanel(openSize.current)} onPopOut={popOut} />
      )}

      <div className="split-view screen">
        <PaneHeader side="Left" file={sidePath(view.leftName, view.leftLabel, view.path)} state={view.leftState} />
        <div className="split-divider" />
        <PaneHeader side="Right" file={sidePath(view.rightName, view.rightLabel, view.path)} state={view.rightState} />

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
  const title = side === 'left' ? sideTitle(view.leftName, view.leftLabel) : sideTitle(view.rightName, view.rightLabel)
  if (state === 'MISSING') return <><b>File does not exist</b><span>{view.path} is not in {title}</span></>
  if (view.binary) return <><b>Binary file</b><span>Content cannot be shown</span></>
  if (state === 'EMPTY') return <><b>File is empty</b></>
  return undefined
}

function PaneHeader({ side, file, state }: { side: string; file: string; state: SideState }) {
  return (
    <div className="pane-head">
      <span className="pane-side">{side}</span>
      <span className="mono pane-root" title={file}>{file}</span>
      {state === 'MISSING' && <span className="tbadge tb-gray">Does not exist</span>}
      {state === 'EMPTY' && <span className="tbadge tb-gray">File empty</span>}
    </div>
  )
}

function DiffPanel({ view, selected, onSelect, state, onResize, onExpand, onPopOut }: {
  view: FileView
  selected: string | null
  onSelect: (d: LogicalDiff) => void
  state: PanelState
  onResize: (s: PanelState) => void
  onExpand: () => void
  onPopOut: () => void
}) {
  const diffs = view.differences
  const leftTitle = sideTitle(view.leftName, view.leftLabel)
  const rightTitle = sideTitle(view.rightName, view.rightLabel)
  return (
    <>
      {state === 'max' && <div className="overlay" style={{ zIndex: 1000 }} onClick={() => onResize('normal')} />}
      <div className={`diff-panel ${state}`}>
        <div className="diff-panel-head">
          <b>Logical differences</b>
          <span className="chip">{diffs.length}</span>
          {state !== 'collapsed' && <span className="small muted">Click a row to highlight its lines in both files</span>}
          <div className="spacer" />
          {state !== 'collapsed' && (
            <span className="legend">
              <span><span className="swatch sw-added" />Added (right)</span>
              <span><span className="swatch sw-removed" />Removed (left)</span>
              <span><span className="swatch sw-changed" />Changed</span>
            </span>
          )}
          <div className="panel-btns">
            {state === 'collapsed'
              ? <button className="icon-btn" onClick={onExpand} title="Expand" aria-label="Expand"><ChevronDownIcon /></button>
              : <button className="icon-btn" onClick={() => onResize('collapsed')} title="Collapse" aria-label="Collapse"><ChevronUpIcon /></button>}
            {state === 'max'
              ? <button className="icon-btn" onClick={() => onResize('normal')} title="Restore size (Esc)" aria-label="Restore size"><RestoreIcon /></button>
              : <button className="icon-btn" onClick={() => onResize('max')} title="Maximise" aria-label="Maximise"><MaximizeIcon /></button>}
            <button className="icon-btn" onClick={onPopOut} title="Open in a separate tab" aria-label="Open in a separate tab"><PopOutIcon /></button>
          </div>
        </div>
        <div className="diff-body" hidden={state === 'collapsed'}>
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
                <span>{leftTitle}</span>
                <span>{rightTitle}</span>
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
      </div>
    </>
  )
}

function range(a: number, b: number) {
  return a === b || b <= 0 ? `${a}` : `${a}–${b}`
}
