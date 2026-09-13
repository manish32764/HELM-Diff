import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Expectation } from '../api/types'
import { FileDrop } from '../components/UploadChartDialog'
import {
  Badge, Button, Card, EmptyState, Field, Modal, PageHeader, Segmented, Spinner, useToast,
} from '../components/ui'
import { appendFiles } from '../lib/files'
import type { PickedFile } from '../lib/files'
import { CATEGORY_LABEL, CATEGORY_ORDER, formatDate } from '../lib/labels'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

export function PortfolioPage() {
  const lib = useAsync(async () => {
    const [portfolios, analyses] = await Promise.all([api.portfolios(), api.analyses()])
    return { portfolios, analyses }
  }, [])
  const [uploadOpen, setUploadOpen] = useState(false)
  const [portfolioId, setPortfolioId] = useState('')
  const [setId, setSetId] = useState('')
  const [expectations, setExpectations] = useState<Expectation[] | null>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  const sets = (lib.data?.analyses ?? []).filter((a) => a.type === 'PAIRWISE')

  useEffect(() => {
    if (!setId) { setExpectations(null); return }
    api.expectations(setId).then(setExpectations).catch((e: Error) => toast(e.message, 'error'))
  }, [setId, toast])

  const update = (id: string, patch: Partial<Expectation>) =>
    setExpectations((xs) => xs?.map((x) => (x.id === id ? { ...x, ...patch } : x)) ?? null)

  const run = async () => {
    setBusy(true)
    try {
      const rec = await api.portfolioAnalysis({ portfolioId, differenceSetId: setId || undefined, expectations: expectations ?? undefined })
      navigate(`/analysis/${rec.id}`)
    } catch (e) {
      toast((e as Error).message, 'error')
      setBusy(false)
    }
  }

  const removePortfolio = async (id: string) => {
    if (!window.confirm('Delete this portfolio and its charts?')) return
    await api.deletePortfolio(id)
    if (portfolioId === id) setPortfolioId('')
    lib.reload()
  }

  const selectedCount = expectations?.filter((x) => x.selected).length ?? 0

  return (
    <div className="page">
      <PageHeader eyebrow="Mode 4" title="Portfolio analysis"
        subtitle="Apply historical PROD expectations across every application to find charts that are compliant, need changes, or behave unusually."
        actions={<Button variant="primary" onClick={() => setUploadOpen(true)}>＋ Upload portfolio</Button>} />

      {lib.loading ? <Spinner /> : (
        <>
          <Card title="Portfolios" flush>
            {lib.data!.portfolios.length === 0 ? (
              <EmptyState icon="▦" title="No portfolios yet" text="Upload a .zip or folder with one folder per application and NON-PROD / PROD sub-folders."
                action={<Button variant="primary" onClick={() => setUploadOpen(true)}>Upload portfolio</Button>} />
            ) : (
              <table className="table">
                <thead><tr><th>Portfolio</th><th>Version</th><th className="num">Applications</th><th className="num">NON-PROD + PROD pairs</th><th>Uploaded</th><th /></tr></thead>
                <tbody>
                  {lib.data!.portfolios.map((p) => (
                    <tr key={p.id} className="clickable" onClick={() => setPortfolioId(p.id)} style={portfolioId === p.id ? { background: 'var(--accent-soft)' } : undefined}>
                      <td className="subject">{portfolioId === p.id ? '● ' : ''}{p.name}</td>
                      <td>{p.version}</td>
                      <td className="num">{p.apps.length}</td>
                      <td className="num">{p.apps.filter((a) => a.nonProdChartId && a.prodChartId).length}</td>
                      <td className="small faint">{formatDate(p.uploadedAt)}</td>
                      <td>
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          {p.warnings.length > 0 && <Badge tone="orange" title={p.warnings.join('\n')}>⚠ {p.warnings.length}</Badge>}
                          <Button size="sm" variant="danger" onClick={(e) => { e.stopPropagation(); removePortfolio(p.id) }}>Delete</Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          <Card title="Run analysis" subtitle="1 · choose a portfolio above   2 · choose the Difference Set that describes historical PROD behaviour   3 · decide which expectations apply">
            <div className="grid grid-2">
              <Field label="Portfolio">
                <select className="select" value={portfolioId} onChange={(e) => setPortfolioId(e.target.value)}>
                  <option value="">Select…</option>
                  {lib.data!.portfolios.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.apps.length} apps</option>)}
                </select>
              </Field>
              <Field label="Historical Difference Set" hint="Create one with a two-chart diff, e.g. 3.0.4 NON-PROD ↔ 3.0.4 PROD">
                <select className="select" value={setId} onChange={(e) => setSetId(e.target.value)}>
                  <option value="">Only built-in policies</option>
                  {sets.map((s) => <option key={s.id} value={s.id}>{s.title}</option>)}
                </select>
              </Field>
            </div>

            {expectations && (
              <>
                <div className="banner banner-info mt">
                  Not every historical PROD difference must be copied to the new version. Untick what does not apply and choose whether an
                  expectation applies to every chart or only to charts that contain the configuration.
                </div>
                <ExpectationList expectations={expectations} onUpdate={update} />
              </>
            )}

            <div className="row mt" style={{ justifyContent: 'flex-end' }}>
              {expectations && <span className="small muted">{selectedCount} expectation(s) selected</span>}
              <Button variant="primary" size="lg" disabled={!portfolioId || busy || (!!expectations && selectedCount === 0)} onClick={run}>
                {busy ? 'Analysing…' : 'Analyse portfolio'}
              </Button>
            </div>
          </Card>

          <Card title="Recent portfolio analyses" flush>
            {lib.data!.analyses.filter((a) => a.type === 'PORTFOLIO').length === 0 ? <div className="empty">None yet.</div> : (
              <table className="table">
                <tbody>
                  {lib.data!.analyses.filter((a) => a.type === 'PORTFOLIO').map((a) => (
                    <tr key={a.id} className="clickable" onClick={() => navigate(`/analysis/${a.id}`)}>
                      <td className="subject">{a.title}</td>
                      <td className="small muted">{a.headline.apps} apps · {a.headline.compliant} compliant · {a.headline.requireChanges} require changes · {a.headline.requireReview} review</td>
                      <td className="small faint">{formatDate(a.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}

      <UploadPortfolioDialog open={uploadOpen} onClose={() => setUploadOpen(false)}
        onUploaded={(id) => { lib.reload(); setPortfolioId(id) }} />
    </div>
  )
}

function ExpectationList({ expectations, onUpdate }: { expectations: Expectation[]; onUpdate: (id: string, p: Partial<Expectation>) => void }) {
  const groups = CATEGORY_ORDER.map((c) => ({ c, xs: expectations.filter((x) => x.category === c) })).filter((g) => g.xs.length)
  return (
    <div className="stack-v mt">
      {groups.map(({ c, xs }) => (
        <div key={c} style={{ border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden' }}>
          <div className="row" style={{ padding: '8px 14px', background: 'var(--surface-2)', justifyContent: 'space-between' }}>
            <span className="small" style={{ fontWeight: 600 }}>{CATEGORY_LABEL[c]}</span>
            <span className="row">
              <Button size="sm" variant="ghost" onClick={() => xs.forEach((x) => onUpdate(x.id, { selected: true }))}>Select all</Button>
              <Button size="sm" variant="ghost" onClick={() => xs.forEach((x) => onUpdate(x.id, { selected: false }))}>None</Button>
            </span>
          </div>
          <table className="table">
            <tbody>
              {xs.map((x) => (
                <tr key={x.id} style={{ opacity: x.selected ? 1 : 0.55 }}>
                  <td style={{ width: 36 }}>
                    <input type="checkbox" checked={x.selected} onChange={(e) => onUpdate(x.id, { selected: e.target.checked })} />
                  </td>
                  <td>
                    <div className="subject">{x.title}</div>
                    <div className="small faint">{x.rationale}</div>
                  </td>
                  <td style={{ width: 260 }}>
                    <Segmented value={x.applicability} onChange={(v) => onUpdate(x.id, { applicability: v })} options={[
                      { value: 'ALWAYS', label: 'Every chart' }, { value: 'IF_PRESENT', label: 'Where present' },
                    ]} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  )
}

function UploadPortfolioDialog({ open, onClose, onUploaded }: { open: boolean; onClose: () => void; onUploaded: (id: string) => void }) {
  const [files, setFiles] = useState<PickedFile[]>([])
  const [name, setName] = useState('')
  const [version, setVersion] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  useEffect(() => { if (open) { setFiles([]); setName(''); setVersion('') } }, [open])

  const submit = async () => {
    const form = new FormData()
    appendFiles(form, files)
    if (name) form.append('name', name)
    if (version) form.append('version', version)
    setBusy(true)
    try {
      const p = await api.uploadPortfolio(form)
      toast(`${p.apps.length} applications recognised`)
      onUploaded(p.id)
      onClose()
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Upload portfolio" width={680}
      subtitle="Charts are grouped by application and environment automatically"
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={busy || files.length === 0} onClick={submit}>{busy ? 'Uploading & parsing…' : 'Upload'}</Button></>}>
      <div className="stack-v">
        <FileDrop files={files} onFiles={setFiles} hint="A .zip, a folder, or many files" />
        <div className="banner banner-info" style={{ marginBottom: 0 }}>
          <div>
            Supported layouts:
            <div className="value" style={{ marginTop: 6 }}>
              &lt;app&gt;/nonprod/… and &lt;app&gt;/prod/…<br />
              nonprod/&lt;app&gt;/… and prod/&lt;app&gt;/…<br />
              &lt;app&gt;-nonprod.yaml and &lt;app&gt;-prod.yaml
            </div>
            <div className="small" style={{ marginTop: 6 }}>Recognised as NON-PROD: nonprod, np, dev, qa, uat, sit, test, staging, preprod. As PROD: prod, prd, production, live.</div>
          </div>
        </div>
        <div className="grid grid-2">
          <Field label="Name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Retail platform 3.1.3" /></Field>
          <Field label="Version"><input className="input" value={version} onChange={(e) => setVersion(e.target.value)} placeholder="3.1.3" /></Field>
        </div>
      </div>
    </Modal>
  )
}
