import { Fragment, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { EnvRow, EnvSource, EnvVar, EnvView } from '../api/types'
import { DiffText } from '../components/DiffText'
import { ExportMenu } from '../components/ExportMenu'
import { Button, SearchInput, Segmented, Spinner, useToast } from '../components/ui'
import { closeTab, sideTitle } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

type Verdict = 'LEFT_ONLY' | 'RIGHT_ONLY' | 'VALUE_DIFFERS' | 'UNVERIFIED' | 'SAME'
type Filter = 'ALL' | Verdict
type Scope = 'FILE' | 'FOLDER'

const SOURCE: Record<EnvSource, [string, string]> = {
  PLAIN: ['Plain text', 'tb-gray'],
  EMPTY: ['Empty', 'tb-gray'],
  TEMPLATE: ['Template', 'tb-purple'],
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
  const [query, setQuery] = useState('')
  const [showInjected, setShowInjected] = useState(true)
  const [showValue, setShowValue] = useState(true)
  const [showSecrets, setShowSecrets] = useState(false)

  useEffect(() => {
    document.title = `Env variables & secrets · ${path}`
  }, [path])

  const view = data.data
  const isFile = /\.[A-Za-z0-9]+$/.test(path)
  const fileName = path.split('/').pop()
  const topFolder = path.includes('/') ? path.slice(0, path.indexOf('/')) : path
  const left = view ? sideTitle(view.leftName, view.leftLabel) : ''
  const right = view ? sideTitle(view.rightName, view.rightLabel) : ''

  const rows = useMemo(() => {
    const q = query.toLowerCase()
    const matches = (v?: EnvVar) => !!v && [v.name, v.effectiveValue, v.reference, v.akeylessPath, v.injection]
      .some((t) => (t ?? '').toLowerCase().includes(q))
    return (view?.rows ?? [])
      .filter((r) => (filter === 'ALL' || verdictOf(r) === filter) && (!q || matches(r.left) || matches(r.right)))
      .sort((a, b) => SEVERITY[verdictOf(a)] - SEVERITY[verdictOf(b)])
  }, [view, filter, query])

  const s = view?.summary
  const cols = (showInjected ? 1 : 0) + (showValue ? 1 : 0)

  return (
    <div className="env-page">
      <header className="env-head">
        <div style={{ minWidth: 0 }}>
          <div className="eyebrow">Environment variables &amp; secrets · {currentScope === 'FILE' ? path : (view?.scopePath || topFolder || 'all folders')}</div>
          <h1 className="env-title">
            {view ? <><span className="mono">{left}</span> <span className="faint">↔</span> <span className="mono">{right}</span></> : '…'}
          </h1>
        </div>
        <div className="actions">
          {view && <ExportMenu url={(f) => api.folderEnvExportUrl(id, path, currentScope, f, showSecrets)} formats={['xlsx', 'csv', 'html']} />}
          <Button onClick={() => closeTab(`/folders/${id}`)}>Close tab</Button>
        </div>
      </header>

      <div className="env-controls">
        {isFile && (
          <Segmented value={currentScope} onChange={setCurrentScope} options={[
            { value: 'FILE', label: `This file · ${fileName}` },
            { value: 'FOLDER', label: `Whole folder · ${topFolder}` },
          ]} />
        )}
        <SearchInput value={query} onChange={setQuery} placeholder="Find a variable, value or AKeyless path" />
        <div className="spacer" />
        <div className="env-checks" role="group" aria-label="Columns">
          <span className="small muted">Show</span>
          <Check label="Injected via" checked={showInjected} disabled={showInjected && !showValue} onChange={setShowInjected} />
          <Check label="Value" checked={showValue} disabled={showValue && !showInjected} onChange={setShowValue} />
          <span className="env-checks-sep" />
          <Check label="Reveal secret values" checked={showSecrets} onChange={setShowSecrets} />
        </div>
      </div>

      {data.loading && !view && <Spinner />}
      {data.error && <div className="banner banner-error">{data.error}</div>}

      {view && s && (
        <>
          <div className="env-tabs" role="tablist">
            {([
              ['ALL', 'All', s.total, 'all'],
              ['LEFT_ONLY', `Missing in ${right}`, s.leftOnly, 'missing'],
              ['RIGHT_ONLY', `Missing in ${left}`, s.rightOnly, 'missing'],
              ['VALUE_DIFFERS', 'Different value', s.valueDiffers, 'diff'],
              ['UNVERIFIED', "Can't verify", s.unverified, 'unverified'],
              ['SAME', 'Same value', s.same, 'same'],
            ] as [Filter, string, number, string][]).map(([value, label, count, tone]) => (
              <button key={value} role="tab" aria-selected={filter === value} onClick={() => setFilter(value)}
                className={`env-tab env-tab-${tone} ${filter === value ? 'active' : ''} ${count === 0 ? 'zero' : ''}`}>
                <span>{label}</span><b>{count}</b>
              </button>
            ))}
          </div>

          <SecretsNote view={view} left={left} right={right} />

          <div className="xl-wrap">
            <table className="xl env-xl" style={{ minWidth: cols === 2 ? 1100 : 780 }}>
              <colgroup>
                <col style={{ width: 44 }} />
                <col style={{ width: cols === 2 ? '16%' : '22%' }} />
                {Array.from({ length: cols * 2 }, (_, i) => <col key={i} />)}
                <col style={{ width: 170 }} />
              </colgroup>
              <thead>
                <tr className="xl-group">
                  <th rowSpan={2} className="xl-num">#</th>
                  <th rowSpan={2}>Variable</th>
                  <th colSpan={cols} className="xl-right-start">{left}</th>
                  <th colSpan={cols} className="xl-right-start">{right}</th>
                  <th rowSpan={2} className="xl-right-start">Result</th>
                </tr>
                <tr>
                  {[0, 1].map((side) => (
                    <Fragment key={side}>
                      {showInjected && <th className="xl-right-start">Injected via</th>}
                      {showValue && <th className={showInjected ? '' : 'xl-right-start'}>Value</th>}
                    </Fragment>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && <tr><td colSpan={3 + cols * 2} className="xl-empty">No variables match.</td></tr>}
                {rows.map((r, i) => (
                  <tr key={r.id} className={`xl-row env-v-${verdictOf(r).toLowerCase()}`}>
                    <td className="xl-num">{i + 1}</td>
                    <td><VariableName row={r} /></td>
                    <SideCells v={r.left} other={r.right} others={r.leftOthers} row={r} missingIn={left}
                      showInjected={showInjected} showValue={showValue} showSecrets={showSecrets} />
                    <SideCells v={r.right} other={r.left} others={r.rightOthers} row={r} missingIn={right}
                      showInjected={showInjected} showValue={showValue} showSecrets={showSecrets} />
                    <td className="xl-right-start"><Result row={r} left={left} right={right} /></td>
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

function Check({ label, checked, onChange, disabled }: { label: string; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <label className={`check ${disabled ? 'disabled' : ''}`} title={disabled ? 'At least one column stays visible' : undefined}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  )
}

/** Which JSON each environment uses, and AKeyless paths whose value is still unknown. */
function SecretsNote({ view, left, right }: { view: EnvView; left: string; right: string }) {
  const [open, setOpen] = useState(false)
  const toast = useToast()
  const s = view.secrets
  if (s.referenced === 0 && s.left.paths === 0 && s.right.paths === 0) return null

  const describe = (title: string, info: EnvView['secrets']['left']) =>
    info.paths ? `${title}: ${info.files.join(', ')} (${info.paths} paths)` : `${title}: no JSON`
  const missing = (side: 'LEFT' | 'RIGHT') => s.missing.filter((m) => m.side === side)
  const copyTemplate = async (side: 'LEFT' | 'RIGHT') => {
    const paths = [...new Set(missing(side).map((m) => m.path.split(' › ')[0]))]
    await navigator.clipboard.writeText(JSON.stringify(Object.fromEntries(paths.map((p) => [p, ''])), null, 2))
    toast('JSON template copied')
  }

  return (
    <div className="env-secrets">
      <span className="env-secrets-title">AKeyless values</span>
      <span>{describe(left, s.left)}</span>
      <span className="faint">·</span>
      <span>{describe(right, s.right)}</span>
      <span className="faint">·</span>
      <span><b>{s.resolved}</b> of {s.referenced} resolved</span>
      {s.missing.length > 0 && (
        <button className="link-btn warn" onClick={() => setOpen((o) => !o)}>{s.missing.length} unknown {open ? '▴' : '▾'}</button>
      )}
      {open && (
        <div className="env-secrets-missing">
          {(['LEFT', 'RIGHT'] as const).filter((side) => missing(side).length > 0).map((side) => (
            <div key={side}>
              <b className="small">{side === 'LEFT' ? left : right}</b>{' '}
              <button className="link-btn" onClick={() => copyTemplate(side)}>Copy JSON template</button>
              <ul>
                {missing(side).map((m, i) => <li key={i}><span className="mono">{m.variable}</span> <span className="mono faint">{m.path}</span></li>)}
              </ul>
            </div>
          ))}
          <div className="small faint">Choose each environment's AKeyless JSON on the home screen, next to its folder.</div>
        </div>
      )}
    </div>
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

function SideCells({ v, other, others, row, missingIn, showInjected, showValue, showSecrets }: {
  v?: EnvVar; other?: EnvVar; others: EnvVar[]; row: EnvRow; missingIn: string
  showInjected: boolean; showValue: boolean; showSecrets: boolean
}) {
  const cols = (showInjected ? 1 : 0) + (showValue ? 1 : 0)
  if (!v) return <td colSpan={cols} className="xl-missing xl-missing-alert xl-right-start">Not defined in {missingIn}</td>
  const tint = row.comparison === 'VALUE_DIFFERS' ? 'xl-changed' : row.comparison === 'UNVERIFIED' && v.effectiveValue == null ? 'xl-unknown' : ''
  const duplicates = others.length > 0 && <Duplicates others={others} conflict={row.duplicateConflict} showSecrets={showSecrets} />
  return (
    <>
      {showInjected && (
        <td className="xl-right-start">
          <Injected v={v} />
          {!showValue && duplicates}
        </td>
      )}
      {showValue && (
        <td className={`${showInjected ? '' : 'xl-right-start'} ${tint}`}>
          <Value v={v} other={row.comparison === 'VALUE_DIFFERS' ? other : undefined} showSecrets={showSecrets} />
          {duplicates}
        </td>
      )}
    </>
  )
}

function steps(injection?: string) {
  return (injection ?? '').split(' › ').filter(Boolean).map((step) => {
    const at = step.lastIndexOf(' @ ')
    return at < 0 ? { text: step } : { text: step.slice(0, at), location: step.slice(at + 3) }
  })
}

/** "externalsecrets.akeyless.secretItems" → "akeyless.secretItems"; "Secret x · key Y" → "secret x/Y". */
function shortStep(text: string) {
  const secret = /^Secret (.+) · key (.+)$/.exec(text)
  if (secret) return `secret ${secret[1]}/${secret[2]}`
  if (/^[\w-]+(\.[\w-]+){2,}$/.test(text)) return text.split('.').slice(-2).join('.')
  return text
}

function Injected({ v }: { v: EnvVar }) {
  const [label, cls] = SOURCE[v.source] ?? [v.source, 'tb-gray']
  const chain = steps(v.injection)
  const details = [...chain.map((s) => (s.location ? `${s.text}  (${s.location})` : s.text)), `defined at ${v.file}:${v.line}`].join('\n')
  return (
    <>
      <div className="inj">
        <span className={`tbadge ${cls}`}>{label}</span>{' '}
        <span className="inj-text" title={details}>{chain.map((s) => shortStep(s.text)).join(' → ')}</span>
      </div>
      {v.akeylessPath && <div className="xl-ref" title="AKeyless path">{v.akeylessPath}</div>}
    </>
  )
}

const isSecret = (v: EnvVar) => v.valueState === 'RESOLVED' || v.kind === 'K8s Secret data' || v.kind === 'Secret value'

const UNKNOWN_VALUE: Record<string, string> = {
  NOT_IN_JSON: 'AKeyless path not in JSON',
  NO_JSON: 'No AKeyless JSON for this side',
  UNKNOWN: 'Value is not in the chart',
}

function Value({ v, other, showSecrets }: { v: EnvVar; other?: EnvVar; showSecrets: boolean }) {
  const value = v.effectiveValue
  if (value == null) return <span className="val-state warn">{UNKNOWN_VALUE[v.valueState] ?? 'Unknown'}</span>
  if (isSecret(v) && !showSecrets) return <span className="val-masked" title="Tick “Reveal secret values”">••••••••</span>
  if (value === '') return <span className="faint">(empty)</span>
  const against = other?.effectiveValue != null && !(isSecret(other) && !showSecrets) ? other.effectiveValue : undefined
  return <span className="xl-value">{against !== undefined ? <DiffText value={value} against={against} /> : value}</span>
}

function Duplicates({ others, conflict, showSecrets }: { others: EnvVar[]; conflict: boolean; showSecrets: boolean }) {
  return (
    <div className={`env-dup ${conflict ? 'conflict' : ''}`}>
      {conflict ? '⚠ Also defined with a different value' : 'Also defined'}
      {others.map((o, i) => (
        <div key={i}>
          <span className="faint">{shortStep(steps(o.injection)[0]?.text ?? o.kind)}:</span> <Value v={o} showSecrets={showSecrets} />
        </div>
      ))}
    </div>
  )
}

function family(source: EnvSource) {
  return source === 'EMPTY' || source === 'TEMPLATE' ? 'Plain text' : SOURCE[source]?.[0] ?? source
}

function Result({ row, left, right }: { row: EnvRow; left: string; right: string }) {
  const verdict = verdictOf(row)
  const [cls, text] = {
    LEFT_ONLY: ['v-missing', `Missing in ${right}`],
    RIGHT_ONLY: ['v-missing', `Missing in ${left}`],
    VALUE_DIFFERS: ['v-diff', 'Different value'],
    UNVERIFIED: ['v-unverified', "Can't verify"],
    SAME: ['v-same', 'Same value'],
  }[verdict]
  const notes: string[] = []
  if (row.sourceChanged && row.left && row.right) notes.push(`${family(row.left.source)} → ${family(row.right.source)}`)
  if (row.match === 'SIMILAR_NAME') notes.push(`Similar name ${row.similarity}%`)
  return (
    <div className="stack-v" style={{ gap: 3 }}>
      <span className={`verdict ${cls}`}>{text}</span>
      {notes.map((n) => <span key={n} className="env-note">{n}</span>)}
      {row.duplicateConflict && <span className="env-note warn">Defined twice, values differ</span>}
    </div>
  )
}
