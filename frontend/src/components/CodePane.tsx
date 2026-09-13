import { memo, useMemo } from 'react'
import type { ReactNode, RefObject, UIEvent } from 'react'
import { highlight } from '../lib/highlight'
import type { Mode } from '../lib/highlight'

export interface LineMark {
  /** `anchor`: the block is missing on this side and would appear after this line. */
  kind: 'added' | 'removed' | 'changed' | 'anchor'
  ids: string[]
  selected: boolean
}

/**
 * Scrolls every pane so its block starts at the same distance from the top: the two sides of a difference sit
 * side by side, centred when the tallest block fits, otherwise with its first lines near the top.
 */
export function alignBlocks(targets: { pane: HTMLDivElement | null; start: number; end: number }[]) {
  const blocks = targets.flatMap(({ pane, start, end }) => {
    const first = start > 0 ? pane?.querySelector<HTMLElement>(`[data-line="${start}"]`) : null
    if (!pane || !first) return []
    const last = pane.querySelector<HTMLElement>(`[data-line="${Math.max(start, end)}"]`) ?? first
    return [{ pane, top: first.offsetTop, height: last.offsetTop + last.offsetHeight - first.offsetTop }]
  })
  if (blocks.length === 0) return
  const view = Math.min(...blocks.map((b) => b.pane.clientHeight))
  const tallest = Math.max(...blocks.map((b) => b.height))
  const wanted = Math.max(32, Math.min(view / 3, (view - tallest) / 2))
  // a block near the top of its file cannot scroll further down: use the offset every side can reach
  const offset = Math.min(wanted, ...blocks.map((b) => b.top))
  for (const b of blocks) b.pane.scrollTop = b.top - offset
}

export const CodePane = memo(function CodePane({ lines, mode, marks, placeholder, paneRef, onScroll, onActivate, onLineClick }: {
  lines?: string[]
  mode: Mode
  marks: Map<number, LineMark>
  placeholder?: ReactNode
  paneRef: RefObject<HTMLDivElement | null>
  onScroll?: (e: UIEvent<HTMLDivElement>) => void
  /** Called when the user starts interacting with this pane (pointer, wheel, touch). */
  onActivate?: () => void
  onLineClick?: (ids: string[]) => void
}) {
  const tokens = useMemo(() => lines?.map((l) => highlight(l, mode)), [lines, mode])

  return (
    <div className="code-pane" ref={paneRef} onScroll={onScroll}
      onMouseEnter={onActivate} onWheel={onActivate} onTouchStart={onActivate} onFocus={onActivate} tabIndex={-1}>
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
