import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { api } from '../api/client'
import type { FileView, FolderStatus, SideDiff, SideInfo, SideState } from '../api/types'
import { alignBlocks, CodePane } from '../components/CodePane'
import type { LineMark } from '../components/CodePane'
import { ChevronDownIcon, ChevronUpIcon, MaximizeIcon, PopOutIcon, RestoreIcon } from '../components/Icons'
import { SideCards } from '../components/Sides'
import { Button, Spinner, Toggle } from '../components/ui'
import { modeFor } from '../lib/highlight'
import { navigate, openTab } from '../lib/router'
import { diffChannel, sideLetter, sidePath, sidesKey, sideTitle, useVisibleSides } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

export const STATUS_TEXT: Record<FolderStatus, [string, string]> = {
  IDENTICAL: ['Identical', 'tb-green'],
  LOGICALLY_IDENTICAL: ['Logically same', 'tb-teal'],
  DIFFERS: ['Differs', 'tb-orange'],
  PARTIAL: ['Not in every folder', 'tb-solid-orange'],
}

type PanelState = 'normal' | 'max' | 'collapsed'

/** How a difference is highlighted in one folder's file: changed everywhere, or only here (first folder red, others green). */
export function markKind(d: SideDiff, side: number): LineMark['kind'] | null {
  if (d.values[side] == null) return null
  if (d.kind === 'CHANGED') return 'changed'
  return side === d.sides[0] ? 'removed' : 'added'
}

/** "Changed", "Only in PROD", "Missing in UAT". */
export function diffLabel(d: SideDiff, titles: string[]): [string, string] {
  if (d.kind === 'CHANGED') return ['Changed', 'tb-amber']
  const present = d.sides.filter((s) => d.values[s] != null)
  const absent = d.sides.filter((s) => d.values[s] == null)
  if (present.length === 1) return [`Only in ${titles[present[0]]}`, present[0] === d.sides[0] ? 'tb-red' : 'tb-green']
  return [`Missing in ${absent.map((s) => titles[s]).join(', ')}`, 'tb-red']
}

