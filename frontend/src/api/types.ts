export type Category =
  | 'ENVIRONMENT' | 'SECRETS' | 'PROBES' | 'TSC' | 'RESOURCES' | 'VOLUMES' | 'SERVICES' | 'CONFIG'
  | 'WORKLOAD' | 'SCALING' | 'SECURITY_CONTEXT' | 'SCHEDULING' | 'METADATA' | 'OTHER'

export type ValueSource =
  | 'LITERAL' | 'EMPTY' | 'TEMPLATE' | 'SECRET_REF' | 'AKEYLESS' | 'EXTERNAL_SECRET' | 'VAULT'
  | 'CONFIGMAP_REF' | 'FIELD_REF' | 'BLOCK'

export type Environment = 'NON_PROD' | 'PROD' | 'OTHER'
export type AnalysisType = 'PAIRWISE' | 'DIFF_COMPARE' | 'FOUR_CHART' | 'PORTFOLIO'

export interface Location { file: string; line: number; path: string }

export interface ConfigItem {
  key: string
  family: string
  subject: string
  category: Category
  source: ValueSource
  sensitive: boolean
  block: boolean
  enabled: boolean
  display: string
  canonical: string
  fields: Record<string, string>
  reference?: string
  scope?: string
  locations: Location[]
}

export interface ChartRecord {
  id: string
  app: string
  version: string
  environment: Environment
  revision: string
  notes?: string
  originalName?: string
  sha256: string
  supersedes?: string
  portfolioId?: string
  uploadedAt: string
  chartName?: string
  chartVersion?: string
  appVersion?: string
  itemCount: number
  files: string[]
  warnings: string[]
  maskedLines: Record<string, number[]>
}

export interface ChartRef {
  role: string
  chartId: string
  app: string
  version: string
  environment: Environment
  revision: string
  originalName?: string
  sha256?: string
  uploadedAt?: string
}

export interface FieldChange { path: string; left?: string; right?: string; kind: 'ADDED' | 'REMOVED' | 'CHANGED' }

export type DiffStatus = 'COMMON' | 'ADDED' | 'REMOVED' | 'CHANGED'

export interface DiffEntry {
  id: string
  key: string
  subject: string
  family: string
  category: Category
  status: DiffStatus
  matchType: string
  nameSimilarity?: number
  left?: ConfigItem
  right?: ConfigItem
  fieldChanges: FieldChange[]
  intent: string
  transition: string
  summary: string
  security: boolean
  prodSpecific: boolean
  review: boolean
  notes: string[]
}

export interface DiffResult {
  left: ChartRef
  right: ChartRef
  summary: {
    totalItems: number
    differences: number
    common: number
    added: number
    removed: number
    changed: number
    security: number
    prodSpecific: number
    review: number
    differencesByCategory: Record<string, number>
  }
  entries: DiffEntry[]
}

export interface CorrelatedChange {
  id: string
  key: string
  subject: string
  category: Category
  classification: string
  similarity: string
  validation: string
  historical?: DiffEntry
  current?: DiffEntry
  stateA?: ConfigItem
  stateB?: ConfigItem
  stateC?: ConfigItem
  stateD?: ConfigItem
  implementationDifferences: FieldChange[]
  explanation: string
  security: boolean
  review: boolean
}

export interface Assessment {
  id: string
  key: string
  subject: string
  category: Category
  status: string
  historical: DiffEntry
  candidate?: ConfigItem
  recommendation: string
  security: boolean
}

export interface Cell { present: boolean; label: string; detail: string; source?: ValueSource }

export interface MatrixRow {
  key: string
  subject: string
  category: Category
  cells: Cell[]
  assessment: string
  allEqual: boolean
  referenceId?: string
}

export interface VersionSummary {
  historicalChanges: number
  currentChanges: number
  carriedForward: number
  changedImplementation: number
  missing: number
  newProdChanges: number
  noLongerApplicable: number
  alreadyInBaseline: number
  security: number
  review: number
  npAlreadySatisfied: number
  npRequiresChange: number
  npDifferentImplementation: number
  npPotentiallyIrrelevant: number
  npReview: number
  implemented: number
  implementedDifferently: number
  validationMissing: number
  noLongerPresent: number
  newChanges: number
  unexpected: number
  verdict: string
  verdictMessage: string
  score: number
}

export interface VersionAnalysisResult {
  a: ChartRef
  b: ChartRef
  c: ChartRef
  d?: ChartRef
  validation: boolean
  historicalDiff: DiffResult
  currentDiff?: DiffResult
  correlations: CorrelatedChange[]
  nonProdAssessment: Assessment[]
  matrix: MatrixRow[]
  summary: VersionSummary
}

