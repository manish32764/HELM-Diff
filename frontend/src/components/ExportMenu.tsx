import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { Button } from './ui'

const FORMATS = [
  { format: 'xlsx', label: 'Excel workbook', sub: 'Every table as a sheet, with status colours' },
  { format: 'html', label: 'HTML report', sub: 'Shareable; print or save as PDF' },
  { format: 'csv', label: 'CSV', sub: 'Main table for spreadsheets' },
  { format: 'json', label: 'JSON', sub: 'Complete data for automation' },
]

export function ExportMenu({ analysisId }: { analysisId: string }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [open])

  return (
    <div className="rel" ref={ref}>
      <Button variant="primary" onClick={() => setOpen((o) => !o)}>⤓ Export</Button>
      {open && (
        <div className="menu">
          {FORMATS.map((f) => (
            <button key={f.format} onClick={() => { window.location.href = api.exportUrl(analysisId, f.format); setOpen(false) }}>
              <span>{f.label}</span>
              <span className="menu-sub">{f.sub}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
