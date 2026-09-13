import { useMemo, useState } from 'react'
import { api } from '../api/client'
import type { AnalysisRecord, AppResult, Expectation } from '../api/types'
import { SourceBadge } from '../components/items'
import { ReviewControl } from '../components/ReviewControl'
import {
  Badge, Button, Card, Drawer, EmptyState, SearchInput, Segmented, StackBar, StatTile, StatusBadge, useToast,
} from '../components/ui'
import { CATEGORY_LABEL, statusLabel } from '../lib/labels'
import { navigate } from '../lib/router'

type AppFilter = 'ALL' | 'COMPLIANT' | 'REQUIRES_CHANGES' | 'REQUIRES_REVIEW' | 'UNEXPECTED' | 'UNUSUAL'

export function PortfolioView({ record, onRecord }: { record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void }) {
  const p = record.portfolio!
  const s = p.summary
  const [filter, setFilter] = useState<AppFilter>('ALL')
  const [query, setQuery] = useState('')
  const [expectationFilter, setExpectationFilter] = useState<{ id: string; status: string } | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const expectations = useMemo(() => new Map(p.expectations.map((x) => [x.id, x])), [p.expectations])

  const apps = p.apps.filter((a) => {
    if (filter === 'UNEXPECTED' && a.unexpectedChanges.length === 0) return false
    if (filter === 'UNUSUAL' && a.unusualChanges.length === 0) return false
    if (['COMPLIANT', 'REQUIRES_CHANGES', 'REQUIRES_REVIEW'].includes(filter) && a.status !== filter) return false
    if (expectationFilter && a.results.find((r) => r.expectationId === expectationFilter.id)?.status !== expectationFilter.status) return false
    return !query || a.app.toLowerCase().includes(query.toLowerCase())
  })
  const app = p.apps.find((a) => a.app === selected)

  return (
    <>
      <div className="tiles">
        <StatTile label="Charts analysed" value={s.chartsAnalyzed} hint={`${s.apps} applications`} />
        <StatTile label="Compliant" value={s.compliant} tone="green" active={filter === 'COMPLIANT'} onClick={() => setFilter('COMPLIANT')} />
        <StatTile label="Require changes" value={s.requireChanges} tone="red" active={filter === 'REQUIRES_CHANGES'} onClick={() => setFilter('REQUIRES_CHANGES')} />
        <StatTile label="Require review" value={s.requireReview} tone="orange" active={filter === 'REQUIRES_REVIEW'} onClick={() => setFilter('REQUIRES_REVIEW')} />
        <StatTile label="New / unexpected PROD changes" value={s.withUnexpectedChanges} tone="blue" active={filter === 'UNEXPECTED'} onClick={() => setFilter('UNEXPECTED')} />
        <StatTile label="Unusual configuration" value={s.withUnusualConfiguration} tone="purple" active={filter === 'UNUSUAL'} onClick={() => setFilter('UNUSUAL')} />
      </div>

      <Card flush title="PROD expectations across the portfolio"
        subtitle={p.differenceSetTitle ? `Derived from ${p.differenceSetTitle}. Click a count to filter the applications.` : 'Click a count to filter the applications.'}
        actions={<div className="legend">
          <span><span className="tile-mark mark-green" />Compliant</span>
          <span><span className="tile-mark mark-red" />Requires change</span>
          <span><span className="tile-mark mark-orange" />Review</span>
          <span><span className="tile-mark mark-gray" />Not applicable</span>
        </div>}>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Expectation</th><th style={{ width: '22%' }}>Distribution</th><th className="num">Compliant</th><th className="num">Requires change</th><th className="num">Review</th><th className="num">N/A</th></tr></thead>
            <tbody>
              {p.expectationSummaries.map((es) => {
                const x = expectations.get(es.expectationId)
                const count = (status: string, n: number) => (
                  <td className="num">
                    {n > 0 ? <a href="#" onClick={(e) => { e.preventDefault(); setFilter('ALL'); setExpectationFilter({ id: es.expectationId, status }) }}>{n}</a> : <span className="faint">0</span>}
                  </td>
                )
                return (
                  <tr key={es.expectationId}>
                    <td><div className="subject">{es.title}</div><div className="small faint">{x ? CATEGORY_LABEL[x.category] : ''}{x?.applicability === 'IF_PRESENT' ? ' · only where present' : ''}</div></td>
                    <td style={{ verticalAlign: 'middle' }}>
                      <StackBar segments={[
                        { value: es.compliant, tone: 'green', label: 'Compliant' },
                        { value: es.requiresChange, tone: 'red', label: 'Requires change' },
                        { value: es.review, tone: 'orange', label: 'Review' },
                        { value: es.notApplicable, tone: 'gray', label: 'Not applicable' },
                      ]} />
                    </td>
                    {count('COMPLIANT', es.compliant)}
                    {count('REQUIRES_CHANGE', es.requiresChange)}
                    {count('REVIEW', es.review)}
                    {count('NOT_APPLICABLE', es.notApplicable)}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <Card flush title="Applications">
        <div className="toolbar">
          <Segmented value={filter} onChange={(f) => { setFilter(f); setExpectationFilter(null) }} options={[
            { value: 'ALL', label: 'All', count: p.apps.length },
            { value: 'REQUIRES_CHANGES', label: 'Require changes', count: s.requireChanges },
            { value: 'REQUIRES_REVIEW', label: 'Review', count: s.requireReview },
            { value: 'COMPLIANT', label: 'Compliant', count: s.compliant },
          ]} />
          {expectationFilter && (
            <span className="chip">
              {expectations.get(expectationFilter.id)?.title}: <StatusBadge code={expectationFilter.status} />
              <button className="icon-btn" style={{ width: 20, height: 20, fontSize: 11 }} onClick={() => setExpectationFilter(null)}>✕</button>
            </span>
          )}
          <div className="spacer" />
          <SearchInput value={query} onChange={setQuery} placeholder="Search applications" />
        </div>
        {apps.length === 0 ? <EmptyState icon="✓" title="No applications match" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Application</th><th>Status</th><th>Checked</th><th className="num">Compliant</th><th className="num">Requires change</th><th className="num">Review</th><th>PROD differences</th><th>Decision</th></tr></thead>
              <tbody>
                {apps.map((a) => (
                  <tr key={a.app} className="clickable" onClick={() => setSelected(a.app)}>
                    <td className="subject">{a.app}</td>
                    <td><StatusBadge code={a.status} /></td>
                    <td className="small">{a.evaluatedOn === 'PROD' ? 'PROD validation' : 'NON-PROD preparation'}</td>
                    <td className="num">{a.compliant}</td>
                    <td className="num">{a.requiresChange || <span className="faint">0</span>}</td>
                    <td className="num">{a.review || <span className="faint">0</span>}</td>
                    <td>
                      <div className="flags">
                        {a.unexpectedChanges.length > 0 && <Badge tone="blue">{a.unexpectedChanges.length} unexpected</Badge>}
                        {a.unusualChanges.length > 0 && <Badge tone="purple">{a.unusualChanges.length} unusual</Badge>}
                        {a.otherProdDifferences > 0 && <Badge tone="gray">{a.otherProdDifferences} other</Badge>}
                      </div>
                    </td>
                    <td>{record.reviews[a.app] && <StatusBadge code={record.reviews[a.app].status} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card flush title="Similar PROD changes across charts" subtitle="NON-PROD → PROD changes that occur in more than one application">
        {p.commonChanges.length === 0 ? <EmptyState icon="◌" title="No shared PROD changes" text="Charts need both a NON-PROD and a PROD version." /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Change</th><th>Area</th><th style={{ width: '28%' }}>Charts</th></tr></thead>
              <tbody>
                {p.commonChanges.slice(0, 40).map((c) => (
                  <tr key={c.signature}>
                    <td className="subject">{c.label}</td>
                    <td className="small">{CATEGORY_LABEL[c.category]}</td>
                    <td title={c.apps.join(', ')}>
                      <div className="row" style={{ flexWrap: 'nowrap' }}>
                        <div className="bar-track" style={{ flex: 1 }}><div className="bar-fill" style={{ width: `${(c.count / Math.max(1, s.apps)) * 100}%` }} /></div>
                        <span className="small num" style={{ width: 60 }}>{c.count} / {s.apps}</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Drawer open={!!app} onClose={() => setSelected(null)} title={app?.app}
        subtitle={app ? (app.evaluatedOn === 'PROD' ? 'Validated against the PROD chart' : 'Assessed on the NON-PROD chart (no PROD chart yet)') : undefined}>
        {app && <AppDetail app={app} expectations={expectations} record={record} onRecord={onRecord} />}
      </Drawer>
    </>
  )
}

function AppDetail({ app, expectations, record, onRecord }: {
  app: AppResult; expectations: Map<string, Expectation>; record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void
}) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const order: Record<string, number> = { REQUIRES_CHANGE: 0, REVIEW: 1, COMPLIANT: 2, NOT_APPLICABLE: 3 }
  const results = [...app.results].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9))

  const compare = async () => {
    setBusy(true)
    try {
      const rec = await api.pairwise({ leftChartId: app.nonProdChartId!, rightChartId: app.prodChartId! })
      navigate(`/analysis/${rec.id}`)
    } catch (e) {
      toast((e as Error).message, 'error')
      setBusy(false)
    }
  }

  return (
    <div className="stack-v">
      <div className="row">
        <StatusBadge code={app.status} />
        <span className="small muted">{app.compliant} compliant · {app.requiresChange} requires change · {app.review} review · {app.notApplicable} not applicable</span>
      </div>
      {app.nonProdChartId && app.prodChartId && (
        <div><Button size="sm" disabled={busy} onClick={compare}>⇆ Compare NON-PROD ↔ PROD</Button></div>
      )}

      <div className="section-title">Expectations</div>
      <div className="table-wrap" style={{ border: '1px solid var(--line)', borderRadius: 12 }}>
        <table className="table">
          <tbody>
            {results.map((r) => (
              <tr key={r.expectationId}>
                <td style={{ width: '40%' }}>
                  <div className="subject">{expectations.get(r.expectationId)?.title ?? r.expectationId}</div>
                  <div className="small faint">{statusLabel(r.match)}</div>
                </td>
                <td><StatusBadge code={r.status} /></td>
                <td className="small">
                  {r.detail}
                  {r.found && <div className="mt" style={{ marginTop: 4 }}><SourceBadge item={r.found} /></div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {app.unexpectedChanges.length > 0 && (
        <>
          <div className="section-title">New / unexpected PROD changes</div>
          <ChangeList entries={app.unexpectedChanges} />
        </>
      )}
      {app.unusualChanges.length > 0 && (
        <>
          <div className="section-title">Unusual — not seen in any other chart</div>
          <ChangeList entries={app.unusualChanges} />
        </>
      )}

      <div className="section-title">Review decision</div>
      <ReviewControl record={record} itemId={app.app} onSaved={onRecord} />
    </div>
  )
}

function ChangeList({ entries }: { entries: AppResult['unexpectedChanges'] }) {
  return (
    <ul className="notes" style={{ listStyle: 'none', padding: 0 }}>
      {entries.map((e) => (
        <li key={e.id} className="row" style={{ gap: 8 }}>
          <StatusBadge code={e.status} />
          {e.security && <Badge tone="red">🔒</Badge>}
          <span style={{ color: 'var(--text)' }}>{e.summary}</span>
        </li>
      ))}
    </ul>
  )
}
