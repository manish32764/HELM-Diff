import { memo, useMemo } from 'react'
import type { ReactNode, RefObject, UIEvent } from 'react'
import { highlight } from '../lib/highlight'
import type { Mode } from '../lib/highlight'

export interface LineMark {
  kind: 'added' | 'removed' | 'changed'
  ids: string[]
  selected: boolean
}

/** Scrolls a code pane so the given line sits in the upper third. */
export function scrollToLine(pane: HTMLDivElement | null, line: number) {
  if (!pane || line <= 0) return
  const el = pane.querySelector<HTMLElement>(`[data-line="${line}"]`)
  if (el) pane.scrollTop = Math.max(0, el.offsetTop - pane.clientHeight / 3)
}

export const CodePane = memo(function CodePane({ lines, mode, marks, placeholder, paneRef, onScroll, onLineClick }: {
  lines?: string[]
  mode: Mode
  marks: Map<number, LineMark>
  placeholder?: ReactNode
  paneRef: RefObject<HTMLDivElement | null>
  onScroll?: (e: UIEvent<HTMLDivElement>) => void
  onLineClick?: (ids: string[]) => void
}) {
  const tokens = useMemo(() => lines?.map((l) => highlight(l, mode)), [lines, mode])

  return (
    <div className="code-pane" ref={paneRef} onScroll={onScroll}>
      {!lines || placeholder ? (
        <div className="code-placeholder">{placeholder}</div>
      ) : (
        <div className="code-inner">
          {tokens!.map((lineTokens, i) => {
            const n = i + 1
            const mark = marks.get(n)
            return (
              <div key={n} data-line={n}
                className={`code-line${mark ? ` hl-${mark.kind}${mark.selected ? ' sel' : ''}` : ''}`}
                onClick={mark && onLineClick ? () => onLineClick(mark.ids) : undefined}>
                <span className="code-ln">{n}</span>
                <span className="code-text">
                  {lineTokens.length === 0 ? ' ' : lineTokens.map((t, k) => (
                    t.cls ? <span key={k} className={t.cls}>{t.text}</span> : t.text
                  ))}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
})
