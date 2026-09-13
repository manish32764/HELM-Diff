import { useMemo, useState } from 'react'
import { api } from '../api/client'
import type { EnvRow, EnvSource, EnvVar } from '../api/types'
import { ExportMenu } from '../components/ExportMenu'
import { Button, SearchInput, Segmented, Spinner } from '../components/ui'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

type Filter = 'ALL' | 'COMMON' | 'LEFT_ONLY' | 'RIGHT_ONLY'
type Detail = 'ANY' | 'SIMILAR' | 'SOURCE_CHANGED' | 'VALUE_DIFFERS' | 'SAME'
type Scope = 'FILE' | 'FOLDER'

const SOURCE: Record<EnvSource, [string, string]> = {
  PLAIN: ['Plain text', 'tb-gray'],
  EMPTY: ['Empty', 'tb-gray'],
  TEMPLATE: ['Helm template', 'tb-purple'],
  AKEYLESS: ['AKeyless', 'tb-green'],
  K8S_SECRET: ['K8s Secret', 'tb-teal'],
  EXTERNAL_SECRET: ['External secret', 'tb-teal'],
  VAULT: ['Vault', 'tb-teal'],
  CONFIGMAP: ['ConfigMap', 'tb-blue'],
  FIELD_REF: ['Field ref', 'tb-blue'],
}

