import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import type { PairSource } from '../api/client'
import type { AnalysisSummary, ChartRecord, Environment } from '../api/types'
import { ChartPicker } from '../components/ChartPicker'
import { Button, Card, EmptyState, Field, PageHeader, Segmented, Spinner, useToast } from '../components/ui'
import { chartLabel, formatDate, TYPE_LABEL } from '../lib/labels'
import { navigate } from '../lib/router'
import type { Route } from '../lib/router'
import { useAsync } from '../lib/useAsync'

function useLibrary() {
  return useAsync(async () => {
    const [charts, analyses] = await Promise.all([api.charts(), api.analyses()])
    return { charts, analyses }
  }, [])
}

function useRun() {
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const run = async (fn: () => Promise<{ id: string }>) => {
    setBusy(true)
    try {
      const rec = await fn()
      navigate(`/analysis/${rec.id}`)
    } catch (e) {
      toast((e as Error).message, 'error')
      setBusy(false)
    }
  }
  return { busy, run }
}

function NoCharts() {
  return (
    <Card>
      <EmptyState icon="⎈" title="Upload charts first" text="Upload Helm charts or load the sample data from the home page."
        action={<div className="row" style={{ justifyContent: 'center' }}>
          <Button variant="primary" onClick={() => navigate('/charts')}>Upload charts</Button>
          <Button onClick={() => navigate('/')}>Home</Button>
        </div>} />
    </Card>
  )
}

