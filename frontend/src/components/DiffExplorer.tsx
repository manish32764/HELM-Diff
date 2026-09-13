import { useMemo, useState } from 'react'
import { api } from '../api/client'
import type { AnalysisRecord, Category, DiffEntry, DiffResult, SearchHit } from '../api/types'
import { CATEGORY_ICON, CATEGORY_LABEL, CATEGORY_ORDER, chartLabel, statusLabel } from '../lib/labels'
import { useAsync } from '../lib/useAsync'
import { FieldChanges, ItemPanel, ItemValue } from './items'
import { ReviewControl } from './ReviewControl'
import {
  Badge, Button, Card, Drawer, EmptyState, SearchInput, Segmented, StatTile, StatusBadge, Toggle, useToast,
} from './ui'

type StatusFilter = 'DIFF' | 'CHANGED' | 'ADDED' | 'REMOVED' | 'COMMON' | 'ALL'

export function EntryFlags({ entry, reviewMark }: { entry: DiffEntry; reviewMark?: string }) {
  return (
    <div className="flags">
      {entry.security && <Badge tone="red" title="Security-related change">🔒 Security</Badge>}
      {entry.prodSpecific && <Badge tone="purple">PROD</Badge>}
      {entry.review && <Badge tone="orange" title={entry.notes.join(' ')}>Review</Badge>}
      {entry.matchType === 'SIMILAR_NAME' && <Badge tone="teal">Similar name</Badge>}
      {reviewMark && <StatusBadge code={reviewMark} />}
    </div>
  )
}

