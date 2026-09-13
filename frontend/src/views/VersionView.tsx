import { useMemo, useState } from 'react'
import type { AnalysisRecord, Assessment, Cell, CorrelatedChange, VersionAnalysisResult } from '../api/types'
import { DiffExplorer, EntryFlags } from '../components/DiffExplorer'
import { FieldChanges, ItemPanel } from '../components/items'
import { ReviewControl } from '../components/ReviewControl'
import {
  Badge, Card, Drawer, EmptyState, SearchInput, StatTile, StatusBadge, Toggle,
} from '../components/ui'
import { CATEGORY_LABEL, CATEGORY_ORDER, chartLabel, statusLabel, statusTone } from '../lib/labels'

type Tab = 'correlation' | 'validation' | 'matrix' | 'np' | 'historical' | 'current'

export function VersionView({ record, onRecord }: { record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void }) {
  const v = record.version!
  const hasD = !!v.d
  const [tab, setTab] = useState<Tab>(record.type === 'DIFF_COMPARE' ? 'correlation' : hasD ? 'validation' : 'np')
  const [selected, setSelected] = useState<string | null>(null)
  const s = v.summary

  const tabs: { id: Tab; label: string; count?: number }[] = [
    ...(hasD ? [
      { id: 'correlation' as Tab, label: 'Diff ↔ Diff', count: v.correlations.length },
      { id: 'validation' as Tab, label: 'PROD validation' },
    ] : []),
    { id: 'matrix', label: 'Evolution matrix', count: v.matrix.filter((r) => !r.allEqual).length },
    { id: 'np', label: 'NON-PROD assessment', count: v.nonProdAssessment.length },
    { id: 'historical', label: 'Historical diff (A ↔ B)', count: v.historicalDiff.summary.differences },
    ...(hasD && v.currentDiff ? [{ id: 'current' as Tab, label: 'New version diff (C ↔ D)', count: v.currentDiff.summary.differences }] : []),
  ]

  const correlation = v.correlations.find((c) => c.id === selected)
  const assessment = v.nonProdAssessment.find((a) => a.id === selected)

  return (
    <>
      <Verdict v={v} />
      <div className="tabs">
        {tabs.map((t) => (
          <button key={t.id} className={`tab ${tab === t.id ? 'active' : ''}`} onClick={() => setTab(t.id)}>
            {t.label}{t.count !== undefined && <span className="count">{t.count}</span>}
          </button>
        ))}
      </div>

      {tab === 'correlation' && <CorrelationTab v={v} record={record} onSelect={setSelected} />}
      {tab === 'validation' && <ValidationTab v={v} onSelect={setSelected} />}
      {tab === 'matrix' && <MatrixTab v={v} onSelect={setSelected} />}
      {tab === 'np' && <NonProdTab v={v} record={record} onSelect={setSelected} />}
      {tab === 'historical' && <DiffExplorer diff={v.historicalDiff} record={record} onRecord={onRecord} />}
      {tab === 'current' && v.currentDiff && <DiffExplorer diff={v.currentDiff} record={record} onRecord={onRecord} />}

      <Drawer open={!!correlation} onClose={() => setSelected(null)} title={correlation?.subject}
        subtitle={correlation ? CATEGORY_LABEL[correlation.category] : undefined}>
        {correlation && <CorrelationDetail cc={correlation} v={v} record={record} onRecord={onRecord} />}
      </Drawer>
      <Drawer open={!!assessment} onClose={() => setSelected(null)} title={assessment?.subject}
        subtitle={assessment ? `${CATEGORY_LABEL[assessment.category]} · NON-PROD assessment` : undefined}>
        {assessment && <AssessmentDetail as={assessment} v={v} record={record} onRecord={onRecord} />}
      </Drawer>
      {s.historicalChanges === 0 && (
        <div className="banner banner-info">The historical charts have no differences, so there are no PROD expectations to carry forward.</div>
      )}
    </>
  )
}

