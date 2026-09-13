import { useMemo, useState } from 'react'
import { api } from '../api/client'
import type { Category, ChartRecord } from '../api/types'
import { SourceBadge } from '../components/items'
import { useSourceViewer } from '../components/SourceViewer'
import {
  Badge, Button, Card, Drawer, EmptyState, PageHeader, SearchInput, Segmented, Spinner, Toggle, useToast,
} from '../components/ui'
import { UploadChartDialog } from '../components/UploadChartDialog'
import type { UploadDefaults } from '../components/UploadChartDialog'
import { CATEGORY_ICON, CATEGORY_LABEL, CATEGORY_ORDER, chartLabel, envLabel, envTone, formatDate } from '../lib/labels'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

export function ChartsPage() {
  const [includePortfolio, setIncludePortfolio] = useState(false)
  const charts = useAsync(() => api.charts(includePortfolio), [includePortfolio])
  const [env, setEnv] = useState<'ALL' | 'NON_PROD' | 'PROD'>('ALL')
  const [query, setQuery] = useState('')
  const [upload, setUpload] = useState<UploadDefaults | null>(null)
  const [selected, setSelected] = useState<string | null>(null)

  const list = (charts.data ?? []).filter((c) => (env === 'ALL' || c.environment === env)
    && (!query || `${c.app} ${c.version} ${c.revision} ${c.originalName ?? ''}`.toLowerCase().includes(query.toLowerCase())))
  const all = charts.data ?? []

  return (
    <div className="page">
      <PageHeader eyebrow="Library" title="Helm charts"
        subtitle="Every upload is an immutable, fingerprinted snapshot. Upload a new revision whenever a chart evolves."
        actions={<Button variant="primary" onClick={() => setUpload({})}>＋ Upload chart</Button>} />

      <Card flush>
        <div className="toolbar" style={{ paddingTop: 4 }}>
          <Segmented value={env} onChange={setEnv} options={[
            { value: 'ALL', label: 'All' }, { value: 'NON_PROD', label: 'NON-PROD' }, { value: 'PROD', label: 'PROD' },
          ]} />
          <Toggle checked={includePortfolio} onChange={setIncludePortfolio} label="Include portfolio charts" />
          <div className="spacer" />
          <SearchInput value={query} onChange={setQuery} placeholder="Search charts" />
        </div>
        {charts.loading ? <Spinner /> : list.length === 0 ? (
          <EmptyState icon="⎈" title="No charts yet" text="Upload a values file, a chart folder, a .zip or a .tgz."
            action={<Button variant="primary" onClick={() => setUpload({})}>Upload chart</Button>} />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Application</th><th>Version</th><th>Environment</th><th>Revision</th><th className="num">Items</th><th className="num">Files</th><th>Uploaded</th><th /></tr></thead>
              <tbody>
                {list.map((c) => (
                  <tr key={c.id} className="clickable" onClick={() => setSelected(c.id)}>
                    <td className="subject">{c.app}</td>
                    <td>{c.version}</td>
                    <td><Badge tone={envTone(c.environment)}>{envLabel(c.environment)}</Badge></td>
                    <td>{c.revision}{c.supersedes && <span className="small faint"> · revision</span>}</td>
                    <td className="num">{c.itemCount}</td>
                    <td className="num">{c.files.length}</td>
                    <td className="small faint nowrap">{formatDate(c.uploadedAt)}</td>
                    <td>{c.warnings.length > 0 && <Badge tone="orange" title={c.warnings.join('\n')}>⚠ {c.warnings.length}</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <UploadChartDialog open={upload !== null} defaults={upload ?? undefined} charts={all}
        onClose={() => setUpload(null)} onUploaded={(c) => { charts.reload(); setSelected(c.id) }} />

      <Drawer open={!!selected} onClose={() => setSelected(null)} title="Chart details">
        {selected && (
          <ChartDetail id={selected} charts={all}
            onDeleted={() => { setSelected(null); charts.reload() }}
            onNewRevision={(c) => setUpload({ app: c.app, version: c.version, environment: c.environment, supersedes: c.id })} />
        )}
      </Drawer>
    </div>
  )
}

function ChartDetail({ id, charts, onDeleted, onNewRevision }: {
  id: string; charts: ChartRecord[]; onDeleted: () => void; onNewRevision: (c: ChartRecord) => void
}) {
  const data = useAsync(() => api.chart(id), [id])
  const [query, setQuery] = useState('')
  const openSource = useSourceViewer()
  const toast = useToast()

  const groups = useMemo(() => {
    const items = (data.data?.items ?? []).filter((i) => !query
      || `${i.subject} ${i.key} ${i.display}`.toLowerCase().includes(query.toLowerCase()))
    return CATEGORY_ORDER.map((c: Category) => ({ c, items: items.filter((i) => i.category === c) })).filter((g) => g.items.length)
  }, [data.data, query])

  if (data.loading) return <Spinner />
  if (data.error) return <div className="banner banner-error">{data.error}</div>
  const chart = data.data!.chart
  const previous = charts.find((c) => c.id === chart.supersedes)
  const next = charts.filter((c) => c.supersedes === chart.id)

  const remove = async () => {
    if (!window.confirm(`Delete ${chartLabel(chart)} (${chart.revision})? Existing analyses keep their audit record.`)) return
    try {
      await api.deleteChart(chart.id)
      toast('Chart deleted')
      onDeleted()
    } catch (e) {
      toast((e as Error).message, 'error')
    }
  }

  return (
    <div className="stack-v">
      <div>
        <div style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em' }}>{chart.app} {chart.version}</div>
        <div className="row mt" style={{ marginTop: 6 }}>
          <Badge tone={envTone(chart.environment)}>{envLabel(chart.environment)}</Badge>
          <Badge>{chart.revision}</Badge>
          <span className="small faint">Uploaded {formatDate(chart.uploadedAt)}</span>
        </div>
      </div>
      <div className="row">
        <Button variant="primary" size="sm" onClick={() => navigate(`/compare?left=${chart.id}`)}>⇆ Compare with…</Button>
        <Button size="sm" onClick={() => onNewRevision(chart)}>Upload new revision</Button>
        <Button size="sm" onClick={() => openSource(chart.id)}>View source</Button>
        <Button size="sm" variant="danger" onClick={remove}>Delete</Button>
      </div>

      <dl className="kv" style={{ gridTemplateColumns: '140px 1fr' }}>
        <dt>Chart.yaml</dt><dd>{chart.chartName ?? '—'} {chart.chartVersion ?? ''} {chart.appVersion ? `(app ${chart.appVersion})` : ''}</dd>
        <dt>Source</dt><dd>{chart.originalName ?? '—'}</dd>
        <dt>Fingerprint</dt><dd>{chart.sha256.slice(0, 16)}…</dd>
        {previous && <><dt>Supersedes</dt><dd>{chartLabel(previous)} · {previous.revision}</dd></>}
        {next.length > 0 && <><dt>Superseded by</dt><dd>{next.map((n) => `${n.revision} (${formatDate(n.uploadedAt)})`).join(', ')}</dd></>}
        {chart.notes && <><dt>Notes</dt><dd>{chart.notes}</dd></>}
      </dl>

      {chart.warnings.length > 0 && (
        <div className="banner banner-warn"><div>{chart.warnings.map((w) => <div key={w}>⚠ {w}</div>)}</div></div>
      )}

      <div className="section-title">Files</div>
      <div className="chips">
        {chart.files.map((f) => <button key={f} className="chip" style={{ border: 0, cursor: 'pointer' }} onClick={() => openSource(chart.id, f)}>{f}</button>)}
      </div>

      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="section-title" style={{ margin: '20px 0 8px' }}>Recognised configuration · {data.data!.items.length}</div>
        <SearchInput value={query} onChange={setQuery} placeholder="Filter" />
      </div>
      {groups.map(({ c, items }) => (
        <div key={c}>
          <div className="small" style={{ fontWeight: 600, margin: '8px 0 4px' }}>{CATEGORY_ICON[c]} {CATEGORY_LABEL[c]} · {items.length}</div>
          <div style={{ border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden' }}>
            <table className="table">
              <tbody>
                {items.map((i) => (
                  <tr key={i.key} className="clickable" onClick={() => i.locations[0] && openSource(chart.id, i.locations[0].file, i.locations[0].line)}>
                    <td style={{ width: '38%' }}><div className="subject">{i.subject}</div><div className="key">{i.key}</div></td>
                    <td style={{ width: 120 }}><SourceBadge item={i} /></td>
                    <td className="value small">{i.display}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </div>
  )
}
