import { useCallback, useEffect, useState } from 'react'
import type { SideInfo } from '../api/types'
import { navigate } from './router'

/** The label entered for a folder, or the name of the uploaded folder when no label was given. */
export function sideTitle(side: SideInfo): string {
  return side.label?.trim() || side.name
}

/** "LABEL\my-service\helm\values.yaml" — tells which folder's file is shown. */
export function sidePath(side: SideInfo, path: string): string {
  return [sideTitle(side), ...path.split('/').filter(Boolean)].join('\\')
}

/** Letter shown next to a folder (A, B, C); its colour is `side-tone-{index}` in CSS. */
export const sideLetter = (index: number) => String.fromCharCode(65 + index)

/** "0-2": key of the comparison of these folders. */
export const sidesKey = (sides: number[]) => sides.join('-')

/** "0,2": query parameter for these folders. */
export const sidesParam = (sides: number[]) => sides.join(',')

const visibleKey = (id: string) => `helm-compare:${id}:visible-sides`

function readVisible(id: string, count: number): number[] {
  const all = Array.from({ length: count }, (_, i) => i)
  try {
    const parsed = JSON.parse(localStorage.getItem(visibleKey(id)) ?? 'null') as unknown
    if (Array.isArray(parsed)) {
      const valid = [...new Set(parsed.filter((s): s is number => Number.isInteger(s) && s >= 0 && s < count))].sort()
      if (valid.length >= 2) return valid
    }
  } catch {
    // storage unavailable
  }
  return all
}

/**
 * The folders shown for a comparison. At least two stay visible. The choice is shared by every tab of the comparison
 * (chart, file, env variables) and follows changes made in another tab.
 */
export function useVisibleSides(id: string, count: number) {
  const [visible, setVisibleState] = useState(() => readVisible(id, count))

  useEffect(() => {
    setVisibleState(readVisible(id, count))
    const onStorage = (e: StorageEvent) => {
      if (e.key === visibleKey(id)) setVisibleState(readVisible(id, count))
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [id, count])

  const setVisible = useCallback((sides: number[]) => {
    const next = [...new Set(sides)].sort()
    if (next.length < 2) return
    setVisibleState(next)
    try {
      localStorage.setItem(visibleKey(id), JSON.stringify(next))
    } catch {
      // storage unavailable
    }
  }, [id])

  const toggle = useCallback((side: number) => {
    setVisibleState((current) => {
      const next = current.includes(side) ? current.filter((s) => s !== side) : [...current, side].sort()
      if (next.length < 2) return current
      try {
        localStorage.setItem(visibleKey(id), JSON.stringify(next))
      } catch {
        // storage unavailable
      }
      return next
    })
  }, [id])

  return { visible, setVisible, toggle }
}

/** Channel shared by a file compare tab and its "logical differences" tab. */
export const diffChannel = (id: string, path: string) => `helm-compare:diffs:${id}:${path}`

/** Closes a tab the app opened; a tab opened directly navigates to `fallback` instead. */
export function closeTab(fallback: string) {
  if (window.opener) window.close()
  else navigate(fallback)
}
