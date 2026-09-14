import { useCallback, useEffect, useMemo, useState } from 'react'
import type { CSSProperties } from 'react'
import { api } from '../api/client'
import type { FolderCompare, FolderNode, SideInfo } from '../api/types'
import { ExportMenu } from '../components/ExportMenu'
import { FileIcon, FolderIcon } from '../components/FolderIcons'
import { HScroll, SideCards, StatusPill } from '../components/Sides'
import { Button, SearchInput, Spinner, useToast } from '../components/ui'
import { createEvaluator, isVisible, total } from '../lib/compare'
import type { Eval, Evaluator } from '../lib/compare'
import { formatBytes } from '../lib/files'
import { formatDate } from '../lib/labels'
import { navigate, openTab } from '../lib/router'
import { closeTab, sideLetter, sidesParam, sideTitle, useVisibleSides } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

type Filter = 'ALL' | 'DIFF' | 'SAME'

interface Row {
  node: FolderNode
  depth: number
  eval: Eval
}

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
  const { id, sides } = compare
  const chart = scope !== undefined
  const base = scope ?? compare.root
  const suffix = scope ? `:${scope.path}` : ''
  const expandedKey = storageKey(id, `expanded${suffix}`)
  const filterKey = storageKey(id, `filter${suffix}`)
  const lastKey = storageKey(id, `last${suffix}`)
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(load<string[] | null>(expandedKey, null) ?? (scope ? allDirs(scope) : [])))
  const [filter, setFilter] = useState<Filter>(() => load<Filter>(filterKey, 'ALL'))
  const [query, setQuery] = useState('')
  const [hover, setHover] = useState<number | null>(null)
  const [lastOpened, setLastOpened] = useState(() => load<string>(lastKey, ''))
  const { visible, toggle: toggleSide } = useVisibleSides(id, sides.length)
  const toast = useToast()

  useEffect(() => save(expandedKey, [...expanded]), [expandedKey, expanded])
  useEffect(() => save(filterKey, filter), [filterKey, filter])
  useEffect(() => {
    document.title = scope ? `${scope.name} · Helm chart` : 'Helm Compare'
  }, [scope])

  const evaluator = useMemo(() => createEvaluator(sides, visible), [sides, visible])
  const fileCounts = useMemo(() => countFiles(compare.root, sides.length), [compare.root, sides.length])

  const q = query.trim().toLowerCase()
  const rows = useMemo(() => (scope ? flatten(scope, expanded, filter, q, evaluator, visible) : topLevel(compare.root, filter, q, evaluator, visible)),
    [scope, compare.root, expanded, filter, q, evaluator, visible])

  const stats = useMemo(() => {
    const files = evaluator.evaluate(base).counts
    if (chart) {
      return { all: total(files), same: files.identical + files.logicallySame, diff: files.differs + files.partial, files, entries: files }
    }
    const entries = { identical: 0, logicallySame: 0, differs: 0, partial: 0 }
    for (const n of compare.root.children ?? []) {
      if (!isVisible(n, visible)) continue
      const status = evaluator.evaluate(n).status
      if (status === 'IDENTICAL') entries.identical++
      else if (status === 'LOGICALLY_IDENTICAL') entries.logicallySame++
      else if (status === 'DIFFERS') entries.differs++
      else entries.partial++
    }
    return { all: total(entries), same: entries.identical + entries.logicallySame, diff: entries.differs + entries.partial, files, entries }
  }, [evaluator, base, chart, compare.root, visible])

  useEffect(() => {
    if (!lastOpened) return
    document.querySelector(`[data-row="${CSS.escape(lastOpened)}"]`)?.scrollIntoView({ block: 'center' })
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

  const unit = chart ? 'file' : 'folder'
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`
  const detail = (side: number) => chart
    ? plural(fileCounts.get(base)?.[side] ?? 0, 'file')
    : plural(compare.summary.sideFolders?.[side] ?? 0, 'folder')
  const hidden = sides.map((_, i) => i).filter((i) => !visible.includes(i))

  return (
    <div className="cmp-page">
      <section className="cmp-hero">
        <div className="cmp-hero-top">
          <div style={{ minWidth: 0 }}>
            <div className="eyebrow">{chart ? `Helm chart · ${plural(sides.length, 'folder')}` : `Folder comparison · ${formatDate(compare.createdAt)}`}</div>
            <h1 className="cmp-title">
              {chart ? <span className="mono">{scope!.name}</span> : visible.map((s, k) => (
                <span key={s}>{k > 0 && <span className="cmp-vs">↔</span>}<span className="mono">{sideTitle(sides[s])}</span></span>
              ))}
            </h1>
          </div>
          <div className="actions">
            {chart ? (
              <>
                <Button onClick={() => openEnv(scope!.path)} title="Opens in a new tab">⊞ Env variables &amp; secrets</Button>
                <Button onClick={() => closeTab(`/folders/${id}`)}>Close tab</Button>
              </>
            ) : (
              <>
                <Button onClick={() => navigate('/')}>New comparison</Button>
                <ExportMenu url={(f) => api.folderExportUrl(id, f, sidesParam(visible))} formats={['xlsx', 'html', 'csv']} />
                <Button variant="danger" onClick={remove}>Delete</Button>
              </>
            )}
          </div>
        </div>
        <SideCards sides={sides} visible={visible} onToggle={toggleSide} detail={detail} />
      </section>

      <div className="stat-row">
        <StatButton active={filter === 'ALL'} onClick={() => setFilter('ALL')} label={`All ${unit}s`} value={stats.all}
          hint={hidden.length ? `${hidden.map((s) => sideTitle(sides[s])).join(', ')} hidden` : `in ${plural(visible.length, 'folder')}`} />
        <StatButton active={filter === 'DIFF'} onClick={() => setFilter('DIFF')} tone="red" label="With differences" value={stats.diff}
          hint={`${stats.entries.differs} differ · ${stats.entries.partial} not in every folder`} />
        <StatButton active={filter === 'SAME'} onClick={() => setFilter('SAME')} tone="green" label="Identical" value={stats.same}
          hint={`${stats.entries.identical} identical · ${stats.entries.logicallySame} logically same`} />
        {!chart && (
          <div className="stat stat-files">
            <span className="stat-label">Files in all folders</span>
            <span className="stat-value">{total(stats.files)}</span>
            <FileBar counts={stats.files} />
          </div>
        )}
      </div>

      <div className={`cmp-grid ${chart ? '' : 'with-actions'}`} style={{ '--sides': visible.length } as CSSProperties}>
        <div className="cmp-sticky">
          <div className="cmp-toolbar">
            <SearchInput value={query} onChange={setQuery} placeholder={chart ? 'Find a file' : 'Find a microservice folder'} />
            {chart && (
              <div className="btn-group">
                <Button size="sm" onClick={() => setExpanded(new Set(allDirs(scope!)))}>Expand all</Button>
                <Button size="sm" onClick={() => setExpanded(new Set())}>Collapse all</Button>
              </div>
            )}
            <div className="spacer" />
            <span className="small faint">
              {rows.length} shown · {chart ? 'click a file to compare it' : 'double-click a folder to open its Helm chart in a new tab'}
            </span>
          </div>
          <div className="cmp-head">
            <div className="ch ch-num">#</div>
            <div className="ch">Status</div>
            {visible.map((s) => <SideHeader key={s} index={s} side={sides[s]} />)}
            {!chart && <div className="ch ch-actions">Open</div>}
          </div>
        </div>

        <div className="cmp-body" onMouseLeave={() => setHover(null)}>
          {rows.length === 0 ? (
            <div className="cmp-empty">{q ? `Nothing matches “${query}”.` : 'Nothing matches the current filter.'}</div>
          ) : (
            <>
              <div className="cmp-col cmp-lead">
                {rows.map((r, i) => (
                  <div key={r.node.path} data-row={r.node.path} onMouseEnter={() => setHover(i)}
                    className={`cmp-row lead tone-${r.eval.status.toLowerCase()} ${hover === i ? 'hover' : ''} ${r.node.path === lastOpened ? 'last' : ''}`}>
                    <span className="cmp-num">{i + 1}</span>
                    <RowStatus row={r} />
                  </div>
                ))}
              </div>
              {visible.map((s) => (
                <HScroll key={s} className="cmp-col">
                  <div className="cmp-rows">
                    {rows.map((r, i) => (
                      <SideCell key={r.node.path} row={r} side={s} sideInfo={sides[s]} chart={chart} open={expanded.has(r.node.path)}
                        files={fileCounts.get(r.node)?.[s] ?? 0} hover={hover === i} last={r.node.path === lastOpened}
                        onHover={() => setHover(i)}
                        onClick={() => (r.node.dir ? (chart ? toggle(r.node.path) : select(r.node.path)) : openFile(r.node.path))}
                        onDoubleClick={() => { if (r.node.dir && !chart) openChart(r.node.path) }} />
                    ))}
                  </div>
                </HScroll>
              ))}
              {!chart && (
                <div className="cmp-col">
                  {rows.map((r, i) => (
                    <div key={r.node.path} onMouseEnter={() => setHover(i)}
                      className={`cmp-row cmp-actions ${hover === i ? 'hover' : ''} ${r.node.path === lastOpened ? 'last' : ''}`}>
                      {r.node.dir && (
                        <>
                          <button className="row-action" title="Open the Helm chart of this folder in a new tab" onClick={() => openChart(r.node.path)}>Chart ↗</button>
                          <button className="row-action" title="Compare environment variables & secrets (new tab)" onClick={() => openEnv(r.node.path)}>Env ↗</button>
                        </>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <div className="legend cmp-legend">
        <span><span className="dot dot-green" />Identical</span>
        <span><span className="dot dot-teal" />Logically same (formatting / order only)</span>
        <span><span className="dot dot-red" />Content differs</span>
        <span><span className="dot dot-orange" />Not in every folder</span>
      </div>
    </div>
  )
}

function StatButton({ label, value, hint, tone, active, onClick }: {
  label: string; value: number; hint: string; tone?: 'red' | 'green'; active: boolean; onClick: () => void
}) {
  return (
    <button className={`stat stat-btn ${active ? 'active' : ''}`} onClick={onClick} aria-pressed={active}>
      <span className="stat-label">{tone && <span className={`dot dot-${tone}`} />}{label}</span>
      <span className="stat-value">{value}</span>
      <span className="stat-hint">{hint}</span>
    </button>
  )
}

function FileBar({ counts }: { counts: { identical: number; logicallySame: number; differs: number; partial: number } }) {
  const all = total(counts) || 1
  const parts: [number, string, string][] = [
    [counts.identical, 'green', 'identical'],
    [counts.logicallySame, 'teal', 'logically same'],
    [counts.differs, 'red', 'differ'],
    [counts.partial, 'orange', 'not in every folder'],
  ]
  return (
    <>
      <span className="file-bar" role="img" aria-label={parts.map(([n, , t]) => `${n} ${t}`).join(', ')}>
        {parts.filter(([n]) => n > 0).map(([n, tone, t]) => <span key={t} className={`dot-${tone}`} style={{ width: `${(n / all) * 100}%` }} title={`${n} ${t}`} />)}
      </span>
      <span className="stat-hint">{parts.map(([n, , t]) => `${n} ${t}`).join(' · ')}</span>
    </>
  )
}

function SideHeader({ index, side }: { index: number; side: SideInfo }) {
  return (
    <div className={`ch ch-side side-tone-${index}`} title={side.label ? `${sideTitle(side)} (${side.name})` : side.name}>
      <span className="side-letter">{sideLetter(index)}</span>
      <span className="ch-title">{sideTitle(side)}</span>
      {side.label && <span className="ch-sub">{side.name}</span>}
    </div>
  )
}

function RowStatus({ row }: { row: Row }) {
  const { eval: e, node } = row
  let text: string | undefined
  if (e.status === 'DIFFERS') {
    const n = node.dir ? e.counts.differs + e.counts.partial : e.differences
    text = node.dir ? `${n} of ${total(e.counts)} differ` : n > 0 ? `${n} difference${n === 1 ? '' : 's'}` : 'Differs'
  } else if (e.status === 'PARTIAL') {
    text = e.reason
  }
  return <StatusPill status={e.status} text={text} title={e.reason} />
}

function SideCell({ row, side, sideInfo, chart, open, files, hover, last, onHover, onClick, onDoubleClick }: {
  row: Row
  side: number
  sideInfo: SideInfo
  chart: boolean
  open: boolean
  files: number
  hover: boolean
  last: boolean
  onHover: () => void
  onClick: () => void
  onDoubleClick: () => void
}) {
  const n = row.node
  const state = n.states[side]
  const cls = `cmp-row ${hover ? 'hover' : ''} ${last ? 'last' : ''}`
  const pad = 14 + row.depth * 20
  if (state === 'MISSING') {
    return (
      <div className={`${cls} cmp-missing`} style={{ paddingLeft: pad }} onMouseEnter={onHover} onClick={onClick} onDoubleClick={onDoubleClick}>
        Not in {sideTitle(sideInfo)}
      </div>
    )
  }
  const differs = row.eval.status === 'DIFFERS' || row.eval.status === 'PARTIAL'
  return (
    <div className={cls} style={{ paddingLeft: pad }} onMouseEnter={onHover} onClick={onClick} onDoubleClick={onDoubleClick}
      title={!chart && n.dir ? 'Double-click to open the Helm chart in a new tab' : row.eval.reason}>
      {chart && (n.dir ? <span className="tree-toggle">{open ? '−' : '+'}</span> : <span className="tree-toggle-space" />)}
      {n.dir ? <FolderIcon tone={differs ? 'yellow' : 'green'} /> : <FileIcon />}
      <span className="cmp-name">{n.name}</span>
      <span className="cmp-meta">
        {n.dir ? `${files} file${files === 1 ? '' : 's'}` : state === 'EMPTY' ? <span className="tbadge tb-gray">Empty</span> : formatBytes(n.sizes?.[side] ?? 0)}
      </span>
    </div>
  )
}

function matchesFilter(e: Eval, filter: Filter, dir: boolean): boolean {
  if (filter === 'ALL') return true
  if (filter === 'DIFF') return e.status === 'DIFFERS' || e.status === 'PARTIAL'
  return dir ? e.counts.identical + e.counts.logicallySame > 0 : e.status === 'IDENTICAL' || e.status === 'LOGICALLY_IDENTICAL'
}

function matchesQuery(n: FolderNode, q: string): boolean {
  if (!q) return true
  if (n.name.toLowerCase().includes(q)) return true
  return !!n.children?.some((c) => matchesQuery(c, q))
}

/** First-level entries of the uploaded parent folders. */
function topLevel(root: FolderNode, filter: Filter, q: string, evaluator: Evaluator, shown: number[]): Row[] {
  return (root.children ?? [])
    .filter((n) => isVisible(n, shown))
    .map((n) => ({ node: n, depth: 0, eval: evaluator.evaluate(n) }))
    .filter((r) => matchesFilter(r.eval, filter, r.node.dir) && matchesQuery(r.node, q))
}

function flatten(root: FolderNode, expanded: Set<string>, filter: Filter, q: string, evaluator: Evaluator, shown: number[]): Row[] {
  const rows: Row[] = []
  const walk = (nodes: FolderNode[] | undefined, depth: number) => {
    for (const n of nodes ?? []) {
      if (!isVisible(n, shown) || !matchesQuery(n, q)) continue
      const e = evaluator.evaluate(n)
      if (!matchesFilter(e, filter, n.dir)) continue
      rows.push({ node: n, depth, eval: e })
      if (n.dir && (expanded.has(n.path) || q)) walk(n.children, depth + 1)
    }
  }
  walk(root.children, 0)
  return rows
}

/** Files per folder (side) below every node. */
function countFiles(root: FolderNode, sides: number): Map<FolderNode, number[]> {
  const out = new Map<FolderNode, number[]>()
  const walk = (n: FolderNode): number[] => {
    const counts = new Array<number>(sides).fill(0)
    if (!n.dir) {
      for (let s = 0; s < sides; s++) if (n.states[s] !== 'MISSING') counts[s] = 1
    } else {
      for (const child of n.children ?? []) walk(child).forEach((c, s) => { counts[s] += c })
    }
    out.set(n, counts)
    return counts
  }
  walk(root)
  return out
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