export interface Expectation {
  id: string
  title: string
  category: Category
  kind: string
  key?: string
  subject?: string
  family?: string
  targetSourceClass?: string
  expectedCanonical?: string
  originCanonical?: string
  expectedDisplay?: string
  applicability: 'ALWAYS' | 'IF_PRESENT'
  selected: boolean
  rationale?: string
}

export interface ExpectationResult {
  expectationId: string
  status: string
  match: string
  detail: string
  found?: ConfigItem
}

export interface AppResult {
  app: string
  nonProdChartId?: string
  prodChartId?: string
  evaluatedOn: string
  status: string
  compliant: number
  requiresChange: number
  review: number
  notApplicable: number
  results: ExpectationResult[]
  unexpectedChanges: DiffEntry[]
  unusualChanges: DiffEntry[]
  otherProdDifferences: number
}

export interface PortfolioResult {
  portfolioId: string
  portfolioName: string
  portfolioVersion: string
  differenceSetId?: string
  differenceSetTitle?: string
  expectations: Expectation[]
  apps: AppResult[]
  expectationSummaries: { expectationId: string; title: string; compliant: number; requiresChange: number; notApplicable: number; review: number }[]
  commonChanges: { signature: string; label: string; category: Category; count: number; apps: string[] }[]
  summary: {
    chartsAnalyzed: number
    apps: number
    compliant: number
    requireChanges: number
    requireReview: number
    withUnexpectedChanges: number
    withUnusualConfiguration: number
  }
}

export interface PortfolioRecord {
  id: string
  name: string
  version: string
  uploadedAt: string
  originalName?: string
  apps: { app: string; nonProdChartId?: string; prodChartId?: string; otherChartId?: string }[]
  warnings: string[]
}

export interface ReviewMark { status: string; comment?: string; updatedAt: string }
export interface AuditEvent { at: string; action: string; detail: string }

export interface AnalysisRecord {
  id: string
  type: AnalysisType
  title: string
  createdAt: string
  rerunOf?: string
  charts: ChartRef[]
  inputs: Record<string, unknown>
  headline: Record<string, number>
  pairwise?: DiffResult
  version?: VersionAnalysisResult
  portfolio?: PortfolioResult
  reviews: Record<string, ReviewMark>
  audit: AuditEvent[]
}

export interface AnalysisSummary {
  id: string
  type: AnalysisType
  title: string
  createdAt: string
  rerunOf?: string
  charts: ChartRef[]
  headline: Record<string, number>
  reviewMarks: number
}

export interface SourceView { path: string; lines: string[]; maskedLines: number[] }

export interface SearchHit { app: string; evaluatedOn: string; chartId: string; result: ExpectationResult }

// ───────────── folder comparison ─────────────

export type FolderStatus = 'IDENTICAL' | 'LOGICALLY_IDENTICAL' | 'DIFFERS' | 'LEFT_ONLY' | 'RIGHT_ONLY'
export type SideState = 'PRESENT' | 'EMPTY' | 'MISSING'

export interface FolderNode {
  name: string
  path: string
  dir: boolean
  status: FolderStatus
  leftState: SideState
  rightState: SideState
  reason?: string
  differences: number
  leftSize: number
  rightSize: number
  identical: number
  logicallySame: number
  differs: number
  leftOnly: number
  rightOnly: number
  children?: FolderNode[]
}

export interface FolderSummary {
  leftFolders: number
  rightFolders: number
  matchedFolders: number
  leftOnlyFolders: number
  rightOnlyFolders: number
  identicalFolders: number
  differentFolders: number
  files: number
  identicalFiles: number
  logicallySameFiles: number
  differentFiles: number
  leftOnlyFiles: number
  rightOnlyFiles: number
}

export interface FolderCompareInfo {
  id: string
  createdAt: string
  leftName: string
  rightName: string
  leftLabel?: string
  rightLabel?: string
  summary: FolderSummary
}

export interface FolderCompare extends FolderCompareInfo {
  root: FolderNode
}

export interface LogicalDiff {
  id: string
  kind: 'ADDED' | 'REMOVED' | 'CHANGED'
  path: string
  description: string
  left?: string
  right?: string
  leftStart: number
  leftEnd: number
  rightStart: number
  rightEnd: number
}

export interface FileView {
  path: string
  name: string
  status: FolderStatus
  reason: string
  leftState: SideState
  rightState: SideState
  binary: boolean
  leftLines?: string[]
  rightLines?: string[]
  differences: LogicalDiff[]
  leftName: string
  rightName: string
  leftLabel?: string
  rightLabel?: string
}
