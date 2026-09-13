import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { AnalysisRecord } from '../api/types'
import { formatDate } from '../lib/labels'
import { Button, Segmented, useToast } from './ui'

const OPTIONS = [
  { value: 'OPEN', label: 'Needs review' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'NEEDS_CHANGE', label: 'Change required' },
  { value: 'NOT_APPLICABLE', label: 'Not applicable' },
]

/** Records a human decision on an item; stored in the analysis audit trail. */
export function ReviewControl({ record, itemId, onSaved }: { record: AnalysisRecord; itemId: string; onSaved: (r: AnalysisRecord) => void }) {
  const mark = record.reviews[itemId]
  const [status, setStatus] = useState(mark?.status ?? 'OPEN')
  const [comment, setComment] = useState(mark?.comment ?? '')
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  useEffect(() => {
    setStatus(mark?.status ?? 'OPEN')
    setComment(mark?.comment ?? '')
  }, [itemId, mark?.status, mark?.comment])

  const save = async () => {
    setBusy(true)
    try {
      onSaved(await api.review(record.id, itemId, status, comment))
      toast('Review decision saved to the audit trail')
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack-v" style={{ gap: 10 }}>
      <Segmented value={status} options={OPTIONS} onChange={setStatus} />
      <textarea className="textarea" placeholder="Decision or comment (optional)" value={comment}
        onChange={(e) => setComment(e.target.value)} />
      <div className="row">
        <Button variant="primary" size="sm" disabled={busy} onClick={save}>Save decision</Button>
        {mark && <span className="small faint">Last updated {formatDate(mark.updatedAt)}</span>}
      </div>
    </div>
  )
}