function Recent({ analyses, type, title }: { analyses: AnalysisSummary[]; type: AnalysisSummary['type']; title: string }) {
  const rows = analyses.filter((a) => a.type === type).slice(0, 8)
  if (rows.length === 0) return null
  return (
    <Card title={title} flush>
      <table className="table">
        <tbody>
          {rows.map((a) => (
            <tr key={a.id} className="clickable" onClick={() => navigate(`/analysis/${a.id}`)}>
              <td className="subject">{a.title}</td>
              <td className="small muted">{a.charts.map((c) => `${c.version} ${c.environment === 'PROD' ? 'PROD' : 'NP'}`).join(' · ')}</td>
              <td className="small faint nowrap">{formatDate(a.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}

// ───────────── Mode 1 ─────────────

export function PairwisePage({ route }: { route: Route }) {
  const lib = useLibrary()
  const [left, setLeft] = useState(route.query.get('left') ?? '')
  const [right, setRight] = useState(route.query.get('right') ?? '')
  const [title, setTitle] = useState('')
  const { busy, run } = useRun()

  return (
    <div className="page">
      <PageHeader eyebrow="Mode 1" title="Two-chart diff"
        subtitle="Compare any two charts by configuration meaning. Blocks are matched even when they sit at different locations. The result is kept as a reusable Difference Set." />
      {lib.loading ? <Spinner /> : lib.data!.charts.length < 2 ? <NoCharts /> : (
        <>
          <Card title="Select charts">
            <div className="pair">
              <ChartPicker label="Chart A (source, e.g. NON-PROD)" charts={lib.data!.charts} value={left} onChange={setLeft} env="NON_PROD" />
              <button className="icon-btn pair-sep" title="Swap" onClick={() => { setLeft(right); setRight(left) }}>⇆</button>
              <ChartPicker label="Chart B (target, e.g. PROD)" charts={lib.data!.charts} value={right} onChange={setRight} env="PROD" />
            </div>
            <div className="row mt" style={{ alignItems: 'flex-end' }}>
              <div style={{ flex: 1, minWidth: 240 }}>
                <Field label="Title (optional)"><input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. payments-api 3.0.4 PROD Difference Set" /></Field>
              </div>
              <Button variant="primary" size="lg" disabled={!left || !right || left === right || busy}
                onClick={() => run(() => api.pairwise({ leftChartId: left, rightChartId: right, title: title || undefined }))}>
                {busy ? 'Comparing…' : 'Compare'}
              </Button>
            </div>
          </Card>
          <Recent analyses={lib.data!.analyses} type="PAIRWISE" title="Difference Sets" />
        </>
      )}
    </div>
  )
}

// ───────────── Mode 2 ─────────────

function PairSelector({ title, subtitle, charts, sets, value, onChange }: {
  title: string; subtitle: string; charts: ChartRecord[]; sets: AnalysisSummary[]; value: PairSource; onChange: (v: PairSource) => void
}) {
  const [mode, setMode] = useState<'SET' | 'CHARTS'>(value.differenceSetId ? 'SET' : 'CHARTS')
  return (
    <Card title={title} subtitle={subtitle}>
      <div className="stack-v">
        <Segmented value={mode} onChange={(m) => { setMode(m); onChange({}) }} options={[
          { value: 'CHARTS', label: 'Pick two charts' },
          { value: 'SET', label: 'Use a saved Difference Set', count: sets.length },
        ]} />
        {mode === 'SET' ? (
          <Field label="Difference Set">
            <select className="select" value={value.differenceSetId ?? ''} onChange={(e) => onChange({ differenceSetId: e.target.value })}>
              <option value="">Select…</option>
              {sets.map((s) => <option key={s.id} value={s.id}>{s.title} · {formatDate(s.createdAt)}</option>)}
            </select>
          </Field>
        ) : (
          <div className="grid grid-2">
            <ChartPicker label="NON-PROD" env="NON_PROD" charts={charts} value={value.leftChartId}
              onChange={(id) => onChange({ ...value, differenceSetId: undefined, leftChartId: id })} />
            <ChartPicker label="PROD" env="PROD" charts={charts} value={value.rightChartId}
              onChange={(id) => onChange({ ...value, differenceSetId: undefined, rightChartId: id })} />
          </div>
        )}
      </div>
    </Card>
  )
}

const complete = (p: PairSource) => !!p.differenceSetId || (!!p.leftChartId && !!p.rightChartId)

export function DiffComparePage() {
  const lib = useLibrary()
  const [historical, setHistorical] = useState<PairSource>({})
  const [current, setCurrent] = useState<PairSource>({})
  const { busy, run } = useRun()
  const sets = (lib.data?.analyses ?? []).filter((a) => a.type === 'PAIRWISE')

  return (
    <div className="page">
      <PageHeader eyebrow="Mode 2" title="Two-diff comparison"
        subtitle="Compare the PROD changes of two versions: which were carried forward, which are missing, which changed implementation, and which are new." />
      {lib.loading ? <Spinner /> : lib.data!.charts.length < 4 && sets.length === 0 ? <NoCharts /> : (
        <>
          <div className="grid grid-2">
            <PairSelector title="DIFF A — historical" subtitle="e.g. 3.0.4 NON-PROD → 3.0.4 PROD" charts={lib.data!.charts} sets={sets} value={historical} onChange={setHistorical} />
            <PairSelector title="DIFF B — new version" subtitle="e.g. 3.1.3 NON-PROD → 3.1.3 PROD" charts={lib.data!.charts} sets={sets} value={current} onChange={setCurrent} />
          </div>
          <div className="row" style={{ justifyContent: 'flex-end', marginBottom: 18 }}>
            <Button variant="primary" size="lg" disabled={!complete(historical) || !complete(current) || busy}
              onClick={() => run(() => api.diffCompare({ historical, current }))}>{busy ? 'Comparing…' : 'Compare differences'}</Button>
          </div>
          <Recent analyses={lib.data!.analyses} type="DIFF_COMPARE" title={`Recent ${TYPE_LABEL.DIFF_COMPARE.toLowerCase()}s`} />
        </>
      )}
    </div>
  )
}

// ───────────── Mode 3 ─────────────

export function FourChartPage() {
  const lib = useLibrary()
  const [ids, setIds] = useState<Record<'a' | 'b' | 'c' | 'd', string>>({ a: '', b: '', c: '', d: '' })
  const [autoApp, setAutoApp] = useState('')
  const [autoOld, setAutoOld] = useState('')
  const [autoNew, setAutoNew] = useState('')
  const { busy, run } = useRun()
  const charts = lib.data?.charts ?? []

  const apps = useMemo(() => [...new Set(charts.map((c) => c.app))].sort(), [charts])
  const versions = useMemo(() => [...new Set(charts.filter((c) => c.app === autoApp).map((c) => c.version))]
    .sort((x, y) => x.localeCompare(y, undefined, { numeric: true })), [charts, autoApp])

  useEffect(() => {
    if (!autoApp || !autoOld || !autoNew) return
    const latest = (version: string, env: Environment) => charts
      .filter((c) => c.app === autoApp && c.version === version && c.environment === env)
      .sort((x, y) => y.uploadedAt.localeCompare(x.uploadedAt))[0]?.id ?? ''
    setIds({ a: latest(autoOld, 'NON_PROD'), b: latest(autoOld, 'PROD'), c: latest(autoNew, 'NON_PROD'), d: latest(autoNew, 'PROD') })
  }, [autoApp, autoOld, autoNew, charts])

  const set = (k: keyof typeof ids) => (id: string) => setIds((s) => ({ ...s, [k]: id }))
  const slot = (k: keyof typeof ids, label: string, env: Environment, hint?: ReactNode) => (
    <ChartPicker label={label} env={env} charts={charts} value={ids[k]} onChange={set(k)} optional={k === 'd'} hint={hint as string} />
  )

  return (
    <div className="page">
      <PageHeader eyebrow="Mode 3" title="Four-chart analysis"
        subtitle="Historical PROD behaviour + new-version changes + current PROD implementation → assessment. Leave the new PROD chart empty to prepare it instead of validating it." />
      {lib.loading ? <Spinner /> : charts.length < 3 ? <NoCharts /> : (
        <>
          <Card title="Quick select" subtitle="Pick an application and two versions — the latest revision of each chart is selected for you">
            <div className="grid grid-3">
              <Field label="Application">
                <select className="select" value={autoApp} onChange={(e) => { setAutoApp(e.target.value); setAutoOld(''); setAutoNew('') }}>
                  <option value="">Select…</option>
                  {apps.map((a) => <option key={a}>{a}</option>)}
                </select>
              </Field>
              <Field label="Historical version">
                <select className="select" value={autoOld} onChange={(e) => setAutoOld(e.target.value)} disabled={!autoApp}>
                  <option value="">Select…</option>
                  {versions.map((v) => <option key={v}>{v}</option>)}
                </select>
              </Field>
              <Field label="New version">
                <select className="select" value={autoNew} onChange={(e) => setAutoNew(e.target.value)} disabled={!autoApp}>
                  <option value="">Select…</option>
                  {versions.map((v) => <option key={v}>{v}</option>)}
                </select>
              </Field>
            </div>
          </Card>

          <Card title="Charts">
            <div className="quad">
              <span />
              <span className="quad-head">NON-PROD</span>
              <span className="quad-head">PROD</span>
              <span className="subject">Historical</span>
              {slot('a', 'A · historical NON-PROD', 'NON_PROD')}
              {slot('b', 'B · historical PROD', 'PROD')}
              <span className="subject">New version</span>
              {slot('c', 'C · new NON-PROD', 'NON_PROD')}
              {slot('d', 'D · new PROD (optional)', 'PROD', 'Empty = PROD preparation')}
            </div>
            <div className="row mt" style={{ justifyContent: 'space-between' }}>
              <span className="muted small">
                {ids.d ? 'Validation: does the new PROD chart contain the expected PROD characteristics?'
                  : 'Preparation: which historical PROD characteristics should be considered for the new PROD chart?'}
              </span>
              <Button variant="primary" size="lg" disabled={!ids.a || !ids.b || !ids.c || busy}
                onClick={() => run(() => api.fourChart({ a: ids.a, b: ids.b, c: ids.c, d: ids.d || undefined }))}>
                {busy ? 'Analysing…' : ids.d ? 'Validate new PROD' : 'Assess for PROD preparation'}
              </Button>
            </div>
          </Card>
          <Recent analyses={lib.data!.analyses} type="FOUR_CHART" title="Recent four-chart analyses" />
        </>
      )}
    </div>
  )
}

// ───────────── History ─────────────

export function HistoryPage() {
  const analyses = useAsync(() => api.analyses(), [])
  const [type, setType] = useState<'ALL' | AnalysisSummary['type']>('ALL')
  const [query, setQuery] = useState('')
  const rows = (analyses.data ?? []).filter((a) => (type === 'ALL' || a.type === type)
    && (!query || `${a.title} ${a.charts.map(chartLabel).join(' ')}`.toLowerCase().includes(query.toLowerCase())))
  const count = (t: AnalysisSummary['type']) => (analyses.data ?? []).filter((a) => a.type === t).length

  return (
    <div className="page">
      <PageHeader eyebrow="Audit" title="Analysis history"
        subtitle="Which charts were compared, which versions and revisions, when, what was found and which items were reviewed." />
      <Card flush>
        <div className="toolbar" style={{ paddingTop: 4 }}>
          <Segmented value={type} onChange={setType} options={[
            { value: 'ALL', label: 'All', count: analyses.data?.length },
            { value: 'PAIRWISE', label: 'Two-chart', count: count('PAIRWISE') },
            { value: 'DIFF_COMPARE', label: 'Two-diff', count: count('DIFF_COMPARE') },
            { value: 'FOUR_CHART', label: 'Four-chart', count: count('FOUR_CHART') },
            { value: 'PORTFOLIO', label: 'Portfolio', count: count('PORTFOLIO') },
          ]} />
          <div className="spacer" />
          <input className="input" style={{ width: 240, borderRadius: 999 }} placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {analyses.loading ? <Spinner /> : rows.length === 0 ? <EmptyState icon="◷" title="No analyses" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Analysis</th><th>Charts</th><th>Findings</th><th className="num">Decisions</th><th>Performed</th></tr></thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.id} className="clickable" onClick={() => navigate(`/analysis/${a.id}`)}>
                    <td>
                      <div className="subject">{a.title}</div>
                      <div className="small faint">{TYPE_LABEL[a.type]} · {a.id}{a.rerunOf ? ` · repeat of ${a.rerunOf}` : ''}</div>
                    </td>
                    <td className="small">{a.charts.map((c) => <div key={c.role + c.chartId}>{chartLabel(c)} <span className="faint">· {c.revision}</span></div>)}</td>
                    <td className="small muted">{Object.entries(a.headline).slice(0, 4).map(([k, v]) => `${k.replace(/([A-Z])/g, ' $1').toLowerCase()} ${v}`).join(' · ')}</td>
                    <td className="num">{a.reviewMarks}</td>
                    <td className="small faint nowrap">{formatDate(a.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}