function Verdict({ v }: { v: VersionAnalysisResult }) {
  const s = v.summary
  const tone = statusTone(s.verdict)
  const color = { green: '#34c759', red: '#ff3b30', orange: '#ff9500', blue: '#0a84ff', purple: '#af52de', teal: '#30b0c7', gray: '#aeaeb2' }[tone]
  const circumference = 2 * Math.PI * 32
  return (
    <Card>
      <div className="verdict" style={{ padding: 0 }}>
        <svg className="verdict-ring" viewBox="0 0 76 76" role="img" aria-label={`${s.score}%`}>
          <circle cx="38" cy="38" r="32" fill="none" stroke="#efeff3" strokeWidth="7" />
          <circle cx="38" cy="38" r="32" fill="none" stroke={color} strokeWidth="7" strokeLinecap="round"
            strokeDasharray={`${(s.score / 100) * circumference} ${circumference}`} transform="rotate(-90 38 38)" />
          <text x="38" y="43" textAnchor="middle" fontSize="16" fontWeight="600" fill="#1d1d1f">{s.score}%</text>
        </svg>
        <div style={{ minWidth: 0 }}>
          <div className="verdict-title">
            {v.validation ? 'Is the new PROD chart consistent?' : 'What should be considered for the new PROD chart?'}
            <StatusBadge code={s.verdict} />
          </div>
          <div className="verdict-text">{s.verdictMessage}</div>
          <div className="chips mt">
            <span className="chip">A <b>{chartLabel(v.a)}</b></span>
            <span className="chip">B <b>{chartLabel(v.b)}</b></span>
            <span className="chip">C <b>{chartLabel(v.c)}</b></span>
            <span className="chip">D <b>{v.d ? chartLabel(v.d) : 'not provided — preparation mode'}</b></span>
          </div>
        </div>
      </div>
    </Card>
  )
}

// ───────────── Diff ↔ Diff ─────────────

function CorrelationTab({ v, record, onSelect }: { v: VersionAnalysisResult; record: AnalysisRecord; onSelect: (id: string) => void }) {
  const [filter, setFilter] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const s = v.summary
  const rows = v.correlations.filter((c) => (!filter || c.classification === filter)
    && (!query || c.subject.toLowerCase().includes(query.toLowerCase())))

  const tile = (code: string, value: number, hint: string) => (
    <StatTile label={statusLabel(code)} value={value} tone={statusTone(code)} hint={hint}
      active={filter === code} onClick={() => setFilter(filter === code ? null : code)} />
  )

  return (
    <>
      <div className="tiles">
        {tile('CARRIED_FORWARD', s.carriedForward, 'Same or equivalent change')}
        {tile('CHANGED_IMPLEMENTATION', s.changedImplementation, 'Similar purpose, differs')}
        {tile('MISSING', s.missing, 'Historical change absent')}
        {tile('NEW_PROD_CHANGE', s.newProdChanges, 'Only in new PROD')}
        {tile('NO_LONGER_APPLICABLE', s.noLongerApplicable, 'Needs a decision')}
        {tile('ALREADY_IN_BASELINE', s.alreadyInBaseline, 'Already in NON-PROD')}
      </div>
      <Card flush title="Historical PROD changes vs new PROD changes"
        subtitle="DIFF A (historical NON-PROD → PROD) compared with DIFF B (new NON-PROD → PROD)">
        <div className="toolbar">
          <div className="legend">
            <span><Badge tone="green">Same logical change</Badge></span>
            <span><Badge tone="teal">Similar change</Badge></span>
            <span><Badge tone="orange">Different change</Badge></span>
            <span><Badge tone="gray">Unable to determine</Badge></span>
          </div>
          <div className="spacer" />
          <SearchInput value={query} onChange={setQuery} placeholder="Search configuration" />
        </div>
        <CorrelationTable rows={rows} record={record} onSelect={onSelect} />
      </Card>
    </>
  )
}

function CorrelationTable({ rows, record, onSelect }: { rows: CorrelatedChange[]; record: AnalysisRecord; onSelect: (id: string) => void }) {
  if (rows.length === 0) return <EmptyState icon="✓" title="Nothing here" />
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Configuration</th><th>Assessment</th><th style={{ width: '26%' }}>Historical PROD change (A → B)</th>
            <th style={{ width: '26%' }}>New PROD change (C → D)</th><th>Similarity</th><th>Flags</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.id} className="clickable" onClick={() => onSelect(c.id)}>
              <td><div className="subject">{c.subject}</div><div className="small faint">{CATEGORY_LABEL[c.category]}</div></td>
              <td><StatusBadge code={c.classification} /></td>
              <td className="small">{c.historical?.summary ?? <span className="faint">—</span>}</td>
              <td className="small">{c.current?.summary ?? <span className="faint">No PROD change in new version</span>}</td>
              <td><Badge tone={statusTone(c.similarity)}>{statusLabel(c.similarity)}</Badge></td>
              <td>
                <div className="flags">
                  {c.security && <Badge tone="red">🔒 Security</Badge>}
                  {c.review && <Badge tone="orange">Review</Badge>}
                  {record.reviews[c.id] && <StatusBadge code={record.reviews[c.id].status} />}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ───────────── PROD validation ─────────────