export function EnvVarsPage({ id, path, scope }: { id: string; path: string; scope?: string }) {
  const startsOnFolder = scope === 'FOLDER' || !/\.[A-Za-z0-9]+$/.test(path)
  const [currentScope, setCurrentScope] = useState<Scope>(startsOnFolder ? 'FOLDER' : 'FILE')
  const data = useAsync(() => api.folderEnv(id, path, currentScope), [id, path, currentScope])
  const [filter, setFilter] = useState<Filter>('ALL')
  const [detail, setDetail] = useState<Detail>('ANY')
  const [query, setQuery] = useState('')

  const view = data.data
  const isFile = /\.[A-Za-z0-9]+$/.test(path)
  const fileName = path.split('/').pop()
  const topFolder = path.includes('/') ? path.slice(0, path.indexOf('/')) : path

  const back = () => {
    if (window.history.length > 1) window.history.back()
    else navigate(`/folders/${id}`)
  }

  const rows = useMemo(() => (view?.rows ?? []).filter((r) => {
    if (filter !== 'ALL' && r.status !== filter) return false
    if (detail !== 'ANY') {
      if (r.status !== 'COMMON') return false
      if (detail === 'SIMILAR' && r.match !== 'SIMILAR_NAME') return false
      if (detail !== 'SIMILAR' && r.comparison !== detail) return false
    }
    if (!query) return true
    const q = query.toLowerCase()
    return [r.left, r.right].some((v) => v && (v.name.toLowerCase().includes(q) || (v.value ?? '').toLowerCase().includes(q)
      || (v.reference ?? '').toLowerCase().includes(q)))
  }), [view, filter, detail, query])

  const s = view?.summary

  return (
    <div className="env-page">
      <div className="fc-bar">
        <Button onClick={back}>← Back</Button>
        <div className="fc-path">
          <span className="faint">{view ? `${view.leftName} ↔ ${view.rightName} /` : ''}</span>{' '}
          <b>Environment variables &amp; secrets</b>
        </div>
        {view && (
          <ExportMenu url={(f) => api.folderEnvExportUrl(id, path, currentScope, f)} formats={['xlsx', 'csv', 'html']} />
        )}
      </div>

      <div className="fill-toolbar">
        <span className="small muted">Scope</span>
        {isFile ? (
          <Segmented value={currentScope} onChange={setCurrentScope} options={[
            { value: 'FILE', label: `This file · ${fileName}` },
            { value: 'FOLDER', label: `Whole folder · ${topFolder}` },
          ]} />
        ) : <span className="chip mono">{path || 'all folders'}</span>}
        {view && <span className="small faint">{view.files.length} file(s) scanned</span>}
        <div className="spacer" />
        <SearchInput value={query} onChange={setQuery} placeholder="Find a variable, value or secret path" />
      </div>

      {data.loading && <Spinner />}
      {data.error && <div className="banner banner-error">{data.error}</div>}

      {view && s && (
        <>
          <div className="env-filters">
            <FilterButton active={filter === 'ALL'} tone="all" label="All variables" count={s.total} onClick={() => { setFilter('ALL'); setDetail('ANY') }} />
            <FilterButton active={filter === 'COMMON'} tone="common" label="Common" count={s.common} onClick={() => setFilter('COMMON')} />
            <FilterButton active={filter === 'LEFT_ONLY'} tone="left" label={`Only in left · ${view.leftName}`} count={s.leftOnly}
              onClick={() => { setFilter('LEFT_ONLY'); setDetail('ANY') }} />
            <FilterButton active={filter === 'RIGHT_ONLY'} tone="right" label={`Only in right · ${view.rightName}`} count={s.rightOnly}
              onClick={() => { setFilter('RIGHT_ONLY'); setDetail('ANY') }} />
          </div>

          <div className="fill-toolbar">
            <span className="small muted">Common variables:</span>
            <Segmented value={detail} onChange={(d) => { setDetail(d); if (d !== 'ANY' && filter !== 'COMMON') setFilter('COMMON') }} options={[
              { value: 'ANY', label: 'Any' },
              { value: 'SOURCE_CHANGED', label: 'Source changed (e.g. plain → AKeyless)', count: s.sourceChanged },
              { value: 'VALUE_DIFFERS', label: 'Value differs', count: s.valueDiffers },
              { value: 'SAME', label: 'Same', count: s.same },
              { value: 'SIMILAR', label: 'Matched by similar name', count: s.similarNames },
            ]} />
          </div>

          <div className="xl-wrap">
            <table className="xl">
              <colgroup>
                <col style={{ width: 46 }} />
                <col style={{ width: '14%' }} /><col style={{ width: '17%' }} /><col style={{ width: '8%' }} />
                <col style={{ width: '14%' }} /><col style={{ width: '17%' }} /><col style={{ width: '8%' }} />
                <col style={{ width: 190 }} />
              </colgroup>
              <thead>
                <tr className="xl-group">
                  <th rowSpan={2} className="xl-num">#</th>
                  <th colSpan={3} className="xl-side">Left · {view.leftName}{view.leftLabel ? ` (${view.leftLabel})` : ''}</th>
                  <th colSpan={3} className="xl-side xl-right-start">Right · {view.rightName}{view.rightLabel ? ` (${view.rightLabel})` : ''}</th>
                  <th rowSpan={2} className="xl-right-start">Comparison</th>
                </tr>
                <tr>
                  <th>Variable</th><th>Value</th><th>Source</th>
                  <th className="xl-right-start">Variable</th><th>Value</th><th>Source</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr><td colSpan={8} className="xl-empty">No variables match this filter.</td></tr>
                )}
                {rows.map((r, i) => (
                  <tr key={r.id} className={`xl-row xl-${r.status.toLowerCase()}`}>
                    <td className="xl-num">{i + 1}</td>
                    <SideCells v={r.left} row={r} showFile={currentScope === 'FOLDER'} />
                    <SideCells v={r.right} row={r} showFile={currentScope === 'FOLDER'} rightSide />
                    <td className="xl-right-start"><Comparison row={r} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}

function FilterButton({ active, tone, label, count, onClick }: { active: boolean; tone: string; label: string; count: number; onClick: () => void }) {
  return (
    <button className={`env-filter env-${tone} ${active ? 'active' : ''}`} onClick={onClick}>
      <span className="env-filter-count">{count}</span>
      <span className="env-filter-label">{label}</span>
    </button>
  )
}

function SideCells({ v, row, showFile, rightSide }: { v?: EnvVar; row: EnvRow; showFile: boolean; rightSide?: boolean }) {
  const edge = rightSide ? 'xl-right-start' : ''
  if (!v) return <td colSpan={3} className={`xl-missing ${edge}`}>— not present —</td>
  const valueChanged = row.status === 'COMMON' && row.comparison !== 'SAME'
  const [label, cls] = SOURCE[v.source] ?? [v.source, 'tb-gray']
  return (
    <>
      <td className={edge}>
        <div className="xl-name">{v.name}</div>
        <div className="xl-meta">{v.kind}{showFile ? ` · ${v.file}:${v.line}` : ` · line ${v.line}`}</div>
      </td>
      <td className={valueChanged ? 'xl-changed' : ''}>
        {v.value ? <div className="xl-value">{v.value}</div> : null}
        {v.reference ? <div className="xl-ref">↳ {v.reference}</div> : null}
        {!v.value && !v.reference ? <span className="faint">(empty)</span> : null}
      </td>
      <td className={row.comparison === 'SOURCE_CHANGED' ? 'xl-changed' : ''}><span className={`tbadge ${cls}`}>{label}</span></td>
    </>
  )
}

function Comparison({ row }: { row: EnvRow }) {
  if (row.status === 'LEFT_ONLY') return <span className="tbadge tb-solid-orange">Only in left</span>
  if (row.status === 'RIGHT_ONLY') return <span className="tbadge tb-solid-blue">Only in right</span>
  const change = row.comparison === 'SOURCE_CHANGED'
    ? <span className="tbadge tb-orange">{SOURCE[row.left!.source]?.[0]} → {SOURCE[row.right!.source]?.[0]}</span>
    : row.comparison === 'VALUE_DIFFERS' ? <span className="tbadge tb-amber">Value differs</span>
      : <span className="tbadge tb-green">Same</span>
  return (
    <div className="stack-v" style={{ gap: 4 }}>
      <div>{change}</div>
      {row.match === 'SIMILAR_NAME' && <div><span className="tbadge tb-teal" title="Names differ but refer to the same variable">Similar name {row.similarity}%</span></div>}
    </div>
  )
}
