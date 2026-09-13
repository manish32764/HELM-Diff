import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { AnalysisRecord, ChartRecord } from '../api/types'
import { ChartPicker } from '../components/ChartPicker'
import { DiffExplorer } from '../components/DiffExplorer'
import { ExportMenu } from '../components/ExportMenu'
import { Button, Drawer, Modal, PageHeader, Spinner, StatusBadge, useToast } from '../components/ui'
import { chartLabel, envLabel, formatDate, TYPE_LABEL } from '../lib/labels'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'
import { PortfolioView } from '../views/PortfolioView'
import { VersionView } from '../views/VersionView'

const VERSION_KEYS = ['a', 'b', 'c', 'd']

export function AnalysisPage({ id }: { id: string }) {
  const analysis = useAsync(() => api.analysis(id), [id])
  const [record, setRecord] = useState<AnalysisRecord | undefined>()
  const [auditOpen, setAuditOpen] = useState(false)
  const [rerunOpen, setRerunOpen] = useState(false)
  const toast = useToast()

  useEffect(() => setRecord(analysis.data), [analysis.data])

  if (analysis.loading && !record) return <Spinner />
  if (analysis.error) return <div className="banner banner-error">{analysis.error}</div>
  if (!record) return null

  const remove = async () => {
    if (!window.confirm('Delete this analysis and its audit trail?')) return
    await api.deleteAnalysis(record.id)
    toast('Analysis deleted')
    navigate('/history')
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow={TYPE_LABEL[record.type]}
        title={record.title}
        subtitle={<>
          Performed {formatDate(record.createdAt)} · ID {record.id}
          {record.rerunOf && <> · repeat of <a href={`#/analysis/${record.rerunOf}`}>{record.rerunOf}</a></>}
        </>}
        actions={<>
          <Button onClick={() => setAuditOpen(true)}>Audit trail</Button>
          <Button onClick={() => setRerunOpen(true)}>↻ Re-run</Button>
          <ExportMenu url={(f) => api.exportUrl(record.id, f)} />
        </>}
      />
      <div className="chips" style={{ marginBottom: 18 }}>
        {record.charts.map((c) => (
          <span key={c.role + c.chartId} className="chip" title={`sha256 ${c.sha256 ?? ''}`}>
            {c.role}: <b>{chartLabel(c)}</b> <span className="faint">· {c.revision}</span>
          </span>
        ))}
      </div>

      {record.type === 'PAIRWISE' && record.pairwise && (
        <DiffExplorer diff={record.pairwise} record={record} onRecord={setRecord} allowPortfolioSearch />
      )}
      {(record.type === 'DIFF_COMPARE' || record.type === 'FOUR_CHART') && record.version && (
        <VersionView record={record} onRecord={setRecord} />
      )}
      {record.type === 'PORTFOLIO' && record.portfolio && <PortfolioView record={record} onRecord={setRecord} />}

      <Drawer open={auditOpen} onClose={() => setAuditOpen(false)} title="Audit trail" subtitle={`${record.title} · ${record.id}`}>
        <div className="section-title">Charts compared</div>
        <div className="table-wrap" style={{ border: '1px solid var(--line)', borderRadius: 12 }}>
          <table className="table">
            <thead><tr><th>Role</th><th>Chart</th><th>Revision</th><th>Uploaded</th><th>Fingerprint</th></tr></thead>
            <tbody>
              {record.charts.map((c) => (
                <tr key={c.role + c.chartId}>
                  <td className="small">{c.role}</td>
                  <td>{c.app} {c.version} {envLabel(c.environment)}</td>
                  <td>{c.revision}</td>
                  <td className="small">{formatDate(c.uploadedAt)}</td>
                  <td className="key">{c.sha256?.slice(0, 12)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="section-title">Events</div>
        <ul className="timeline">
          {[...record.audit].reverse().map((a, i) => (
            <li key={i}>
              <div><b>{a.action}</b> <span className="small faint">{formatDate(a.at)}</span></div>
              <div className="muted">{a.detail}</div>
            </li>
          ))}
        </ul>
        <div className="section-title">Review decisions ({Object.keys(record.reviews).length})</div>
        {Object.entries(record.reviews).map(([item, m]) => (
          <div key={item} className="row" style={{ padding: '6px 0', borderBottom: '1px solid var(--line-soft)' }}>
            <StatusBadge code={m.status} />
            <span className="key">{item}</span>
            <span className="small">{m.comment}</span>
            <span className="small faint" style={{ marginLeft: 'auto' }}>{formatDate(m.updatedAt)}</span>
          </div>
        ))}
        <div className="divider" />
        <Button variant="danger" size="sm" onClick={remove}>Delete analysis</Button>
      </Drawer>

      {rerunOpen && <RerunDialog record={record} onClose={() => setRerunOpen(false)} />}
    </div>
  )
}

/** Repeats an analysis, optionally with newer chart revisions (e.g. NON-PROD Initial → Revised). */
function RerunDialog({ record, onClose }: { record: AnalysisRecord; onClose: () => void }) {
  const charts = useAsync(() => api.charts(true), [])
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  const slots: { key: string; role: string; chartId: string }[] =
    record.type === 'PAIRWISE' ? record.charts.map((c, i) => ({ key: i === 0 ? 'leftChartId' : 'rightChartId', role: c.role, chartId: c.chartId }))
      : record.type === 'PORTFOLIO' ? []
        : record.charts.map((c, i) => ({ key: VERSION_KEYS[i], role: c.role, chartId: c.chartId }))

  useEffect(() => {
    if (!charts.data) return
    const latest = (id: string): string => {
      const next = charts.data!.find((c) => c.supersedes === id)
      return next ? latest(next.id) : id
    }
    setOverrides(Object.fromEntries(slots.map((s) => [s.key, latest(s.chartId)])))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [charts.data])

  const run = async () => {
    setBusy(true)
    try {
      const rec = await api.rerun(record.id, overrides)
      toast('Analysis repeated')
      onClose()
      navigate(`/analysis/${rec.id}`)
    } catch (e) {
      toast((e as Error).message, 'error')
      setBusy(false)
    }
  }

  const available: ChartRecord[] = charts.data ?? []
  const newer = slots.filter((s) => overrides[s.key] && overrides[s.key] !== s.chartId).length

  return (
    <Modal open onClose={onClose} title="Repeat analysis" width={640}
      subtitle="Charts evolve through revisions. The latest revision of each chart is pre-selected."
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy} onClick={run}>{busy ? 'Running…' : 'Run again'}</Button></>}>
      {charts.loading ? <Spinner /> : (
        <div className="stack-v">
          {slots.length === 0 && <div className="muted">The portfolio analysis will be repeated with the same portfolio and expectations.</div>}
          {newer > 0 && <div className="banner banner-info">{newer} newer revision(s) selected.</div>}
          {slots.map((s) => (
            <ChartPicker key={s.key} label={s.role} charts={available} value={overrides[s.key]}
              onChange={(id) => setOverrides((o) => ({ ...o, [s.key]: id }))}
              hint={overrides[s.key] !== s.chartId ? 'Newer than the chart used originally' : 'Same chart as the original analysis'} />
          ))}
        </div>
      )}
    </Modal>
  )
}
