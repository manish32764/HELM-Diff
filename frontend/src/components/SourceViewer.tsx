import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import { chartLabel } from '../lib/labels'
import { useAsync } from '../lib/useAsync'
import { Badge, Modal, Spinner } from './ui'

type OpenSource = (chartId: string, file?: string, line?: number) => void
const SourceContext = createContext<OpenSource>(() => {})

export function useSourceViewer() {
  return useContext(SourceContext)
}

/** Lets any component navigate from a difference to the corresponding Helm configuration. */
export function SourceViewerProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<{ chartId: string; file?: string; line?: number } | null>(null)
  const open = useCallback<OpenSource>((chartId, file, line) => setTarget({ chartId, file, line }), [])
  return (
    <SourceContext.Provider value={open}>
      {children}
      {target && <SourceModal {...target} onClose={() => setTarget(null)} />}
    </SourceContext.Provider>
  )
}

function SourceModal({ chartId, file, line, onClose }: { chartId: string; file?: string; line?: number; onClose: () => void }) {
  const chart = useAsync(() => api.chart(chartId), [chartId])
  const [current, setCurrent] = useState<string | undefined>(file)
  const [highlight, setHighlight] = useState<number | undefined>(line)
  const path = current ?? chart.data?.chart.files[0]

  return (
    <Modal open title="Helm configuration" width={980} onClose={onClose}
      subtitle={chart.data ? `${chartLabel(chart.data.chart)} · revision ${chart.data.chart.revision}` : undefined}>
      {chart.loading && <Spinner />}
      {chart.error && <div className="banner banner-error">{chart.error}</div>}
      {chart.data && (
        <>
          <div className="file-tabs">
            {chart.data.chart.files.map((f) => (
              <button key={f} className={`btn btn-sm ${f === path ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => { setCurrent(f); setHighlight(undefined) }}>{f}</button>
            ))}
          </div>
          {path && <SourceFile chartId={chartId} path={path} line={highlight} />}
        </>
      )}
    </Modal>
  )
}

function SourceFile({ chartId, path, line }: { chartId: string; path: string; line?: number }) {
  const source = useAsync(() => api.source(chartId, path), [chartId, path])
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!source.data || !line || !ref.current) return
    const el = ref.current.querySelector<HTMLElement>(`[data-line="${line}"]`)
    if (el) ref.current.scrollTop = el.offsetTop - ref.current.clientHeight / 3
  }, [source.data, line])

  if (source.loading) return <Spinner />
  if (source.error) return <div className="banner banner-error">{source.error}</div>
  const masked = new Set(source.data!.maskedLines)
  return (
    <>
      {masked.size > 0 && (
        <div className="row small muted" style={{ marginBottom: 8 }}>
          <Badge tone="gray">🔒 {masked.size} sensitive value(s) hidden</Badge>
        </div>
      )}
      <div className="source" ref={ref}>
        {source.data!.lines.map((text, i) => (
          <div key={i} data-line={i + 1} className={`source-line ${line === i + 1 ? 'hl' : ''}`}>
            <span className="ln">{i + 1}</span>
            <span>{text || ' '}</span>
          </div>
        ))}
      </div>
    </>
  )
}
