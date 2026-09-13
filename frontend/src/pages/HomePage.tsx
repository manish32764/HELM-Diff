import { useState } from 'react'
import { api } from '../api/client'
import { Button, Card, Spinner, useToast } from '../components/ui'
import { chartLabel, formatDate, TYPE_ICON, TYPE_LABEL } from '../lib/labels'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

const MODES = [
  {
    to: '/compare', icon: '⇆', bg: '#e8f1fd', color: '#0a5dc2', title: 'Two-chart diff', formula: 'A ↔ B',
    text: 'What changed between NON-PROD and PROD? A meaningful, configuration-level delta — saved as a Difference Set.',
  },
  {
    to: '/diff-compare', icon: '⧉', bg: '#e3f4f5', color: '#0a7079', title: 'Two-diff comparison', formula: '(A ↔ B) ↔ (C ↔ D)',
    text: 'Were historical PROD changes carried forward? Carried forward, missing, changed, new and no-longer-applicable.',
  },
  {
    to: '/four-chart', icon: '⊞', bg: '#f4ebfa', color: '#7b3fa0', title: 'Four-chart analysis', formula: 'A + B + C + D',
    text: 'Prepare or validate the new PROD chart: evolution matrix, NON-PROD assessment and PROD validation verdict.',
  },
  {
    to: '/portfolio', icon: '▦', bg: '#e6f6ec', color: '#1b7f45', title: 'Portfolio analysis', formula: 'Expectations → 313 charts',
    text: 'Which charts require attention? Apply historical PROD expectations across every application.',
  },
]

export function HomePage() {
  const data = useAsync(async () => {
    const [charts, analyses, portfolios] = await Promise.all([api.charts(), api.analyses(), api.portfolios()])
    return { charts, analyses, portfolios }
  }, [])
  const [seeding, setSeeding] = useState(false)
  const toast = useToast()

  const seed = async () => {
    setSeeding(true)
    try {
      const res = await api.seed()
      toast(res.alreadySeeded ? 'Sample data is already loaded' : 'Sample charts, analyses and a 40-application portfolio were loaded')
      data.reload()
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setSeeding(false)
    }
  }

  return (
    <div className="page">
      <div className="hero">
        <div>
          <h1>Understand every Helm change.</h1>
          <p>Compare charts by configuration meaning, correlate PROD differences across versions, validate new PROD charts
            and find what needs attention across the whole portfolio.</p>
        </div>
        <div className="actions">
          <Button variant="primary" size="lg" onClick={() => navigate('/charts')}>Upload charts</Button>
          <Button size="lg" disabled={seeding} onClick={seed}>{seeding ? 'Loading…' : 'Load sample data'}</Button>
        </div>
      </div>

      <div className="grid grid-4" style={{ marginBottom: 22 }}>
        {MODES.map((m) => (
          <button key={m.to} className="mode-card" onClick={() => navigate(m.to)}>
            <span className="mode-icon" style={{ background: m.bg, color: m.color }}>{m.icon}</span>
            <span className="mode-title">{m.title}</span>
            <span className="mode-formula">{m.formula}</span>
            <span className="mode-text">{m.text}</span>
          </button>
        ))}
      </div>

      {data.loading ? <Spinner /> : data.data && (
        <div className="grid grid-3" style={{ gridTemplateColumns: '2fr 1fr' }}>
          <Card title="Recent analyses" actions={<Button variant="ghost" size="sm" onClick={() => navigate('/history')}>View all</Button>} flush>
            {data.data.analyses.length === 0 ? (
              <div className="empty">No analyses yet. Upload charts or load the sample data to get started.</div>
            ) : (
              <table className="table">
                <tbody>
                  {data.data.analyses.slice(0, 7).map((a) => (
                    <tr key={a.id} className="clickable" onClick={() => navigate(`/analysis/${a.id}`)}>
                      <td style={{ width: 36, fontSize: 18, color: 'var(--accent)' }}>{TYPE_ICON[a.type]}</td>
                      <td>
                        <div className="subject">{a.title}</div>
                        <div className="small faint">{TYPE_LABEL[a.type]} · {a.charts.slice(0, 2).map(chartLabel).join(' ↔ ')}</div>
                      </td>
                      <td className="small faint nowrap">{formatDate(a.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
          <Card title="Library">
            <div className="stack-v">
              <LibraryRow label="Charts" value={data.data.charts.length} to="/charts" />
              <LibraryRow label="Analyses" value={data.data.analyses.length} to="/history" />
              <LibraryRow label="Portfolios" value={data.data.portfolios.length} to="/portfolio" />
              <LibraryRow label="Difference Sets" value={data.data.analyses.filter((a) => a.type === 'PAIRWISE').length} to="/history" />
            </div>
          </Card>
        </div>
      )}
    </div>
  )
}

function LibraryRow({ label, value, to }: { label: string; value: number; to: string }) {
  return (
    <a href={`#${to}`} className="row" style={{ justifyContent: 'space-between', color: 'var(--text)' }}>
      <span className="muted">{label}</span>
      <span style={{ fontSize: 22, fontWeight: 600 }}>{value}</span>
    </a>
  )
}
