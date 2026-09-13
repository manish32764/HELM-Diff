import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { RefObject, WheelEvent } from 'react'
import { api } from '../api/client'
import type { FolderCompare, FolderNode } from '../api/types'
import { ExportMenu } from '../components/ExportMenu'
import { FileIcon, FolderIcon } from '../components/FolderIcons'
import { Button, SearchInput, Segmented, Spinner, useToast } from '../components/ui'
import { formatDate } from '../lib/labels'
import { navigate } from '../lib/router'
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

export function FolderTreePage({ id }: { id: string }) {
  const data = useAsync(() => api.folderCompare(id), [id])
  if (data.loading) return <Spinner />
  if (data.error) return <div className="banner banner-error">{data.error}</div>
  return <FolderTree compare={data.data!} />
}

function FolderTree({ compare }: { compare: FolderCompare }) {
  const { id } = compare
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(load<string[]>(storageKey(id, 'expanded'), [])))
  const [filter, setFilter] = useState<Filter>(() => load<Filter>(storageKey(id, 'filter'), 'ALL'))
  const [query, setQuery] = useState('')
  const [hover, setHover] = useState<number | null>(null)
  const [lastOpened] = useState(() => load<string>(storageKey(id, 'last'), ''))
  const toast = useToast()

  useEffect(() => save(storageKey(id, 'expanded'), [...expanded]), [id, expanded])
  useEffect(() => save(storageKey(id, 'filter'), filter), [id, filter])

  const rows = useMemo(() => flatten(compare.root, expanded, filter, query.trim().toLowerCase()), [compare.root, expanded, filter, query])

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

  const open = useCallback((path: string) => {
    save(storageKey(id, 'last'), path)
    navigate(`/folders/${id}/file?path=${encodeURIComponent(path)}`)
  }, [id])

  const openEnv = useCallback((path: string) => {
    navigate(`/folders/${id}/env?path=${encodeURIComponent(path)}&scope=FOLDER`)
  }, [id])

  const expandAll = () => setExpanded(new Set(allDirs(compare.root)))
  const collapseAll = () => setExpanded(new Set())

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
        <div style={{ minWidth: 0 }}>
          <div className="eyebrow">Folder comparison · {formatDate(compare.createdAt)}</div>
          <h1 className="page-title fill-title">
            <span className="mono">{compare.leftName}</span> <span className="faint">↔</span> <span className="mono">{compare.rightName}</span>
          </h1>
        </div>
        <div className="actions">
          <Button onClick={() => navigate('/')}>New comparison</Button>
          <ExportMenu url={(f) => api.folderExportUrl(id, f)} formats={['xlsx', 'html', 'csv']} />
          <Button variant="danger" onClick={remove}>Delete</Button>
        </div>
      </div>

      <div className="summary-strip">
        <span className="strip-item"><span className="dot dot-gray" /><b>{s.leftFolders}</b> {compare.leftName} folders</span>
        <span className="strip-item"><span className="dot dot-gray" /><b>{s.rightFolders}</b> {compare.rightName} folders</span>
        <span className="strip-item"><span className="dot dot-green" /><b>{s.matchedFolders}</b> matches</span>
        <span className="strip-item"><span className="dot dot-green" /><b>{s.identicalFolders}</b> identical</span>
        <span className="strip-item"><span className="dot dot-red" /><b>{s.differentFolders}</b> with differences</span>
        <span className="strip-item"><span className="dot dot-orange" /><b>{s.leftOnlyFolders}</b> left only</span>
        <span className="strip-item"><span className="dot dot-orange" /><b>{s.rightOnlyFolders}</b> right only</span>
        <span className="strip-sep" />
        <span className="strip-item muted">
          {s.files} files: {s.identicalFiles} identical · {s.logicallySameFiles} logically same · {s.differentFiles} differ · {s.leftOnlyFiles + s.rightOnlyFiles} one side only
        </span>
      </div>

      <div className="fill-toolbar">
        <Segmented value={filter} onChange={setFilter} options={[
          { value: 'ALL', label: 'All' },
          { value: 'DIFF', label: 'Differences only' },
          { value: 'SAME', label: 'Identical only' },
        ]} />
        <Button size="sm" onClick={expandAll}>Expand all</Button>
        <Button size="sm" onClick={collapseAll}>Collapse all</Button>
        <div className="spacer" />
        <SearchInput value={query} onChange={setQuery} placeholder="Find a folder or file" />
      </div>

      <div className="tree-grid">
        <div className="tree-head">
          <div className="th th-num">#</div>
          <div className="th th-dot" />
          <div className="th th-side" title={compare.leftName}>
            {compare.leftName}{compare.leftLabel && <span className="th-label">({compare.leftLabel})</span>}
          </div>
          <div className="split-divider" />
          <div className="th th-side" title={compare.rightName}>
            {compare.rightName}{compare.rightLabel && <span className="th-label">({compare.rightLabel})</span>}
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
              <TreePane key={side} side={side} rows={rows} expanded={expanded} hover={hover} lastOpened={lastOpened}
                paneRef={ref} divider={k === 1}
                onActivate={() => { active.current = ref.current }}
                onScroll={(el) => onPaneScroll(el)} onHover={setHover} onToggle={toggle} onOpen={open} onEnv={openEnv} />
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
        <span className="tbadge tb-blue">ENV</span><span>compare env variables &amp; secrets of a microservice</span>
      </div>
    </div>
  )
}

function TreePane({ side, rows, expanded, hover, lastOpened, paneRef, divider, onActivate, onScroll, onHover, onToggle, onOpen, onEnv }: {
  side: Side
  rows: Row[]
  expanded: Set<string>
  hover: number | null
  lastOpened: string
  paneRef: RefObject<HTMLDivElement | null>
  divider: boolean
  onActivate: () => void
  onScroll: (el: HTMLDivElement) => void
  onHover: (i: number) => void
  onToggle: (path: string) => void
  onOpen: (path: string) => void
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
            if (state === 'MISSING') {
              return (
                <div key={n.path} className={`${cls} missing`} style={{ paddingLeft: pad }} onMouseEnter={() => onHover(i)}
                  onClick={() => (n.dir ? onToggle(n.path) : onOpen(n.path))}>
                  <span className="tree-missing">— does not exist —</span>
                </div>
              )
            }
            return (
              <div key={n.path} className={cls} style={{ paddingLeft: pad }} title={n.reason} onMouseEnter={() => onHover(i)}
                onClick={() => (n.dir ? onToggle(n.path) : onOpen(n.path))}>
                {n.dir ? <span className="tree-toggle">{isOpen ? '−' : '+'}</span> : <span className="tree-toggle-space" />}
                {n.dir ? <FolderIcon tone={n.status === 'IDENTICAL' ? 'green' : 'yellow'} /> : <FileIcon />}
                <span className="tree-name">{n.name}</span>
                <TreeBadge node={n} side={side} />
                {n.dir && <span className="tree-counts">{dirCounts(n)}</span>}
                {n.dir && r.depth === 0 && side === 'left' && (
                  <button className="tree-env" title="Compare environment variables & secrets of this microservice"
                    onClick={(e) => { e.stopPropagation(); onEnv(n.path) }}>ENV</button>
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

function dirCounts(n: FolderNode): string {
  const total = n.identical + n.logicallySame + n.differs + n.leftOnly + n.rightOnly
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

function allDirs(n: FolderNode): string[] {
  if (!n.dir) return []
  return [...(n.path ? [n.path] : []), ...(n.children ?? []).flatMap(allDirs)]
}
