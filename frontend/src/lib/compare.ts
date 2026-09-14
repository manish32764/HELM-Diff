import type { FolderNode, FolderStatus, SideInfo } from '../api/types'
import { sideTitle } from './sides'

export interface Counts { identical: number; logicallySame: number; differs: number; partial: number }

export interface Eval {
  status: FolderStatus
  reason: string
  /** Files: most logical differences between two shown folders · folders: total over their files. */
  differences: number
  counts: Counts
}

export const total = (c: Counts) => c.identical + c.logicallySame + c.differs + c.partial

/** A node is listed when it exists in at least one shown folder. */
export const isVisible = (n: FolderNode, sides: number[]) => sides.some((s) => n.states[s] !== 'MISSING')

/** "Only in PROD" / "Missing in UAT", or null when the node exists in every shown folder. Same wording as the backend. */
export function missingReason(exists: (side: number) => boolean, sides: number[], titles: string[]): string | null {
  const present = sides.filter(exists)
  const absent = sides.filter((s) => !exists(s))
  if (absent.length === 0) return null
  if (present.length === 1) return `Only in ${titles[present[0]]}`
  return `Missing in ${absent.map((s) => titles[s]).join(', ')}`
}

/**
 * Status of files and folders for the shown folders (mirrors FolderStatus.java). Results are cached per node, so
 * evaluating a whole tree stays linear.
 */
export function createEvaluator(sides: SideInfo[], shown: number[]) {
  const titles = sides.map(sideTitle)
  const cache = new Map<FolderNode, Eval>()

  const evaluate = (n: FolderNode): Eval => {
    const cached = cache.get(n)
    if (cached) return cached
    const result = n.dir ? folder(n) : file(n)
    cache.set(n, result)
    return result
  }

  const file = (n: FolderNode): Eval => {
    const missing = missingReason((s) => n.states[s] !== 'MISSING', shown, titles)
    let differences = 0
    let allIdentical = true
    let allSame = true
    for (let i = 0; i < shown.length; i++) {
      for (let j = i + 1; j < shown.length; j++) {
        const p = n.pairs?.[`${shown[i]}-${shown[j]}`]
        if (!p) continue
        differences = Math.max(differences, p.differences)
        if (p.status !== 'IDENTICAL') allIdentical = false
        if (p.status === 'DIFFERS') allSame = false
      }
    }
    if (missing) return { status: 'PARTIAL', reason: missing, differences, counts: { identical: 0, logicallySame: 0, differs: 0, partial: 1 } }
    if (allIdentical) return { status: 'IDENTICAL', reason: 'Identical', differences: 0, counts: { identical: 1, logicallySame: 0, differs: 0, partial: 0 } }
    if (allSame) {
      return { status: 'LOGICALLY_IDENTICAL', reason: 'Logically the same — only formatting or order differs', differences: 0,
        counts: { identical: 0, logicallySame: 1, differs: 0, partial: 0 } }
    }
    return { status: 'DIFFERS', reason: `${differences} logical difference${differences === 1 ? '' : 's'}`, differences,
      counts: { identical: 0, logicallySame: 0, differs: 1, partial: 0 } }
  }

  const folder = (n: FolderNode): Eval => {
    const counts: Counts = { identical: 0, logicallySame: 0, differs: 0, partial: 0 }
    let differences = 0
    for (const child of n.children ?? []) {
      if (!isVisible(child, shown)) continue
      const e = evaluate(child)
      counts.identical += e.counts.identical
      counts.logicallySame += e.counts.logicallySame
      counts.differs += e.counts.differs
      counts.partial += e.counts.partial
      differences += e.differences
    }
    const missing = missingReason((s) => n.states[s] !== 'MISSING', shown, titles)
    if (missing) return { status: 'PARTIAL', reason: missing, differences, counts }
    if (counts.differs + counts.partial + counts.logicallySame === 0) return { status: 'IDENTICAL', reason: 'All files are identical', differences, counts }
    if (counts.differs + counts.partial === 0) {
      return { status: 'LOGICALLY_IDENTICAL', reason: `${counts.logicallySame} file(s) formatted differently but logically the same`, differences, counts }
    }
    const parts: string[] = []
    if (counts.differs) parts.push(`${counts.differs} differ`)
    if (counts.partial) parts.push(`${counts.partial} not in every folder`)
    return { status: 'DIFFERS', reason: parts.join(' · '), differences, counts }
  }

  return { evaluate, titles }
}

export type Evaluator = ReturnType<typeof createEvaluator>

/** Tone used for dots, pills and row accents. */
export function statusTone(status: FolderStatus): 'green' | 'teal' | 'red' | 'orange' {
  switch (status) {
    case 'IDENTICAL': return 'green'
    case 'LOGICALLY_IDENTICAL': return 'teal'
    case 'DIFFERS': return 'red'
    default: return 'orange'
  }
}
