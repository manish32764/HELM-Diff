import type { AnalysisType, Category, ChartRecord, ChartRef, ConfigItem, Environment, ValueSource } from '../api/types'

export type Tone = 'green' | 'red' | 'orange' | 'blue' | 'purple' | 'teal' | 'gray'

const STATUS: Record<string, [string, Tone]> = {
  COMMON: ['Common', 'gray'],
  ADDED: ['Added', 'blue'],
  REMOVED: ['Removed', 'red'],
  CHANGED: ['Changed', 'orange'],

  CARRIED_FORWARD: ['Carried forward', 'green'],
  CHANGED_IMPLEMENTATION: ['Changed implementation', 'orange'],
  MISSING: ['Missing', 'red'],
  NEW_PROD_CHANGE: ['New PROD change', 'blue'],
  NO_LONGER_APPLICABLE: ['No longer applicable', 'purple'],
  ALREADY_IN_BASELINE: ['Consistent', 'green'],
  CONSISTENT: ['Consistent', 'green'],
  UNCHANGED: ['Unchanged', 'gray'],
  VERSION_CHANGE: ['Version change', 'teal'],
  REVIEW: ['Requires review', 'orange'],

  IMPLEMENTED: ['Implemented', 'green'],
  IMPLEMENTED_DIFFERENTLY: ['Implemented differently', 'orange'],
  NO_LONGER_PRESENT: ['No longer present', 'purple'],
  NEW_CHANGE: ['New change', 'blue'],
  UNEXPECTED: ['Unexpected', 'red'],

  ALREADY_SATISFIED: ['Already satisfied', 'green'],
  REQUIRES_CHANGE: ['Requires change', 'red'],
  DIFFERENT_IMPLEMENTATION: ['Different implementation', 'orange'],
  POTENTIALLY_IRRELEVANT: ['Potentially irrelevant', 'purple'],

  COMPLIANT: ['Compliant', 'green'],
  REQUIRES_CHANGES: ['Requires changes', 'red'],
  REQUIRES_REVIEW: ['Requires review', 'orange'],
  NOT_APPLICABLE: ['Not applicable', 'gray'],

  SAME: ['Same logical change', 'green'],
  SIMILAR: ['Similar change', 'teal'],
  DIFFERENT: ['Different change', 'orange'],
  UNDETERMINED: ['Unable to determine', 'gray'],
  ABSENT: ['Absent', 'gray'],

  OPEN: ['Needs review', 'orange'],
  ACCEPTED: ['Accepted', 'green'],
  NEEDS_CHANGE: ['Change required', 'red'],

  CONSISTENT_WITH_REVIEW: ['Consistent — review items', 'orange'],
  INCONSISTENT: ['Inconsistent', 'red'],
  PREPARATION: ['PROD preparation', 'blue'],
}

export function statusLabel(code?: string): string {
  if (!code) return ''
  return STATUS[code]?.[0] ?? humanize(code)
}

export function statusTone(code?: string): Tone {
  return (code && STATUS[code]?.[1]) || 'gray'
}

export const CATEGORY_LABEL: Record<Category, string> = {
  ENVIRONMENT: 'Environment Variables',
  SECRETS: 'Secrets',
  PROBES: 'Health Probes',
  TSC: 'TSC / Topology Spread',
  RESOURCES: 'Resources',
  VOLUMES: 'Volumes',
  SERVICES: 'Services & Networking',
  CONFIG: 'Configuration',
  WORKLOAD: 'Image & Workload',
  SCALING: 'Scaling',
  SECURITY_CONTEXT: 'Security Context',
  SCHEDULING: 'Scheduling',
  METADATA: 'Labels & Annotations',
  OTHER: 'Other',
}

export const CATEGORY_ORDER = Object.keys(CATEGORY_LABEL) as Category[]

export const CATEGORY_ICON: Record<Category, string> = {
  ENVIRONMENT: '𝑥', SECRETS: '🔒', PROBES: '♡', TSC: '⌖', RESOURCES: '◔', VOLUMES: '▤', SERVICES: '⇄',
  CONFIG: '⚙', WORKLOAD: '▣', SCALING: '⇅', SECURITY_CONTEXT: '⛨', SCHEDULING: '◷', METADATA: '#', OTHER: '…',
}

export function envLabel(env?: Environment): string {
  return env === 'NON_PROD' ? 'NON-PROD' : env === 'PROD' ? 'PROD' : 'Other'
}

export function envTone(env?: Environment): Tone {
  return env === 'PROD' ? 'purple' : env === 'NON_PROD' ? 'teal' : 'gray'
}

export const SOURCE_LABEL: Record<ValueSource, string> = {
  LITERAL: 'Plain text', EMPTY: 'Empty', TEMPLATE: 'Helm template', SECRET_REF: 'K8s Secret', AKEYLESS: 'AKeyless',
  EXTERNAL_SECRET: 'External secret', VAULT: 'Vault', CONFIGMAP_REF: 'ConfigMap', FIELD_REF: 'Field ref', BLOCK: 'Block',
}

export function sourceTone(item?: ConfigItem): Tone {
  if (!item) return 'gray'
  switch (item.source) {
    case 'AKEYLESS': return 'green'
    case 'SECRET_REF': case 'EXTERNAL_SECRET': case 'VAULT': return 'teal'
    case 'TEMPLATE': return 'purple'
    case 'CONFIGMAP_REF': case 'FIELD_REF': return 'blue'
    case 'LITERAL': return item.sensitive ? 'red' : 'gray'
    default: return 'gray'
  }
}

export const TYPE_LABEL: Record<AnalysisType, string> = {
  PAIRWISE: 'Two-chart diff',
  DIFF_COMPARE: 'Two-diff comparison',
  FOUR_CHART: 'Four-chart analysis',
  PORTFOLIO: 'Portfolio analysis',
}

export const TYPE_ICON: Record<AnalysisType, string> = {
  PAIRWISE: '⇆', DIFF_COMPARE: '⧉', FOUR_CHART: '⊞', PORTFOLIO: '▦',
}

export function chartLabel(c?: ChartRecord | ChartRef): string {
  if (!c) return ''
  return `${c.app} ${c.version} ${envLabel(c.environment)}`
}

export function humanize(code: string): string {
  const s = code.replace(/_/g, ' ').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}

export function formatDate(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}