/** Summary tiles, filters, grouped table and detail drawer for one pairwise comparison. */
export function DiffExplorer({ diff, record, onRecord, allowPortfolioSearch }: {
  diff: DiffResult
  record: AnalysisRecord
  onRecord: (r: AnalysisRecord) => void
  allowPortfolioSearch?: boolean
}) {
  const [status, setStatus] = useState<StatusFilter>('DIFF')
  const [category, setCategory] = useState<Category | null>(null)
  const [query, setQuery] = useState('')
  const [securityOnly, setSecurityOnly] = useState(false)
  const [reviewOnly, setReviewOnly] = useState(false)
  const [prodOnly, setProdOnly] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const s = diff.summary

  const filtered = useMemo(() => diff.entries.filter((e) => {
    if (status === 'DIFF' && e.status === 'COMMON') return false
    if (status !== 'DIFF' && status !== 'ALL' && e.status !== status) return false
    if (category && e.category !== category) return false
    if (securityOnly && !e.security) return false
    if (reviewOnly && !e.review) return false
    if (prodOnly && !e.prodSpecific) return false
    if (query) {
      const q = query.toLowerCase()
      return e.subject.toLowerCase().includes(q) || e.key.toLowerCase().includes(q) || e.summary.toLowerCase().includes(q)
    }
    return true
  }), [diff.entries, status, category, securityOnly, reviewOnly, prodOnly, query])

  const byCategory = CATEGORY_ORDER.map((c) => ({ c, rows: filtered.filter((e) => e.category === c) })).filter((g) => g.rows.length)
  const categoryCounts = CATEGORY_ORDER
    .map((c) => ({ c, n: diff.entries.filter((e) => e.category === c && (status === 'ALL' || status === 'COMMON' ? true : e.status !== 'COMMON')).length }))
    .filter((x) => x.n > 0)
  const selected = diff.entries.find((e) => e.id === selectedId)
  const resetTiles = () => { setSecurityOnly(false); setReviewOnly(false); setProdOnly(false) }

  return (
    <>
      <div className="tiles">
        <StatTile label="Total differences" value={s.differences} tone="blue" active={status === 'DIFF' && !securityOnly && !reviewOnly && !prodOnly}
          onClick={() => { setStatus('DIFF'); resetTiles() }} hint={`of ${s.totalItems} configuration items`} />
        <StatTile label="PROD-specific" value={s.prodSpecific} tone="purple" active={prodOnly}
          onClick={() => { resetTiles(); setProdOnly(true); setStatus('DIFF') }} />
        <StatTile label="Security changes" value={s.security} tone="red" active={securityOnly}
          onClick={() => { resetTiles(); setSecurityOnly(true); setStatus('DIFF') }} />
        <StatTile label="Requires review" value={s.review} tone="orange" active={reviewOnly}
          onClick={() => { resetTiles(); setReviewOnly(true); setStatus('ALL') }} />
        <StatTile label="Changed" value={s.changed} tone="orange" active={status === 'CHANGED'} onClick={() => { resetTiles(); setStatus('CHANGED') }} />
        <StatTile label={`Only in ${diff.right.role}`} value={s.added} tone="blue" active={status === 'ADDED'} onClick={() => { resetTiles(); setStatus('ADDED') }} />
        <StatTile label={`Only in ${diff.left.role}`} value={s.removed} tone="red" active={status === 'REMOVED'} onClick={() => { resetTiles(); setStatus('REMOVED') }} />
        <StatTile label="Common" value={s.common} tone="gray" active={status === 'COMMON'} onClick={() => { resetTiles(); setStatus('COMMON') }} />
      </div>

      <Card flush title="Configuration differences"
        subtitle={`${diff.left.role}: ${chartLabel(diff.left)} (${diff.left.revision})  ↔  ${diff.right.role}: ${chartLabel(diff.right)} (${diff.right.revision})`}>
        <div className="toolbar">
          <Segmented value={status} onChange={setStatus} options={[
            { value: 'DIFF', label: 'Differences', count: s.differences },
            { value: 'CHANGED', label: 'Changed', count: s.changed },
            { value: 'ADDED', label: 'Added', count: s.added },
            { value: 'REMOVED', label: 'Removed', count: s.removed },
            { value: 'COMMON', label: 'Common', count: s.common },
            { value: 'ALL', label: 'All' },
          ]} />
          <div className="spacer" />
          <Toggle checked={securityOnly} onChange={setSecurityOnly} label="Security" />
          <Toggle checked={reviewOnly} onChange={setReviewOnly} label="Review" />
          <SearchInput value={query} onChange={setQuery} placeholder="Search configuration" />
        </div>
        <div className="toolbar chips">
          <button className={`btn btn-sm ${category === null ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setCategory(null)}>All areas</button>
          {categoryCounts.map(({ c, n }) => (
            <button key={c} className={`btn btn-sm ${category === c ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setCategory(category === c ? null : c)}>
              {CATEGORY_ICON[c]} {CATEGORY_LABEL[c]} <span style={{ opacity: .6 }}>{n}</span>
            </button>
          ))}
        </div>
        {filtered.length === 0 ? (
          <EmptyState icon="✓" title="Nothing matches" text="Try another filter." />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '24%' }}>Configuration</th>
                  <th>Status</th>
                  <th style={{ width: '24%' }}>{diff.left.role} · {chartLabel(diff.left)}</th>
                  <th style={{ width: '24%' }}>{diff.right.role} · {chartLabel(diff.right)}</th>
                  <th>Flags</th>
                </tr>
              </thead>
              <tbody>
                {byCategory.map(({ c, rows }) => (
                  <GroupRows key={c} category={c} rows={rows} record={record} onSelect={setSelectedId} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Drawer open={!!selected} onClose={() => setSelectedId(null)}
        title={selected?.subject} subtitle={selected ? `${CATEGORY_LABEL[selected.category]} · ${selected.key}` : undefined}>
        {selected && (
          <DiffEntryDetail entry={selected} diff={diff} record={record} onRecord={onRecord} allowPortfolioSearch={allowPortfolioSearch} />
        )}
      </Drawer>
    </>
  )
}

function GroupRows({ category, rows, record, onSelect }: { category: Category; rows: DiffEntry[]; record: AnalysisRecord; onSelect: (id: string) => void }) {
  return (
    <>
      <tr className="group-row"><td colSpan={5}>{CATEGORY_ICON[category]} {CATEGORY_LABEL[category]} · {rows.length}</td></tr>
      {rows.map((e) => (
        <tr key={e.id} className="clickable" onClick={() => onSelect(e.id)}>
          <td>
            <div className="subject">{e.subject}</div>
            <div className="small muted">{e.summary}</div>
          </td>
          <td><StatusBadge code={e.status} /></td>
          <td><ItemValue item={e.left} /></td>
          <td><ItemValue item={e.right} /></td>
          <td><EntryFlags entry={e} reviewMark={record.reviews[e.id]?.status} /></td>
        </tr>
      ))}
    </>
  )
}

export function DiffEntryDetail({ entry, diff, record, onRecord, allowPortfolioSearch }: {
  entry: DiffEntry; diff: DiffResult; record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void; allowPortfolioSearch?: boolean
}) {
  return (
    <div className="stack-v">
      <div className="row">
        <StatusBadge code={entry.status} />
        <EntryFlags entry={entry} />
      </div>
      <div style={{ fontSize: 15 }}>{entry.summary}</div>
      {entry.notes.length > 0 && <ul className="notes">{entry.notes.map((n) => <li key={n}>{n}</li>)}</ul>}

      <div className="section-title">Configuration</div>
      <div className="grid grid-2">
        <ItemPanel title={`${diff.left.role} · ${chartLabel(diff.left)}`} item={entry.left} chartId={diff.left.chartId} />
        <ItemPanel title={`${diff.right.role} · ${chartLabel(diff.right)}`} item={entry.right} chartId={diff.right.chartId} />
      </div>

      {entry.fieldChanges.length > 0 && (
        <>
          <div className="section-title">What changed</div>
          <FieldChanges changes={entry.fieldChanges} leftLabel={diff.left.role} rightLabel={diff.right.role} />
        </>
      )}

      <div className="section-title">Review decision</div>
      <ReviewControl record={record} itemId={entry.id} onSaved={onRecord} />

      {allowPortfolioSearch && entry.status !== 'COMMON' && <PortfolioSearch differenceSetId={record.id} entryId={entry.id} />}
    </div>
  )
}

function PortfolioSearch({ differenceSetId, entryId }: { differenceSetId: string; entryId: string }) {
  const portfolios = useAsync(() => api.portfolios(), [])
  const [portfolioId, setPortfolioId] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  if (!portfolios.data || portfolios.data.length === 0) return null

  const run = async () => {
    setBusy(true)
    try {
      setHits(await api.search(portfolioId, differenceSetId, entryId))
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const counts = hits?.reduce<Record<string, number>>((acc, h) => ({ ...acc, [h.result.status]: (acc[h.result.status] ?? 0) + 1 }), {})

  return (
    <>
      <div className="section-title">Find this change across charts</div>
      <div className="row">
        <select className="select" style={{ maxWidth: 320 }} value={portfolioId} onChange={(e) => { setPortfolioId(e.target.value); setHits(null) }}>
          <option value="">Select a portfolio…</option>
          {portfolios.data.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.apps.length} apps</option>)}
        </select>
        <Button size="sm" variant="primary" disabled={!portfolioId || busy} onClick={run}>{busy ? 'Searching…' : 'Search'}</Button>
      </div>
      {hits && (
        <>
          <div className="chips mt">
            {Object.entries(counts ?? {}).map(([k, v]) => <span key={k} className="chip"><StatusBadge code={k} /> <b>{v}</b></span>)}
          </div>
          <div className="table-wrap mt" style={{ border: '1px solid var(--line)', borderRadius: 12 }}>
            <table className="table">
              <thead><tr><th>Application</th><th>Checked on</th><th>Result</th><th>Detail</th></tr></thead>
              <tbody>
                {hits.map((h) => (
                  <tr key={h.app}>
                    <td className="subject">{h.app}</td>
                    <td className="small">{h.evaluatedOn === 'PROD' ? 'PROD' : 'NON-PROD'}</td>
                    <td><StatusBadge code={h.result.status} /> <span className="small faint">{statusLabel(h.result.match)}</span></td>
                    <td className="small">{h.result.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  )
}
