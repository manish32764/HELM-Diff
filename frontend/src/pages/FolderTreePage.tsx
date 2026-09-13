import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { RefObject, WheelEvent } from 'react'
import { api } from '../api/client'
import type { FolderCompare, FolderNode } from '../api/types'
import { ExportMenu } from '../components/ExportMenu'
import { FileIcon, FolderIcon } from '../components/FolderIcons'
import { Button, SearchInput, Segmented, Spinner, useToast } from '../components/ui'
import { formatDate } from '../lib/labels'
import { navigate, openTab } from '../lib/router'
import { closeTab, sidePath, sideTitle } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

type Filter = 'ALL' | 'DIFF' | 'SAME'
type Side = 'left' | 'right'

interface Row {
  node: FolderNode
  depth: number
  number?: number
}

const ROW_HEIGHT = 34
const storageKey = (id: string, what: string) => `folder-compare:${id}:${what}`

function load<T>(key: string, fallback: T): T {
  try {
    const v = sessionStorage.getItem(key)
    return v ? (JSON.parse(v) as T) : fallback
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // storage unavailable
  }
}

/**
 * Without `root`: the uploaded parent folders side by side, first-level (microservice) folders only.
 * With `root`: the complete Helm chart of that microservice folder (opened in its own tab).
 */
export function FolderTreePage({ id, root }: { id: string; root?: string }) {
  const data = useAsync(() => api.folderCompare(id), [id])
  if (data.loading) return <Spinner />
  if (data.error) return <div className="banner banner-error">{data.error}</div>
  const compare = data.data!
  if (root === undefined) return <FolderTree compare={compare} />
  const scope = findNode(compare.root, root)
  if (!scope || !scope.dir) return <div className="banner banner-error">Folder not found: {root}</div>
  return <FolderTree compare={compare} scope={scope} />
}

