import type {
  AnalysisRecord, AnalysisSummary, ChartRecord, ConfigItem, EnvView, Expectation, FileView, FolderCompare, FolderCompareInfo,
  PortfolioRecord, SearchHit, SecretValuesInfo, SourceView,
} from './types'

/** Multipart upload with progress reporting (fetch cannot report upload progress). */
export function uploadWithProgress<T>(url: string, form: FormData, onProgress: (fraction: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText) as T)
        return
      }
      let message = `${xhr.status} ${xhr.statusText}`
      try {
        message = JSON.parse(xhr.responseText).message ?? message
      } catch {
        // not JSON
      }
      reject(new Error(message))
    }
    xhr.onerror = () => reject(new Error('The upload failed. Check that the backend is running.'))
    xhr.send(form)
  })
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      // not JSON
    }
    throw new Error(message)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

function json(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export interface PairSource { differenceSetId?: string; leftChartId?: string; rightChartId?: string }

export const api = {
  charts: (includePortfolio = false) => request<ChartRecord[]>(`/api/charts?includePortfolio=${includePortfolio}`),
  chart: (id: string) => request<{ chart: ChartRecord; items: ConfigItem[] }>(`/api/charts/${id}`),
  uploadChart: (form: FormData) => request<ChartRecord>('/api/charts', { method: 'POST', body: form }),
  deleteChart: (id: string) => request<void>(`/api/charts/${id}`, { method: 'DELETE' }),
  source: (id: string, path: string) =>
    request<SourceView>(`/api/charts/${id}/source?path=${encodeURIComponent(path)}`),

  analyses: () => request<AnalysisSummary[]>('/api/analyses'),
  analysis: (id: string) => request<AnalysisRecord>(`/api/analyses/${id}`),
  deleteAnalysis: (id: string) => request<void>(`/api/analyses/${id}`, { method: 'DELETE' }),
  pairwise: (body: { leftChartId: string; rightChartId: string; title?: string }) =>
    request<AnalysisRecord>('/api/analyses/pairwise', json('POST', body)),
  diffCompare: (body: { historical: PairSource; current: PairSource; title?: string }) =>
    request<AnalysisRecord>('/api/analyses/diff-compare', json('POST', body)),
  fourChart: (body: { a: string; b: string; c: string; d?: string; title?: string }) =>
    request<AnalysisRecord>('/api/analyses/four-chart', json('POST', body)),
  portfolioAnalysis: (body: { portfolioId: string; differenceSetId?: string; expectations?: Expectation[]; title?: string }) =>
    request<AnalysisRecord>('/api/analyses/portfolio', json('POST', body)),
  expectations: (differenceSetId: string) => request<Expectation[]>(`/api/analyses/${differenceSetId}/expectations`),
  review: (id: string, itemId: string, status: string, comment: string) =>
    request<AnalysisRecord>(`/api/analyses/${id}/reviews/${encodeURIComponent(itemId)}`, json('PUT', { status, comment })),
  rerun: (id: string, overrides: Record<string, string>) =>
    request<AnalysisRecord>(`/api/analyses/${id}/rerun`, json('POST', { overrides })),
  exportUrl: (id: string, format: string) => `/api/analyses/${id}/export?format=${format}`,

  portfolios: () => request<PortfolioRecord[]>('/api/portfolios'),
  uploadPortfolio: (form: FormData) => request<PortfolioRecord>('/api/portfolios', { method: 'POST', body: form }),
  deletePortfolio: (id: string) => request<void>(`/api/portfolios/${id}`, { method: 'DELETE' }),
  search: (portfolioId: string, differenceSetId: string, entryId: string) =>
    request<SearchHit[]>(`/api/portfolios/${portfolioId}/search?differenceSetId=${differenceSetId}&entryId=${entryId}`),

  seed: () => request<Record<string, unknown>>('/api/demo/seed', { method: 'POST' }),

  folderCompares: () => request<FolderCompareInfo[]>('/api/folder-compares'),
  folderCompare: (id: string) => request<FolderCompare>(`/api/folder-compares/${id}`),
  folderFile: (id: string, path: string) =>
    request<FileView>(`/api/folder-compares/${id}/file?path=${encodeURIComponent(path)}`),
  deleteFolderCompare: (id: string) => request<void>(`/api/folder-compares/${id}`, { method: 'DELETE' }),
  folderExportUrl: (id: string, format: string) => `/api/folder-compares/${id}/export?format=${format}`,
  folderEnv: (id: string, path: string, scope: 'FILE' | 'FOLDER') =>
    request<EnvView>(`/api/folder-compares/${id}/env?path=${encodeURIComponent(path)}&scope=${scope}`),
  folderEnvExportUrl: (id: string, path: string, scope: string, format: string, showSecrets = false) =>
    `/api/folder-compares/${id}/env/export?path=${encodeURIComponent(path)}&scope=${scope}&format=${format}&showSecrets=${showSecrets}`,
  uploadSecretValues: (id: string, side: 'left' | 'right' | 'both', files: File[], replace: boolean) => {
    const form = new FormData()
    files.forEach((f) => form.append('file', f))
    return request<SecretValuesInfo>(`/api/folder-compares/${id}/secret-values?side=${side}&replace=${replace}`, { method: 'POST', body: form })
  },
  clearSecretValues: (id: string, side: 'left' | 'right' | 'both') =>
    request<void>(`/api/folder-compares/${id}/secret-values?side=${side}`, { method: 'DELETE' }),
}
