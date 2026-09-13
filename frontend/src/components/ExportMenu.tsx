import { useEffect, useRef, useState } from 'react'
import { Button } from './ui'

const FORMATS: Record<string, { label: string; sub: string }> = {
  xlsx: { label: 'Excel workbook', sub: 'Every table as a sheet, with status colours' },
  html: { label: 'HTML report', sub: 'Shareable; print or save as PDF' },
  csv: { label: 'CSV', sub: 'Main table for spreadsheets' },
  json: { label: 'JSON', sub: 'Complete data for automation' },
}

export function ExportMenu({ url, formats = ['xlsx', 'html', 'csv', 'json'] }: { url: (format: string) => string; formats?: string[] }) {
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
          {formats.map((f) => (
            <button key={f} onClick={() => { window.location.href = url(f); setOpen(false) }}>
              <span>{FORMATS[f].label}</span>
              <span className="menu-sub">{FORMATS[f].sub}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