export function range(a: number, b: number) {
  return a === b || b <= 0 ? `${a}` : `${a}–${b}`
}

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
  const { visible, toggle } = useVisibleSides(id, view.sides.length)
  const key = sidesKey(visible)
  const comparison = view.comparisons[key]
  const diffs = useMemo(() => comparison?.differences ?? [], [comparison])
  const titles = useMemo(() => view.sides.map(sideTitle), [view.sides])
  const [showDiffs, setShowDiffs] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [sync, setSync] = useState(true)
  const [codeOnly, setCodeOnly] = useState(false)
  const [panel, setPanel] = useState<PanelState>('normal')
  /** Size to return to when the collapsed panel is expanded again. */
  const openSize = useRef<'normal' | 'max'>('normal')
  const ref0 = useRef<HTMLDivElement>(null)
  const ref1 = useRef<HTMLDivElement>(null)
  const ref2 = useRef<HTMLDivElement>(null)
  const refs = useMemo(() => [ref0, ref1, ref2], [])
  /** The pane the user is interacting with; only it drives the other panes, so they never fight. */
  const activePane = useRef<number | null>(null)
  const programmatic = useRef(false)
  const lastTop = useRef<number[]>([0, 0, 0])
  const channel = useRef<BroadcastChannel | null>(null)
  const mode = modeFor(view.name)

  // difference ids belong to one set of folders
  useEffect(() => setSelected(null), [key])

  const marks = useMemo(() => {
    const maps = view.sides.map(() => new Map<number, LineMark>())
    if (!showDiffs) return maps
    const put = (map: Map<number, LineMark>, start: number, end: number, kind: LineMark['kind'], d: SideDiff) => {
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
      for (const s of d.sides) {
        const kind = markKind(d, s)
        if (kind) put(maps[s], d.starts[s], d.ends[s], kind, d)
      }
    }
    // where the selected block is missing, mark the line it would follow
    const current = diffs.find((d) => d.id === selected)
    if (current) {
      for (const s of current.sides) {
        if (current.starts[s] === 0 && current.anchors[s] > 0 && !maps[s].has(current.anchors[s])) {
          maps[s].set(current.anchors[s], { kind: 'anchor', ids: [current.id], selected: true })
        }
      }
    }
    return maps
  }, [diffs, showDiffs, selected, view.sides])

  const select = useCallback((d: SideDiff) => {
    setShowDiffs(true)
    setSelected(d.id)
    programmatic.current = true
    requestAnimationFrame(() => {
      const fallback = d.sides.map((s) => d.starts[s]).find((n) => n > 0) ?? 0
      alignBlocks(visible.map((s) => ({
        pane: refs[s].current,
        start: d.starts[s] || d.anchors[s] || fallback,
        end: d.starts[s] ? d.ends[s] : 0,
      })))
      // the scroll events of this alignment may arrive late: they must not be replayed on the other panes
      refs.forEach((r, s) => { lastTop.current[s] = r.current?.scrollTop ?? 0 })
      setTimeout(() => { programmatic.current = false }, 150)
    })
    document.querySelector(`[data-diff="${d.id}"]`)?.scrollIntoView({ block: 'nearest' })
    channel.current?.postMessage({ type: 'selected', diffId: d.id, key })
  }, [visible, refs, key])

  // a differences tab opened from here selects differences in this tab
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return
    const ch = new BroadcastChannel(diffChannel(id, view.path))
    ch.onmessage = (e) => {
      if (e.data?.type !== 'select' || e.data.key !== key) return
      const d = diffs.find((x) => x.id === e.data.diffId)
      if (d) select(d)
    }
    channel.current = ch
    return () => {
      ch.close()
      channel.current = null
    }
  }, [id, view.path, diffs, select, key])

  useEffect(() => {
    if (!initialDiff) return
    const d = diffs.find((x) => x.id === initialDiff)
    if (d) select(d)
    else setShowDiffs(true)
    // only when the page opens
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialDiff])

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

  /** Scrolling together moves every pane by the same distance, so blocks aligned by Next / Previous stay level. */
  const onScroll = (source: number) => {
    const from = refs[source].current
    if (!from) return
    const delta = from.scrollTop - lastTop.current[source]
    lastTop.current[source] = from.scrollTop
    if (!sync || programmatic.current || delta === 0) return
    if (activePane.current !== null && activePane.current !== source) return
    for (const s of visible) {
      const to = refs[s].current
      if (s === source || !to) continue
      to.scrollTop += delta
      lastTop.current[s] = to.scrollTop
    }
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

  const [statusText, statusClass] = comparison ? STATUS_TEXT[comparison.status] : ['—', 'tb-gray']

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
            <span className="faint">{visible.map((s) => titles[s]).join(' ↔ ')} /</span> <b className="mono">{view.path}</b>
          </div>
          <span className={`tbadge ${statusClass}`} title={comparison?.reason}>{statusText}</span>
        </div>
      )}

      {!codeOnly && view.sides.length > 2 && (
        <SideCards sides={view.sides} visible={visible} onToggle={toggle} compact
          detail={(s) => stateText(view.sides[s].state)} />
      )}

      {!codeOnly && (
        <div className="fill-toolbar">
          <span className="small muted fc-reason">{comparison?.reason}</span>
          <div className="spacer" />
          <Toggle checked={sync} onChange={setSync} label="Scroll together" />
          <Button onClick={openEnv} title="Opens in a new tab">⊞ Env variables &amp; secrets</Button>
          <Button onClick={() => { setCodeOnly(true); window.scrollTo({ top: 0 }) }} title="Show only the files">⤢ Code only</Button>
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

      {!codeOnly && showDiffs && comparison && (
        <DiffPanel diffs={diffs} status={comparison.status} reason={comparison.reason} visible={visible} titles={titles}
          selected={selected} onSelect={select} state={panel}
          onResize={resizePanel} onExpand={() => resizePanel(openSize.current)} onPopOut={popOut} />
      )}

      <div className="split-view screen" style={{ '--panes': visible.length } as CSSProperties}>
        {visible.map((s) => <PaneHeader key={s} index={s} side={view.sides[s]} path={view.path} state={view.sides[s].state} />)}
        {visible.map((s) => (
          <CodePane key={s} lines={view.sides[s].lines} mode={mode} marks={marks[s]} paneRef={refs[s]}
            placeholder={placeholder(view, s)} onScroll={() => onScroll(s)}
            onActivate={() => { activePane.current = s }} onLineClick={onLineClick} />
        ))}
      </div>
    </div>
  )
}