function FolderTree({ compare, scope }: { compare: FolderCompare; scope?: FolderNode }) {
  const { id } = compare
  const chart = scope !== undefined
  const suffix = scope ? `:${scope.path}` : ''
  const expandedKey = storageKey(id, `expanded${suffix}`)
  const filterKey = storageKey(id, `filter${suffix}`)
  const lastKey = storageKey(id, `last${suffix}`)
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(load<string[] | null>(expandedKey, null) ?? (scope ? allDirs(scope) : [])))
  const [filter, setFilter] = useState<Filter>(() => load<Filter>(filterKey, 'ALL'))
  const [query, setQuery] = useState('')
  const [hover, setHover] = useState<number | null>(null)
  const [lastOpened, setLastOpened] = useState(() => load<string>(lastKey, ''))
  const toast = useToast()
  const leftTitle = sideTitle(compare.leftName, compare.leftLabel)
  const rightTitle = sideTitle(compare.rightName, compare.rightLabel)

  useEffect(() => save(expandedKey, [...expanded]), [expandedKey, expanded])
  useEffect(() => save(filterKey, filter), [filterKey, filter])
  useEffect(() => {
    document.title = scope ? `${scope.name} · Helm chart` : 'Helm Compare'
  }, [scope])

  const q = query.trim().toLowerCase()
  const rows = useMemo(() => (scope ? flatten(scope, expanded, filter, q) : topLevel(compare.root, filter, q)),
    [scope, compare.root, expanded, filter, q])

  const gutterRef = useRef<HTMLDivElement>(null)
  const leftRef = useRef<HTMLDivElement>(null)
  const rightRef = useRef<HTMLDivElement>(null)
  /** Only the scroller the user is interacting with drives the others, so they never fight (smooth trackpad scrolling). */
  const active = useRef<HTMLDivElement | null>(null)

  const mirror = (source: HTMLDivElement) => {
    for (const el of [gutterRef.current, leftRef.current, rightRef.current]) {
      if (el && el !== source && el.scrollTop !== source.scrollTop) el.scrollTop = source.scrollTop
    }
  }
  const onPaneScroll = (source: HTMLDivElement) => {
    if (active.current && active.current !== source) return
    mirror(source)
  }

  useEffect(() => {
    if (!lastOpened) return
    const index = rows.findIndex((r) => r.node.path === lastOpened)
    if (index >= 0 && leftRef.current) {
      leftRef.current.scrollTop = Math.max(0, index * ROW_HEIGHT - leftRef.current.clientHeight / 3)
      mirror(leftRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const toggle = useCallback((path: string) => {
    setExpanded((s) => {
      const next = new Set(s)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }, [])

  const select = useCallback((path: string) => {
    save(lastKey, path)
    setLastOpened(path)
  }, [lastKey])

  const openFile = useCallback((path: string) => {
    save(lastKey, path)
    navigate(`/folders/${id}/file?path=${encodeURIComponent(path)}`)
  }, [id, lastKey])

  const openChart = useCallback((path: string) => {
    select(path)
    openTab(`/folders/${id}/chart?root=${encodeURIComponent(path)}`)
  }, [id, select])

  const openEnv = useCallback((path: string) => {
    openTab(`/folders/${id}/env?path=${encodeURIComponent(path)}&scope=FOLDER`)
  }, [id])

  const remove = async () => {
    if (!window.confirm('Delete this comparison?')) return
    await api.deleteFolderCompare(id)
    toast('Comparison deleted')
    navigate('/')
  }

  const s = compare.summary

  return (
    <div className="page-fill">
      <div className="fill-header">
        {scope ? (
          <div style={{ minWidth: 0 }}>
            <div className="eyebrow">Helm chart · {scope.name}</div>
            <h1 className="page-title fill-title">
              <span className="mono">{sidePath(compare.leftName, compare.leftLabel, scope.path)}</span> <span className="faint">↔</span>{' '}
              <span className="mono">{sidePath(compare.rightName, compare.rightLabel, scope.path)}</span>
            </h1>
          </div>
        ) : (
          <div style={{ minWidth: 0 }}>
            <div className="eyebrow">Folder comparison · {formatDate(compare.createdAt)}</div>
            <h1 className="page-title fill-title">
              <span className="mono">{leftTitle}</span> <span className="faint">↔</span> <span className="mono">{rightTitle}</span>
            </h1>
          </div>
        )}
        <div className="actions">
          {scope ? (
            <>
              <Button onClick={() => openEnv(scope.path)} title="Opens in a new tab">⊞ Env variables &amp; secrets</Button>
              <Button onClick={() => closeTab(`/folders/${id}`)}>Close tab</Button>
            </>
          ) : (
            <>
              <Button onClick={() => navigate('/')}>New comparison</Button>
              <ExportMenu url={(f) => api.folderExportUrl(id, f)} formats={['xlsx', 'html', 'csv']} />
              <Button variant="danger" onClick={remove}>Delete</Button>
            </>
          )}
        </div>
      </div>

      <div className="summary-strip">
        {scope ? (
          <span className="strip-item muted">
            {fileTotal(scope)} file{fileTotal(scope) === 1 ? '' : 's'}: {scope.identical} identical · {scope.logicallySame} logically same · {scope.differs} differ · {scope.leftOnly + scope.rightOnly} one side only
          </span>
        ) : (
          <>
            <span className="strip-item"><span className="dot dot-gray" /><b>{s.leftFolders}</b> {leftTitle} folders</span>
            <span className="strip-item"><span className="dot dot-gray" /><b>{s.rightFolders}</b> {rightTitle} folders</span>
            <span className="strip-item"><span className="dot dot-green" /><b>{s.identicalFolders}</b> identical</span>
            <span className="strip-item"><span className="dot dot-red" /><b>{s.differentFolders}</b> with differences</span>
            <span className="strip-item"><span className="dot dot-orange" /><b>{s.leftOnlyFolders}</b> only in {leftTitle}</span>
            <span className="strip-item"><span className="dot dot-orange" /><b>{s.rightOnlyFolders}</b> only in {rightTitle}</span>
            <span className="strip-sep" />
            <span className="strip-item muted">
              {s.files} files: {s.identicalFiles} identical · {s.logicallySameFiles} logically same · {s.differentFiles} differ · {s.leftOnlyFiles + s.rightOnlyFiles} one side only
            </span>
          </>
        )}
      </div>

      <div className="fill-toolbar">
        <Segmented value={filter} onChange={setFilter} options={[
          { value: 'ALL', label: 'All' },
          { value: 'DIFF', label: 'Differences only' },
          { value: 'SAME', label: 'Identical only' },
        ]} />
        {scope ? (
          <>
            <Button size="sm" onClick={() => setExpanded(new Set(allDirs(scope)))}>Expand all</Button>
            <Button size="sm" onClick={() => setExpanded(new Set())}>Collapse all</Button>
          </>
        ) : (
          <span className="small muted">Double-click a folder to open its Helm chart in a new tab</span>
        )}
        <div className="spacer" />
        <SearchInput value={query} onChange={setQuery} placeholder={scope ? 'Find a file' : 'Find a microservice folder'} />
      </div>

      <div className="tree-grid">
        <div className="tree-head">
          <div className="th th-num">#</div>
          <div className="th th-dot" />
          <div className="th th-side" title={compare.leftName}>
            {leftTitle}{compare.leftLabel && <span className="th-label">({compare.leftName})</span>}
          </div>
          <div className="split-divider" />
          <div className="th th-side" title={compare.rightName}>
            {rightTitle}{compare.rightLabel && <span className="th-label">({compare.rightName})</span>}
          </div>
        </div>

        <div className="tree-body" onMouseLeave={() => setHover(null)}>
          <div className="tree-gutter" ref={gutterRef}
            onWheel={(e: WheelEvent<HTMLDivElement>) => {
              const left = leftRef.current
              if (!left) return
              active.current = left
              left.scrollTop += e.deltaY
              mirror(left)
            }}>
            <div className="tree-rows">
              {rows.map((r, i) => (
                <div key={r.node.path} className={`tree-row gutter ${hover === i ? 'hover' : ''} ${r.node.path === lastOpened ? 'last' : ''}`}
                  onMouseEnter={() => setHover(i)}>
                  <span className="g-num">{r.node.dir ? '—' : r.number}</span>
                  <span className={`dot dot-${dotTone(r.node)}`} title={r.node.reason} />
                </div>
              ))}
            </div>
          </div>
          {(['left', 'right'] as Side[]).map((side, k) => {
            const ref = k === 0 ? leftRef : rightRef
            return (
              <TreePane key={side} side={side} rows={rows} chart={chart} expanded={expanded} hover={hover} lastOpened={lastOpened}
                paneRef={ref} divider={k === 1}
                onActivate={() => { active.current = ref.current }}
                onScroll={(el) => onPaneScroll(el)} onHover={setHover} onToggle={toggle} onSelect={select}
                onOpen={openFile} onOpenChart={openChart} onEnv={openEnv} />
            )
          })}
          {rows.length === 0 && <div className="tree-empty">Nothing matches the current filter.</div>}
        </div>
      </div>

      <div className="legend tree-legend">
        <span><span className="dot dot-green" />Identical</span>
        <span><span className="dot dot-teal" />Logically same (formatting / order only)</span>
        <span><span className="dot dot-red" />Content differs</span>
        <span><span className="dot dot-orange" />One side only</span>
        <span><FolderIcon tone="green" /> all files identical</span>
        <span><FolderIcon tone="yellow" /> something differs</span>
      </div>
    </div>
  )
}

function TreePane({ side, rows, chart, expanded, hover, lastOpened, paneRef, divider, onActivate, onScroll, onHover, onToggle, onSelect,
  onOpen, onOpenChart, onEnv }: {
  side: Side
  rows: Row[]
  chart: boolean
  expanded: Set<string>
  hover: number | null
  lastOpened: string
  paneRef: RefObject<HTMLDivElement | null>
  divider: boolean
  onActivate: () => void
  onScroll: (el: HTMLDivElement) => void
  onHover: (i: number) => void
  onToggle: (path: string) => void
  onSelect: (path: string) => void
  onOpen: (path: string) => void
  onOpenChart: (path: string) => void
  onEnv: (path: string) => void
}) {
  return (
    <>
      {divider && <div className="split-divider" />}
      <div className="tree-pane" ref={paneRef} onScroll={(e) => onScroll(e.currentTarget)}
        onMouseEnter={onActivate} onWheel={onActivate} onTouchStart={onActivate}>
        <div className="tree-rows">
          {rows.map((r, i) => {
            const n = r.node
            const state = side === 'left' ? n.leftState : n.rightState
            const isOpen = expanded.has(n.path)
            const cls = `tree-row ${hover === i ? 'hover' : ''} ${n.path === lastOpened ? 'last' : ''}`
            const pad = 14 + r.depth * 22
            const click = () => (n.dir ? (chart ? onToggle(n.path) : onSelect(n.path)) : onOpen(n.path))
            const doubleClick = () => { if (n.dir && !chart) onOpenChart(n.path) }
            if (state === 'MISSING') {
              return (
                <div key={n.path} className={`${cls} missing`} style={{ paddingLeft: pad }} onMouseEnter={() => onHover(i)}
                  onClick={click} onDoubleClick={doubleClick}>
                  <span className="tree-missing">— does not exist —</span>
                </div>
              )
            }
            return (
              <div key={n.path} className={cls} style={{ paddingLeft: pad }} title={!chart && n.dir ? 'Double-click to open the Helm chart in a new tab' : n.reason}
                onMouseEnter={() => onHover(i)} onClick={click} onDoubleClick={doubleClick}>
                {n.dir && chart ? <span className="tree-toggle">{isOpen ? '−' : '+'}</span> : <span className="tree-toggle-space" />}
                {n.dir ? <FolderIcon tone={n.status === 'IDENTICAL' ? 'green' : 'yellow'} /> : <FileIcon />}
                <span className="tree-name">{n.name}</span>
                <TreeBadge node={n} side={side} />
                {n.dir && <span className="tree-counts">{dirCounts(n)}</span>}
                {n.dir && !chart && side === 'left' && (
                  <span className="tree-actions">
                    <button className="tree-env" title="Open the Helm chart of this folder in a new tab"
                      onClick={(e) => { e.stopPropagation(); onOpenChart(n.path) }}>OPEN ↗</button>
                    <button className="tree-env" title="Compare environment variables & secrets (new tab)"
                      onClick={(e) => { e.stopPropagation(); onEnv(n.path) }}>ENV ↗</button>
                  </span>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </>
  )
}

function TreeBadge({ node, side }: { node: FolderNode; side: Side }) {
  const state = side === 'left' ? node.leftState : node.rightState
  if (!node.dir && state === 'EMPTY') return <span className="tbadge tb-gray">File empty</span>
  switch (node.status) {
    case 'IDENTICAL': return <span className="tbadge tb-green">Identical</span>
    case 'LOGICALLY_IDENTICAL': return <span className="tbadge tb-teal" title={node.reason}>Logically same</span>
    case 'DIFFERS': return <span className="tbadge tb-orange">Differs{!node.dir && node.differences > 0 && side === 'right' ? ` · ${node.differences}` : ''}</span>
    case 'LEFT_ONLY': return <span className="tbadge tb-solid-orange">Left only</span>
    case 'RIGHT_ONLY': return <span className="tbadge tb-solid-blue">Right only</span>
  }
}

function fileTotal(n: FolderNode): number {
  return n.identical + n.logicallySame + n.differs + n.leftOnly + n.rightOnly
}

function dirCounts(n: FolderNode): string {
  const total = fileTotal(n)
  const parts = [`${total} file${total === 1 ? '' : 's'}`]
  if (n.differs) parts.push(`${n.differs} differ`)
  if (n.leftOnly + n.rightOnly) parts.push(`${n.leftOnly + n.rightOnly} one side`)
  return parts.join(' · ')
}

function dotTone(n: FolderNode): string {
  switch (n.status) {
    case 'IDENTICAL': return 'green'
    case 'LOGICALLY_IDENTICAL': return 'teal'
    case 'DIFFERS': return 'red'
    default: return 'orange'
  }
}

function matchesFilter(n: FolderNode, filter: Filter): boolean {
  if (filter === 'ALL') return true
  if (filter === 'DIFF') return n.status !== 'IDENTICAL'
  return n.dir ? n.identical > 0 : n.status === 'IDENTICAL'
}

function matchesQuery(n: FolderNode, q: string): boolean {
  if (!q) return true
  if (n.name.toLowerCase().includes(q)) return true
  return !!n.children?.some((c) => matchesQuery(c, q))
}

/** First-level entries of the uploaded parent folders. */
function topLevel(root: FolderNode, filter: Filter, q: string): Row[] {
  let number = 0
  return (root.children ?? [])
    .filter((n) => matchesFilter(n, filter) && matchesQuery(n, q))
    .map((n) => ({ node: n, depth: 0, number: n.dir ? undefined : ++number }))
}

function flatten(root: FolderNode, expanded: Set<string>, filter: Filter, q: string): Row[] {
  const rows: Row[] = []
  let number = 0
  const walk = (nodes: FolderNode[] | undefined, depth: number) => {
    for (const n of nodes ?? []) {
      if (!matchesFilter(n, filter) || !matchesQuery(n, q)) continue
      rows.push({ node: n, depth, number: n.dir ? undefined : ++number })
      if (n.dir && (expanded.has(n.path) || q)) walk(n.children, depth + 1)
    }
  }
  walk(root.children, 0)
  return rows
}

function findNode(root: FolderNode, path: string): FolderNode | undefined {
  if (root.path === path) return root
  for (const child of root.children ?? []) {
    if (path === child.path || path.startsWith(`${child.path}/`)) return findNode(child, path)
  }
  return undefined
}

function allDirs(n: FolderNode): string[] {
  if (!n.dir) return []
  return [...(n.path ? [n.path] : []), ...(n.children ?? []).flatMap(allDirs)]
}
