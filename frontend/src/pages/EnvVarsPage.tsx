import { useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import type { EnvRow, EnvSource, EnvVar, EnvView } from '../api/types'
import { ExportMenu } from '../components/ExportMenu'
import { Button, SearchInput, Segmented, Spinner, Toggle, useToast } from '../components/ui'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

type Verdict = 'LEFT_ONLY' | 'RIGHT_ONLY' | 'VALUE_DIFFERS' | 'UNVERIFIED' | 'SAME'
type Filter = 'ALL' | Verdict
type Flag = 'ANY' | 'SOURCE_CHANGED' | 'SIMILAR' | 'DUPLICATE'
type Scope = 'FILE' | 'FOLDER'
type Sort = 'ISSUES' | 'NAME'

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

const SEVERITY: Record<Verdict, number> = { LEFT_ONLY: 0, RIGHT_ONLY: 0, VALUE_DIFFERS: 1, UNVERIFIED: 2, SAME: 3 }

const verdictOf = (r: EnvRow): Verdict => r.status === 'COMMON' ? r.comparison! : r.status

export function EnvVarsPage({ id, path, scope }: { id: string; path: string; scope?: string }) {
  const startsOnFolder = scope === 'FOLDER' || !/\.[A-Za-z0-9]+$/.test(path)
  const [currentScope, setCurrentScope] = useState<Scope>(startsOnFolder ? 'FOLDER' : 'FILE')
  const data = useAsync(() => api.folderEnv(id, path, currentScope), [id, path, currentScope])
  const [filter, setFilter] = useState<Filter>('ALL')
  const [flag, setFlag] = useState<Flag>('ANY')
  const [sort, setSort] = useState<Sort>('ISSUES')
  const [query, setQuery] = useState('')
  const [showSecrets, setShowSecrets] = useState(false)

  const view = data.data
  const isFile = /\.[A-Za-z0-9]+$/.test(path)
  const fileName = path.split('/').pop()
  const topFolder = path.includes('/') ? path.slice(0, path.indexOf('/')) : path

  const back = () => {
    if (window.history.length > 1) window.history.back()
    else navigate(`/folders/${id}`)
  }

  const rows = useMemo(() => {
    const q = query.toLowerCase()
    const matches = (v?: EnvVar) => !!v && [v.name, v.value, v.effectiveValue, v.reference, v.akeylessPath, v.injection]
      .some((t) => (t ?? '').toLowerCase().includes(q))
    const list = (view?.rows ?? []).filter((r) => {
      if (filter !== 'ALL' && verdictOf(r) !== filter) return false
      if (flag === 'SOURCE_CHANGED' && !r.sourceChanged) return false
      if (flag === 'SIMILAR' && r.match !== 'SIMILAR_NAME') return false
      if (flag === 'DUPLICATE' && r.leftOthers.length + r.rightOthers.length === 0) return false
      return !q || matches(r.left) || matches(r.right) || [...r.leftOthers, ...r.rightOthers].some(matches)
    })
    return sort === 'ISSUES' ? [...list].sort((a, b) => SEVERITY[verdictOf(a)] - SEVERITY[verdictOf(b)]) : list
  }, [view, filter, flag, sort, query])

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
          <ExportMenu url={(f) => api.folderEnvExportUrl(id, path, currentScope, f, showSecrets)} formats={['xlsx', 'csv', 'html']} />
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
        <SearchInput value={query} onChange={setQuery} placeholder="Find a variable, value, AKeyless path or secret" />
      </div>

      {data.loading && !view && <Spinner />}
      {data.error && <div className="banner banner-error">{data.error}</div>}

      {view && s && (
        <>
          <SecretValuesBar id={id} view={view} showSecrets={showSecrets} onShowSecrets={setShowSecrets} onChanged={data.reload} />

          <div className="env-filters">
            <FilterButton active={filter === 'ALL'} tone="all" label="All variables" count={s.total} onClick={() => setFilter('ALL')} />
            <FilterButton active={filter === 'LEFT_ONLY'} tone="left" label={`Missing in right · ${view.rightName}`} count={s.leftOnly}
              hint="Only defined on the left" onClick={() => setFilter('LEFT_ONLY')} />
            <FilterButton active={filter === 'RIGHT_ONLY'} tone="right" label={`Missing in left · ${view.leftName}`} count={s.rightOnly}
              hint="Only defined on the right" onClick={() => setFilter('RIGHT_ONLY')} />
            <FilterButton active={filter === 'VALUE_DIFFERS'} tone="diff" label="Different value" count={s.valueDiffers}
              hint="Both sides receive a different value" onClick={() => setFilter('VALUE_DIFFERS')} />
            <FilterButton active={filter === 'UNVERIFIED'} tone="unverified" label="Can't verify" count={s.unverified}
              hint="A value is not known, e.g. AKeyless path not in JSON" onClick={() => setFilter('UNVERIFIED')} />
            <FilterButton active={filter === 'SAME'} tone="common" label="Same value" count={s.same}
              hint="Receives the same value on both sides" onClick={() => setFilter('SAME')} />
          </div>

          <div className="fill-toolbar">
            <span className="small muted">Only rows that</span>
            <Segmented value={flag} onChange={setFlag} options={[
              { value: 'ANY', label: 'Any' },
              { value: 'SOURCE_CHANGED', label: 'Change source (plain ↔ AKeyless)', count: s.sourceChanged },
              { value: 'SIMILAR', label: 'Matched by similar name', count: s.similarNames },
              { value: 'DUPLICATE', label: 'Are defined more than once', count: s.duplicates },
            ]} />
            <div className="spacer" />
            <span className="small muted">Order</span>
            <Segmented value={sort} onChange={setSort} options={[
              { value: 'ISSUES', label: 'Problems first' },
              { value: 'NAME', label: 'A–Z' },
            ]} />
          </div>

          <div className="xl-wrap">
            <table className="xl env-xl">
              <colgroup>
                <col style={{ width: 44 }} />
                <col style={{ width: '15%' }} />
                <col style={{ width: '17%' }} /><col style={{ width: '17%' }} />
                <col style={{ width: '17%' }} /><col style={{ width: '17%' }} />
                <col style={{ width: 170 }} />
              </colgroup>
              <thead>
                <tr className="xl-group">
                  <th rowSpan={2} className="xl-num">#</th>
                  <th rowSpan={2}>Variable</th>
                  <th colSpan={2} className="xl-side xl-right-start">Left · {view.leftName}{view.leftLabel ? ` (${view.leftLabel})` : ''}</th>
                  <th colSpan={2} className="xl-side xl-right-start">Right · {view.rightName}{view.rightLabel ? ` (${view.rightLabel})` : ''}</th>
                  <th rowSpan={2} className="xl-right-start">Result</th>
                </tr>
                <tr>
                  <th className="xl-right-start">Injected via</th><th>Value the container receives</th>
                  <th className="xl-right-start">Injected via</th><th>Value the container receives</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr><td colSpan={7} className="xl-empty">No variables match this filter.</td></tr>
                )}
                {rows.map((r, i) => (
                  <tr key={r.id} className={`xl-row env-v-${verdictOf(r).toLowerCase()}`}>
                    <td className="xl-num">{i + 1}</td>
                    <td><VariableName row={r} /></td>
                    <SideCells v={r.left} others={r.leftOthers} row={r} showSecrets={showSecrets} missingIn={view.leftName} />
                    <SideCells v={r.right} others={r.rightOthers} row={r} showSecrets={showSecrets} missingIn={view.rightName} />
                    <td className="xl-right-start"><Result row={r} view={view} /></td>
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

// ───────────────────────────── AKeyless values ─────────────────────────────

function SecretValuesBar({ id, view, showSecrets, onShowSecrets, onChanged }: {
  id: string; view: EnvView; showSecrets: boolean; onShowSecrets: (v: boolean) => void; onChanged: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const replace = useRef(false)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const toast = useToast()
  const s = view.secrets
  const loaded = s.loadedPaths > 0
  const missingPaths = [...new Set(s.missing.map((m) => m.path))]

  const choose = (replaceExisting: boolean) => {
    replace.current = replaceExisting
    input.current?.click()
  }
  const upload = async (files: FileList | null) => {
    if (!files?.length) return
    setBusy(true)
    try {
      const info = await api.uploadSecretValues(id, [...files], replace.current)
      toast(`${info.paths} AKeyless path(s) loaded`)
      onChanged()
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setBusy(false)
      if (input.current) input.current.value = ''
    }
  }
  const clear = async () => {
    if (!window.confirm('Remove the uploaded AKeyless values from this comparison?')) return
    await api.clearSecretValues(id)
    onChanged()
  }
  const copy = async (text: string, what: string) => {
    await navigator.clipboard.writeText(text)
    toast(`${what} copied`)
  }
  const template = JSON.stringify(Object.fromEntries(missingPaths.map((p) => [p.split(' › ')[0], ''])), null, 2)
  const pct = s.referenced ? Math.round((s.resolved / s.referenced) * 100) : 0

  return (
    <div className={`secrets-bar ${loaded ? 'loaded' : ''}`}>
      <div className="secrets-main">
        <div className="secrets-icon" aria-hidden>🔑</div>
        <div className="secrets-text">
          <div className="secrets-title">AKeyless values</div>
          {!loaded && s.referenced === 0 && <div className="small muted">No AKeyless paths are referenced in this scope.</div>}
          {!loaded && s.referenced > 0 && (
            <div className="small muted">
              <b>{s.referenced}</b> variable(s) get their value from AKeyless, so their values can't be compared yet.
              Upload a JSON with the value of each path: <code>{'{ "/Platform/…/API_KEY": "value" }'}</code>
              <span className="faint"> (nested folders or <code>[{'{ path, value }'}]</code> also work)</span>
            </div>
          )}
          {loaded && (
            <div className="small muted">
              <b>{s.loadedPaths}</b> path(s) loaded from {s.files.join(', ')} ·{' '}
              <b>{s.resolved}</b> of {s.referenced} AKeyless reference(s) resolved
              {s.missing.length > 0 && (
                <> · <button className="link-btn warn" onClick={() => setOpen((o) => !o)}>
                  {missingPaths.length} path(s) not in the JSON {open ? '▴' : '▾'}
                </button></>
              )}
            </div>
          )}
          {s.referenced > 0 && (
            <div className="secrets-meter" title={`${s.resolved} of ${s.referenced} resolved`}>
              <span style={{ width: `${pct}%` }} />
            </div>
          )}
        </div>
        <div className="secrets-actions">
          <input ref={input} type="file" accept=".json,application/json" multiple hidden onChange={(e) => upload(e.target.files)} />
          {loaded ? (
            <>
              <Button size="sm" disabled={busy} onClick={() => choose(false)} title="Merge another JSON into the loaded values">+ Add JSON</Button>
              <Button size="sm" disabled={busy} onClick={() => choose(true)}>Replace</Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={clear}>Clear</Button>
            </>
          ) : (
            <Button size="sm" variant="primary" disabled={busy} onClick={() => choose(false)}>{busy ? 'Uploading…' : 'Upload values JSON'}</Button>
          )}
          {!loaded && s.missing.length > 0 && (
            <Button size="sm" variant="ghost" onClick={() => setOpen((o) => !o)}>Paths {open ? '▴' : '▾'}</Button>
          )}
          <Toggle checked={showSecrets} onChange={onShowSecrets} label="Show secret values" />
        </div>
      </div>
      {open && s.missing.length > 0 && (
        <div className="secrets-missing">
          <div className="secrets-missing-head">
            <span className="small muted">AKeyless paths referenced by the charts{loaded ? ' but missing from the JSON' : ''}</span>
            <div className="spacer" />
            <Button size="sm" variant="ghost" onClick={() => copy(missingPaths.join('\n'), 'Paths')}>Copy paths</Button>
            <Button size="sm" variant="ghost" onClick={() => copy(template, 'JSON template')}>Copy JSON template</Button>
          </div>
          <table className="secrets-table">
            <tbody>
              {s.missing.map((m, i) => (
                <tr key={i}>
                  <td><span className={`tbadge ${m.side === 'LEFT' ? 'tb-orange' : 'tb-blue'}`}>{m.side === 'LEFT' ? view.leftName : view.rightName}</span></td>
                  <td className="mono">{m.variable}</td>
                  <td className="mono">{m.path}</td>
                  <td className="faint">{m.file}:{m.line}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ───────────────────────────── table cells ─────────────────────────────

function FilterButton({ active, tone, label, count, hint, onClick }: {
  active: boolean; tone: string; label: string; count: number; hint?: string; onClick: () => void
}) {
  return (
    <button className={`env-filter env-${tone} ${active ? 'active' : ''} ${count === 0 ? 'zero' : ''}`} onClick={onClick} title={hint ? `${label} — ${hint}` : label}>
      <span className="env-filter-count">{count}</span>
      <span className="env-filter-label">{label}</span>
    </button>
  )
}

function VariableName({ row }: { row: EnvRow }) {
  const l = row.left?.name
  const r = row.right?.name
  return (
    <>
      <div className="xl-name">{l ?? r}</div>
      {l && r && l !== r && <div className="xl-meta">↔ <span className="mono">{r}</span></div>}
    </>
  )
}

function SideCells({ v, others, row, showSecrets, missingIn }: {
  v?: EnvVar; others: EnvVar[]; row: EnvRow; showSecrets: boolean; missingIn: string
}) {
  if (!v) {
    return <td colSpan={2} className="xl-missing xl-missing-alert xl-right-start">✕ Not defined in {missingIn}</td>
  }
  const other = row.left === v ? row.right : row.left
  return (
    <>
      <td className="xl-right-start"><Injection v={v} /></td>
      <td className={row.comparison === 'VALUE_DIFFERS' ? 'xl-changed' : row.comparison === 'UNVERIFIED' && v.effectiveValue == null ? 'xl-unknown' : ''}>
        <Value v={v} other={row.comparison === 'VALUE_DIFFERS' ? other : undefined} showSecrets={showSecrets} />
        {others.length > 0 && (
          <div className={`env-dup ${row.duplicateConflict ? 'conflict' : ''}`}>
            <div className="env-dup-title">{row.duplicateConflict ? '⚠ ' : ''}Also defined {others.length}×</div>
            {others.map((o, i) => (
              <div key={i} className="env-dup-item">
                <span className="faint">{steps(o.injection)[0]?.text ?? o.kind} · line {o.line}</span>
                <Value v={o} showSecrets={showSecrets} compact />
              </div>
            ))}
          </div>
        )}
      </td>
    </>
  )
}

function steps(injection?: string) {
  return (injection ?? '').split(' › ').filter(Boolean).map((step) => {
    const at = step.lastIndexOf(' @ ')
    return at < 0 ? { text: step } : { text: step.slice(0, at), location: step.slice(at + 3) }
  })
}

function Injection({ v }: { v: EnvVar }) {
  const [label, cls] = SOURCE[v.source] ?? [v.source, 'tb-gray']
  const chain = steps(v.injection)
  return (
    <>
      <span className={`tbadge ${cls}`}>{label}</span>
      {chain.length > 0 && (
        <ol className="inj-chain">
          {chain.map((s, i) => (
            <li key={i} title={s.location ?? undefined}>
              <span className="mono">{s.text}</span>
              {s.location && <span className="inj-loc">{s.location}</span>}
            </li>
          ))}
        </ol>
      )}
      <div className="xl-meta">{v.file}:{v.line}</div>
    </>
  )
}

const isSecret = (v: EnvVar) => v.valueState === 'RESOLVED' || v.kind === 'K8s Secret data' || v.kind === 'Secret value'

function Value({ v, other, showSecrets, compact }: { v: EnvVar; other?: EnvVar; showSecrets: boolean; compact?: boolean }) {
  const value = v.effectiveValue
  const hidden = isSecret(v) && !showSecrets
  let shown: ReactNode = null
  if (value != null) {
    if (hidden) shown = <span className="val-masked" title="Turn on “Show secret values” to reveal">{'•'.repeat(8)} <span className="faint">{value.length} chars</span></span>
    else if (value === '') shown = <span className="faint">(empty)</span>
    else if (other?.effectiveValue != null && !(isSecret(other) && !showSecrets)) shown = <DiffText value={value} against={other.effectiveValue} />
    else shown = value
  }
  return (
    <>
      {shown != null && <div className="xl-value">{shown}</div>}
      {v.valueState === 'RESOLVED' && !compact && <div className="val-state ok">✓ value from AKeyless JSON</div>}
      {v.valueState === 'NOT_IN_JSON' && <div className="val-state warn">⚠ path not found in the JSON</div>}
      {v.valueState === 'NO_JSON' && <div className="val-state warn">AKeyless value not uploaded</div>}
      {v.valueState === 'UNKNOWN' && <div className="val-state">Value is not in the chart{v.reference ? ` · ${v.reference}` : ''}</div>}
      {v.akeylessPath && <div className="xl-ref" title="AKeyless path"><span aria-hidden>{"\uD83D\uDD11\u00A0"}</span>{v.akeylessPath}</div>}
    </>
  )
}

/** Highlights the part of a value that differs from the other side (common prefix and suffix stay plain). */
function DiffText({ value, against }: { value: string; against: string }) {
  let start = 0
  while (start < value.length && start < against.length && value[start] === against[start]) start++
  let end = 0
  while (end < value.length - start && end < against.length - start
    && value[value.length - 1 - end] === against[against.length - 1 - end]) end++
  const mid = value.slice(start, value.length - end)
  return <>{value.slice(0, start)}{mid && <mark>{mid}</mark>}{value.slice(value.length - end)}</>
}

function family(source: EnvSource) {
  return source === 'EMPTY' || source === 'TEMPLATE' ? 'Plain text' : SOURCE[source]?.[0] ?? source
}

function Result({ row, view }: { row: EnvRow; view: EnvView }) {
  const verdict = verdictOf(row)
  const l = row.left
  const r = row.right
  const main: Record<Verdict, ReactNode> = {
    LEFT_ONLY: <><span className="verdict v-missing">✕ Missing in right</span><div className="xl-meta">Not set when deploying {view.rightName}</div></>,
    RIGHT_ONLY: <><span className="verdict v-missing">✕ Missing in left</span><div className="xl-meta">Only set in {view.rightName}</div></>,
    VALUE_DIFFERS: <span className="verdict v-diff">≠ Different value</span>,
    UNVERIFIED: <><span className="verdict v-unverified">? Can't verify</span>
      <div className="xl-meta">{[l, r].filter((v) => v && v.effectiveValue == null)
        .map((v) => `${v === l ? 'left' : 'right'}: ${v!.valueState === 'NO_JSON' ? 'upload AKeyless JSON' : v!.valueState === 'NOT_IN_JSON' ? 'path not in JSON' : 'value outside chart'}`)
        .join(' · ')}</div></>,
    SAME: <span className="verdict v-same">✓ {l?.effectiveValue == null && l?.akeylessPath ? 'Same AKeyless path' : 'Same value'}</span>,
  }
  return (
    <div className="stack-v" style={{ gap: 4 }}>
      <div>{main[verdict]}</div>
      {row.sourceChanged && l && r && <div><span className="tbadge tb-orange">{family(l.source)} → {family(r.source)}</span></div>}
      {row.match === 'SIMILAR_NAME' && <div><span className="tbadge tb-teal" title="Names differ but refer to the same variable">Similar name {row.similarity}%</span></div>}
      {row.duplicateConflict && <div><span className="tbadge tb-red" title="The same variable is defined more than once with different values">Defined twice, values differ</span></div>}
    </div>
  )
}