function stateText(state: SideState) {
  return state === 'MISSING' ? 'file does not exist' : state === 'EMPTY' ? 'file is empty' : 'file present'
}

function placeholder(view: FileView, side: number): ReactNode | undefined {
  const s = view.sides[side]
  if (s.state === 'MISSING') return <><b>File does not exist</b><span>{view.path} is not in {sideTitle(s)}</span></>
  if (view.binary) return <><b>Binary file</b><span>Content cannot be shown</span></>
  if (s.state === 'EMPTY') return <><b>File is empty</b></>
  return undefined
}

function PaneHeader({ index, side, path, state }: { index: number; side: SideInfo; path: string; state: SideState }) {
  const file = sidePath(side, path)
  return (
    <div className={`pane-head side-tone-${index}`}>
      <span className="side-letter">{sideLetter(index)}</span>
      <span className="mono pane-root" title={file}>{file}</span>
      {state === 'MISSING' && <span className="tbadge tb-gray">Does not exist</span>}
      {state === 'EMPTY' && <span className="tbadge tb-gray">File empty</span>}
    </div>
  )
}

function DiffPanel({ diffs, status, reason, visible, titles, selected, onSelect, state, onResize, onExpand, onPopOut }: {
  diffs: SideDiff[]
  status: FolderStatus
  reason: string
  visible: number[]
  titles: string[]
  selected: string | null
  onSelect: (d: SideDiff) => void
  state: PanelState
  onResize: (s: PanelState) => void
  onExpand: () => void
  onPopOut: () => void
}) {
  const columns = { gridTemplateColumns: `120px minmax(0, 1.1fr) repeat(${visible.length}, minmax(0, 1fr)) ${visible.length > 2 ? 150 : 120}px` }
  return (
    <>
      {state === 'max' && <div className="overlay" style={{ zIndex: 1000 }} onClick={() => onResize('normal')} />}
      <div className={`diff-panel ${state}`}>
        <div className="diff-panel-head">
          <b>Logical differences</b>
          <span className="chip">{diffs.length}</span>
          {state !== 'collapsed' && <span className="small muted">Click a row to highlight its lines in every file</span>}
          <div className="spacer" />
          {state !== 'collapsed' && (
            <span className="legend">
              <span><span className="swatch sw-changed" />Changed</span>
              <span><span className="swatch sw-removed" />Only in {titles[visible[0]]}</span>
              <span><span className="swatch sw-added" />Only in {visible.length > 2 ? 'later folders' : titles[visible[1]]}</span>
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
              {status === 'PARTIAL' ? `${reason}.` : status === 'DIFFERS' ? reason
                : `No logical differences — the files produce the same Kubernetes configuration in ${visible.map((s) => titles[s]).join(', ')}.`}
            </div>
          ) : (
            <div className="diff-list">
              <div className="diff-row diff-header" style={columns}>
                <span>Change</span>
                <span>Configuration</span>
                {visible.map((s) => <span key={s} className={`side-tone-${s}`}><span className="side-letter sm">{sideLetter(s)}</span> {titles[s]}</span>)}
                <span>Lines</span>
              </div>
              {diffs.map((d) => {
                const [label, cls] = diffLabel(d, titles)
                return (
                  <button key={d.id} data-diff={d.id} className={`diff-row diff-item ${d.id === selected ? 'selected' : ''}`} style={columns}
                    onClick={() => onSelect(d)}>
                    <span><span className={`tbadge ${cls}`} title={label}>{label}</span></span>
                    <span className="diff-cell mono" title={d.path}>{d.path}</span>
                    {visible.map((s) => (
                      <span key={s} className="diff-cell">
                        {!d.sides.includes(s) ? <span className="faint" title="Not part of this difference">(not compared)</span>
                          : d.values[s] != null ? <span className={s === d.sides[0] ? 'old' : 'new'}>{d.values[s]}</span>
                            : <span className="faint">— absent —</span>}
                      </span>
                    ))}
                    <span className="small faint diff-lines">
                      {visible.filter((s) => d.sides.includes(s) && d.starts[s] > 0)
                        .map((s) => `${sideLetter(s)}${range(d.starts[s], d.ends[s])}`).join(' · ')}
                    </span>
                  </button>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </>
  )
}
