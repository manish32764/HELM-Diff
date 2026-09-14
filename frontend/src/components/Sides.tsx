import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { FolderStatus, SideInfo } from '../api/types'
import { statusTone } from '../lib/compare'
import { sideLetter, sideTitle } from '../lib/sides'

/** Coloured letter + title of a folder (A · NONPROD). */
export function SideTag({ index, side, sub }: { index: number; side: SideInfo; sub?: ReactNode }) {
  return (
    <span className={`side-tag side-tone-${index}`} title={side.label ? `${sideTitle(side)} (${side.name})` : side.name}>
      <span className="side-letter">{sideLetter(index)}</span>
      <span className="side-tag-title">{sideTitle(side)}</span>
      {sub && <span className="side-tag-sub">{sub}</span>}
    </span>
  )
}

/**
 * One card per folder. With three folders each card has a Shown / Hidden switch; at least two folders stay shown.
 * `detail` adds a line such as "42 folders".
 */
export function SideCards({ sides, visible, onToggle, detail, compact }: {
  sides: SideInfo[]
  visible: number[]
  onToggle: (side: number) => void
  detail?: (side: number) => ReactNode
  compact?: boolean
}) {
  const canHide = sides.length > 2
  return (
    <div className={`side-cards ${compact ? 'compact' : ''}`} style={{ gridTemplateColumns: `repeat(${sides.length}, minmax(0, 1fr))` }}>
      {sides.map((side, i) => {
        const shown = visible.includes(i)
        const locked = shown && visible.length <= 2
        return (
          <div key={i} className={`side-card side-tone-${i} ${shown ? '' : 'off'}`}>
            <span className="side-letter lg">{sideLetter(i)}</span>
            <div className="side-card-text">
              <div className="side-card-title" title={sideTitle(side)}>{sideTitle(side)}</div>
              <div className="side-card-sub">
                {side.label && <span className="mono" title={side.name}>{side.name}</span>}
                {side.label && detail && <span className="faint"> · </span>}
                {detail?.(i)}
              </div>
            </div>
            {canHide && (
              <button className={`side-eye ${shown ? 'on' : ''}`} disabled={locked} aria-pressed={shown} onClick={() => onToggle(i)}
                title={locked ? 'At least two folders stay in the comparison' : shown ? 'Hide this folder from the comparison' : 'Show this folder again'}>
                {shown ? <EyeIcon /> : <EyeOffIcon />}
                <span>{shown ? 'Shown' : 'Hidden'}</span>
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}

const STATUS_TEXT: Record<FolderStatus, string> = {
  IDENTICAL: 'Identical',
  LOGICALLY_IDENTICAL: 'Logically same',
  DIFFERS: 'Differs',
  PARTIAL: 'Not in all',
}

export function StatusPill({ status, text, title }: { status: FolderStatus; text?: string; title?: string }) {
  return (
    <span className={`pill pill-${statusTone(status)}`} title={title}>
      <span className="pill-dot" />
      <span className="pill-text">{text ?? STATUS_TEXT[status]}</span>
    </span>
  )
}

/**
 * Horizontal scrolling for one column while the page scrolls vertically: the column's own scrollbar is hidden and a
 * synchronised copy sticks to the bottom of the window, so it is reachable without scrolling to the end of the list.
 */
export function HScroll({ children, className = '' }: { children: ReactNode; className?: string }) {
  const inner = useRef<HTMLDivElement>(null)
  const bar = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ scroll: 0, client: 0 })

  useEffect(() => {
    const el = inner.current
    if (!el) return
    const measure = () => setSize((s) => (s.scroll === el.scrollWidth && s.client === el.clientWidth ? s : { scroll: el.scrollWidth, client: el.clientWidth }))
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    if (el.firstElementChild) observer.observe(el.firstElementChild)
    measure()
    return () => observer.disconnect()
  }, [])

  const overflow = size.scroll > size.client + 1
  return (
    <div className={`hscroll ${className}`}>
      <div className="hscroll-inner" ref={inner}
        onScroll={(e) => { if (bar.current && bar.current.scrollLeft !== e.currentTarget.scrollLeft) bar.current.scrollLeft = e.currentTarget.scrollLeft }}>
        {children}
      </div>
      {overflow && (
        <div className="hscroll-bar" ref={bar}
          onScroll={(e) => { if (inner.current && inner.current.scrollLeft !== e.currentTarget.scrollLeft) inner.current.scrollLeft = e.currentTarget.scrollLeft }}>
          <div style={{ width: size.scroll, height: 1 }} />
        </div>
      )}
    </div>
  )
}

function EyeIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z" fill="none" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="8" cy="8" r="2.1" fill="currentColor" />
    </svg>
  )
}

function EyeOffIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z" fill="none" stroke="currentColor" strokeWidth="1.3" />
      <path d="M2.5 13.5l11-11" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}