const VALIDATION_GROUPS: { code: string; title: string; text: string }[] = [
  { code: 'MISSING', title: 'Expected changes missing', text: 'Historical PROD characteristics that the new PROD chart does not implement.' },
  { code: 'UNEXPECTED', title: 'Unexpected differences', text: 'New PROD changes that weaken security or remove probes / TSC.' },
  { code: 'IMPLEMENTED_DIFFERENTLY', title: 'Implemented differently', text: 'Similar purpose, but the implementation differs — confirm it is intended.' },
  { code: 'NO_LONGER_PRESENT', title: 'Historical changes no longer present', text: 'The configuration no longer exists in the new version.' },
  { code: 'NEW_CHANGE', title: 'New changes introduced', text: 'PROD changes that did not exist historically.' },
  { code: 'IMPLEMENTED', title: 'Expected changes correctly implemented', text: 'Carried forward or already part of the new NON-PROD chart.' },
]

function ValidationTab({ v, onSelect }: { v: VersionAnalysisResult; onSelect: (id: string) => void }) {
  const s = v.summary
  const counts: Record<string, number> = {
    IMPLEMENTED: s.implemented, IMPLEMENTED_DIFFERENTLY: s.implementedDifferently, MISSING: s.validationMissing,
    NO_LONGER_PRESENT: s.noLongerPresent, NEW_CHANGE: s.newChanges, UNEXPECTED: s.unexpected,
  }
  return (
    <>
      <div className="tiles">
        {VALIDATION_GROUPS.map((g) => <StatTile key={g.code} label={statusLabel(g.code)} value={counts[g.code]} tone={statusTone(g.code)} />)}
        <StatTile label="Requires review" value={s.review} tone="orange" />
      </div>
      {VALIDATION_GROUPS.map((g) => {
        const rows = v.correlations.filter((c) => c.validation === g.code)
        if (rows.length === 0) return null
        return (
          <Card key={g.code} flush title={<span className="row">{g.title} <StatusBadge code={g.code} /></span>} subtitle={g.text}>
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Configuration</th><th>Explanation</th><th>New PROD (D)</th></tr></thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id} className="clickable" onClick={() => onSelect(c.id)}>
                      <td style={{ width: '24%' }}><div className="subject">{c.subject}</div><div className="small faint">{CATEGORY_LABEL[c.category]}</div></td>
                      <td className="small">{c.explanation}</td>
                      <td style={{ width: '26%' }} className="value small">{c.stateD?.display ?? <span className="faint">Not present</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )
      })}
    </>
  )
}

// ───────────── Evolution matrix ─────────────

function MatrixCell({ cell }: { cell: Cell }) {
  if (!cell.present) return <span className="faint" title={cell.detail}>{cell.label === 'n/a' ? 'n/a' : 'No'}</span>
  const tone = cell.label === 'Plain' ? 'red' : cell.label === 'AKeyless' ? 'green'
    : ['K8s Secret', 'External', 'Vault'].includes(cell.label) ? 'teal'
      : cell.label === 'Disabled' ? 'orange' : cell.label === 'Yes' ? 'gray' : null
  if (tone) return <span title={cell.detail}><Badge tone={tone}>{cell.label}</Badge></span>
  return <span className="value small" title={cell.detail}>{cell.label}</span>
}

function MatrixTab({ v, onSelect }: { v: VersionAnalysisResult; onSelect: (id: string) => void }) {
  const [showUnchanged, setShowUnchanged] = useState(false)
  const [query, setQuery] = useState('')
  const [assessment, setAssessment] = useState('')
  const assessments = useMemo(() => [...new Set(v.matrix.map((r) => r.assessment))], [v.matrix])
  const rows = v.matrix.filter((r) => (showUnchanged || !r.allEqual)
    && (!assessment || r.assessment === assessment)
    && (!query || r.subject.toLowerCase().includes(query.toLowerCase())))
  const groups = CATEGORY_ORDER.map((c) => ({ c, rows: rows.filter((r) => r.category === c) })).filter((g) => g.rows.length)
  const head = (label: string, ref?: { version: string; environment: string }) =>
    ref ? `${ref.version} ${ref.environment === 'PROD' ? 'PROD' : 'NP'}` : label

  return (
    <Card flush title="Configuration evolution" subtitle="How each configuration moves across versions and environments">
      <div className="toolbar">
        <select className="select" style={{ width: 230 }} value={assessment} onChange={(e) => setAssessment(e.target.value)}>
          <option value="">All assessments</option>
          {assessments.map((a) => <option key={a} value={a}>{statusLabel(a)}</option>)}
        </select>
        <Toggle checked={showUnchanged} onChange={setShowUnchanged} label="Show unchanged" />
        <div className="spacer" />
        <SearchInput value={query} onChange={setQuery} placeholder="Search configuration" />
      </div>
      {rows.length === 0 ? <EmptyState icon="✓" title="Nothing to show" /> : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Configuration</th>
                <th>{head('A', v.a)}</th><th>{head('B', v.b)}</th><th>{head('C', v.c)}</th><th>{head('D', v.d)}</th>
                <th>Assessment</th>
              </tr>
            </thead>
            <tbody>
              {groups.map(({ c, rows: groupRows }) => (
                <MatrixGroup key={c} label={CATEGORY_LABEL[c]} rows={groupRows} onSelect={onSelect} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

function MatrixGroup({ label, rows, onSelect }: { label: string; rows: VersionAnalysisResult['matrix']; onSelect: (id: string) => void }) {
  return (
    <>
      <tr className="group-row"><td colSpan={6}>{label} · {rows.length}</td></tr>
      {rows.map((r) => (
        <tr key={r.key} className={r.referenceId ? 'clickable' : ''} onClick={() => r.referenceId && onSelect(r.referenceId)}>
          <td style={{ width: '24%' }}><div className="subject">{r.subject}</div><div className="key">{r.key}</div></td>
          {r.cells.map((cell, i) => <td key={i}><MatrixCell cell={cell} /></td>)}
          <td><StatusBadge code={r.assessment} /></td>
        </tr>
      ))}
    </>
  )
}

// ───────────── NON-PROD assessment ─────────────

function NonProdTab({ v, record, onSelect }: { v: VersionAnalysisResult; record: AnalysisRecord; onSelect: (id: string) => void }) {
  const [filter, setFilter] = useState<string | null>(null)
  const s = v.summary
  const rows = v.nonProdAssessment.filter((a) => !filter || a.status === filter)
  const tile = (code: string, value: number) => (
    <StatTile label={statusLabel(code)} value={value} tone={statusTone(code)} active={filter === code}
      onClick={() => setFilter(filter === code ? null : code)} />
  )
  return (
    <>
      <div className="banner banner-info">
        Which historical PROD characteristics should be considered while preparing the new PROD chart from <b>&nbsp;{chartLabel(v.c)}</b>?
        Not every historical difference is automatically required — decide on each item below.
      </div>
      <div className="tiles">
        {tile('ALREADY_SATISFIED', s.npAlreadySatisfied)}
        {tile('REQUIRES_CHANGE', s.npRequiresChange)}
        {tile('DIFFERENT_IMPLEMENTATION', s.npDifferentImplementation)}
        {tile('POTENTIALLY_IRRELEVANT', s.npPotentiallyIrrelevant)}
        {tile('REVIEW', s.npReview)}
      </div>
      <Card flush title="Historical PROD changes against the new NON-PROD chart">
        {rows.length === 0 ? <EmptyState icon="✓" title="Nothing here" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Configuration</th><th>Status</th><th>Historical PROD change</th><th>New NON-PROD</th><th>Recommendation</th></tr></thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.id} className="clickable" onClick={() => onSelect(a.id)}>
                    <td><div className="subject">{a.subject}</div><div className="small faint">{CATEGORY_LABEL[a.category]}</div></td>
                    <td>
                      <div className="flags">
                        <StatusBadge code={a.status} />
                        {a.security && <Badge tone="red">🔒</Badge>}
                        {record.reviews[a.id] && <StatusBadge code={record.reviews[a.id].status} />}
                      </div>
                    </td>
                    <td className="small">{a.historical.summary}</td>
                    <td className="value small">{a.candidate?.display ?? <span className="faint">Not present</span>}</td>
                    <td className="small">{a.recommendation}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  )
}

// ───────────── detail drawers ─────────────

function CorrelationDetail({ cc, v, record, onRecord }: { cc: CorrelatedChange; v: VersionAnalysisResult; record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void }) {
  return (
    <div className="stack-v">
      <div className="row">
        <StatusBadge code={cc.classification} />
        <Badge tone={statusTone(cc.similarity)}>{statusLabel(cc.similarity)}</Badge>
        {cc.validation && <StatusBadge code={cc.validation} />}
        {cc.security && <Badge tone="red">🔒 Security</Badge>}
        {cc.review && <Badge tone="orange">Requires review</Badge>}
      </div>
      <div style={{ fontSize: 15 }}>{cc.explanation}</div>

      <div className="section-title">Configuration across the four charts</div>
      <div className="state-strip">
        <ItemPanel title={`A · ${chartLabel(v.a)}`} item={cc.stateA} chartId={v.a.chartId} />
        <ItemPanel title={`B · ${chartLabel(v.b)}`} item={cc.stateB} chartId={v.b.chartId} />
        <ItemPanel title={`C · ${chartLabel(v.c)}`} item={cc.stateC} chartId={v.c.chartId} />
        <ItemPanel title={v.d ? `D · ${chartLabel(v.d)}` : 'D · not provided'} item={cc.stateD} chartId={v.d?.chartId} />
      </div>

      {cc.historical && (
        <>
          <div className="section-title">Historical PROD change (A → B)</div>
          <div className="row"><StatusBadge code={cc.historical.status} /> <span>{cc.historical.summary}</span></div>
          <EntryFlags entry={cc.historical} />
          <FieldChanges changes={cc.historical.fieldChanges} leftLabel="A" rightLabel="B" />
        </>
      )}
      {cc.current && (
        <>
          <div className="section-title">New PROD change (C → D)</div>
          <div className="row"><StatusBadge code={cc.current.status} /> <span>{cc.current.summary}</span></div>
          {cc.current.notes.length > 0 && <ul className="notes">{cc.current.notes.map((n) => <li key={n}>{n}</li>)}</ul>}
          <FieldChanges changes={cc.current.fieldChanges} leftLabel="C" rightLabel="D" />
        </>
      )}
      {cc.implementationDifferences.length > 0 && (
        <>
          <div className="section-title">Implementation differences (historical PROD vs new PROD)</div>
          <FieldChanges changes={cc.implementationDifferences} leftLabel="Historical PROD" rightLabel="New PROD" />
        </>
      )}

      <div className="section-title">Review decision</div>
      <ReviewControl record={record} itemId={cc.id} onSaved={onRecord} />
    </div>
  )
}

function AssessmentDetail({ as, v, record, onRecord }: { as: Assessment; v: VersionAnalysisResult; record: AnalysisRecord; onRecord: (r: AnalysisRecord) => void }) {
  return (
    <div className="stack-v">
      <div className="row">
        <StatusBadge code={as.status} />
        {as.security && <Badge tone="red">🔒 Security</Badge>}
      </div>
      <div style={{ fontSize: 15 }}>{as.recommendation}</div>
      <div className="section-title">Historical PROD change</div>
      <div>{as.historical.summary}</div>
      <div className="grid grid-3">
        <ItemPanel title={`A · ${chartLabel(v.a)}`} item={as.historical.left} chartId={v.a.chartId} />
        <ItemPanel title={`B · ${chartLabel(v.b)}`} item={as.historical.right} chartId={v.b.chartId} />
        <ItemPanel title={`C · ${chartLabel(v.c)}`} item={as.candidate} chartId={v.c.chartId} />
      </div>
      <FieldChanges changes={as.historical.fieldChanges} leftLabel="A" rightLabel="B" />
      <div className="section-title">Review decision</div>
      <ReviewControl record={record} itemId={as.id} onSaved={onRecord} />
    </div>
  )
}
