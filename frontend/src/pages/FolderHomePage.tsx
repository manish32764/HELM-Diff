import { useEffect, useMemo, useRef, useState } from 'react'
import { api, uploadWithProgress } from '../api/client'
import type { FolderCompareInfo } from '../api/types'
import { FolderIcon } from '../components/FolderIcons'
import { Button, Card, Spinner, useToast } from '../components/ui'
import { filesFromDrop, filesFromInput, formatBytes, isComparable } from '../lib/files'
import type { PickedFile } from '../lib/files'
import { formatDate } from '../lib/labels'
import { navigate } from '../lib/router'
import { useAsync } from '../lib/useAsync'

interface PickedFolder {
  root: string
  files: PickedFile[]
  subfolders: string[]
  totalBytes: number
}

function toFolder(picked: PickedFile[]): PickedFolder | null {
  const files = picked.filter(isComparable)
  if (files.length === 0) return null
  const first = files[0].path
  const root = first.includes('/') ? first.slice(0, first.indexOf('/')) : ''
  const shared = root !== '' && files.every((f) => f.path.startsWith(`${root}/`))
  const relative = (p: string) => (shared ? p.slice(root.length + 1) : p)
  const subfolders = [...new Set(files.map((f) => relative(f.path)).filter((p) => p.includes('/')).map((p) => p.slice(0, p.indexOf('/'))))]
    .sort((a, b) => a.localeCompare(b))
  return {
    root: shared ? root : 'Selected files',
    files,
    subfolders,
    totalBytes: files.reduce((s, f) => s + f.file.size, 0),
  }
}

export function FolderHomePage() {
  const [left, setLeft] = useState<PickedFolder | null>(null)
  const [right, setRight] = useState<PickedFolder | null>(null)
  const [leftLabel, setLeftLabel] = useState('')
  const [rightLabel, setRightLabel] = useState('')
  const [progress, setProgress] = useState<number | null>(null)
  const history = useAsync(() => api.folderCompares(), [])
  const toast = useToast()

  const match = useMemo(() => {
    if (!left || !right) return null
    const r = new Set(right.subfolders)
    const l = new Set(left.subfolders)
    return {
      matched: left.subfolders.filter((s) => r.has(s)),
      leftOnly: left.subfolders.filter((s) => !r.has(s)),
      rightOnly: right.subfolders.filter((s) => !l.has(s)),
    }
  }, [left, right])

  const compare = async () => {
    if (!left || !right) return
    const form = new FormData()
    left.files.forEach(({ file, path }) => form.append('left', file, path))
    right.files.forEach(({ file, path }) => form.append('right', file, path))
    if (leftLabel) form.append('leftLabel', leftLabel)
    if (rightLabel) form.append('rightLabel', rightLabel)
    setProgress(0)
    try {
      const info = await uploadWithProgress<FolderCompareInfo>('/api/folder-compares', form, setProgress)
      navigate(`/folders/${info.id}`)
    } catch (e) {
      toast((e as Error).message, 'error')
      setProgress(null)
    }
  }

  const remove = async (id: string) => {
    if (!window.confirm('Delete this comparison?')) return
    await api.deleteFolderCompare(id)
    history.reload()
  }

  const busy = progress !== null

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <div className="eyebrow">Helm chart comparison</div>
          <h1 className="page-title">Compare two folders</h1>
          <div className="page-subtitle">
            Choose two parent folders. Each should contain one sub-folder per microservice. Sub-folders with the same name are
            matched and every Helm file inside is compared — logically, the way Kubernetes sees it.
          </div>
        </div>
      </header>

      <div className="pick-grid">
        <FolderPickCard side="Left" folder={left} onPick={setLeft} label={leftLabel} onLabel={setLeftLabel} disabled={busy} />
        <div className="pick-vs">↔</div>
        <FolderPickCard side="Right" folder={right} onPick={setRight} label={rightLabel} onLabel={setRightLabel} disabled={busy} />
      </div>

      {match && (
        <div className="summary-strip">
          <span className="strip-item"><span className="dot dot-green" /><b>{match.matched.length}</b> matching sub-folders</span>
          <span className="strip-item"><span className="dot dot-orange" /><b>{match.leftOnly.length}</b> only in {left!.root}</span>
          <span className="strip-item"><span className="dot dot-orange" /><b>{match.rightOnly.length}</b> only in {right!.root}</span>
          <span className="strip-item muted">{left!.files.length + right!.files.length} files · {formatBytes(left!.totalBytes + right!.totalBytes)}</span>
        </div>
      )}

      <div className="row" style={{ justifyContent: 'center', margin: '8px 0 28px' }}>
        {busy ? (
          <div className="upload-progress">
            <div className="bar-track" style={{ height: 8 }}>
              <div className="bar-fill" style={{ width: `${Math.round((progress ?? 0) * 100)}%` }} />
            </div>
            <div className="small muted" style={{ marginTop: 8, textAlign: 'center' }}>
              {progress! < 1 ? `Uploading ${Math.round(progress! * 100)}%…` : 'Comparing files…'}
            </div>
          </div>
        ) : (
          <Button variant="primary" size="lg" disabled={!left || !right} onClick={compare}>Compare folders</Button>
        )}
      </div>

      <Card title="Recent comparisons" flush>
        {history.loading ? <Spinner /> : !history.data?.length ? (
          <div className="empty">No comparisons yet.</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Left</th><th>Right</th><th>Sub-folders</th><th>Files</th><th>Compared</th><th /></tr></thead>
              <tbody>
                {history.data.map((h) => (
                  <tr key={h.id} className="clickable" onClick={() => navigate(`/folders/${h.id}`)}>
                    <td><span className="mono">{h.leftName}</span>{h.leftLabel && <span className="faint small"> · {h.leftLabel}</span>}</td>
                    <td><span className="mono">{h.rightName}</span>{h.rightLabel && <span className="faint small"> · {h.rightLabel}</span>}</td>
                    <td className="small">
                      <span className="dot dot-green" /> {h.summary.identicalFolders} identical ·{' '}
                      <span className="dot dot-red" /> {h.summary.differentFolders} differ ·{' '}
                      <span className="dot dot-orange" /> {h.summary.leftOnlyFolders + h.summary.rightOnlyFolders} one side only
                    </td>
                    <td className="small muted">{h.summary.files}</td>
                    <td className="small faint nowrap">{formatDate(h.createdAt)}</td>
                    <td><Button size="sm" variant="danger" onClick={(e) => { e.stopPropagation(); remove(h.id) }}>Delete</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

function FolderPickCard({ side, folder, onPick, label, onLabel, disabled }: {
  side: 'Left' | 'Right'
  folder: PickedFolder | null
  onPick: (f: PickedFolder | null) => void
  label: string
  onLabel: (v: string) => void
  disabled: boolean
}) {
  const input = useRef<HTMLInputElement>(null)
  const [drag, setDrag] = useState(false)
  const toast = useToast()

  useEffect(() => {
    input.current?.setAttribute('webkitdirectory', '')
    input.current?.setAttribute('directory', '')
  }, [])

  const accept = (files: PickedFile[]) => {
    const f = toFolder(files)
    if (!f) toast('That folder does not contain any files to compare', 'error')
    onPick(f)
  }

  return (
    <div className={`pick-card ${drag ? 'drag' : ''} ${folder ? 'picked' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={() => setDrag(false)}
      onDrop={async (e) => { e.preventDefault(); setDrag(false); if (!disabled) accept(await filesFromDrop(e.dataTransfer)) }}>
      <div className="pick-side">{side} folder</div>
      <input ref={input} type="file" multiple hidden onChange={(e) => { accept(filesFromInput(e.target.files)); e.target.value = '' }} />
      {!folder ? (
        <div className="pick-empty">
          <FolderIcon tone="gray" />
          <div className="pick-title">Choose the {side.toLowerCase()} parent folder</div>
          <div className="small muted">…or drop it here</div>
          <Button variant="primary" disabled={disabled} onClick={() => input.current?.click()}>Choose folder</Button>
        </div>
      ) : (
        <div className="stack-v" style={{ gap: 12 }}>
          <div className="row" style={{ flexWrap: 'nowrap' }}>
            <FolderIcon tone="yellow" />
            <span className="pick-name" title={folder.root}>{folder.root}</span>
          </div>
          <div className="small muted">
            {folder.subfolders.length} sub-folder(s) · {folder.files.length} file(s) · {formatBytes(folder.totalBytes)}
          </div>
          <div className="pick-subfolders">
            {folder.subfolders.slice(0, 60).map((s) => <span key={s} className="chip mono">{s}</span>)}
            {folder.subfolders.length > 60 && <span className="chip">+{folder.subfolders.length - 60} more</span>}
          </div>
          <input className="input" placeholder="Label (optional), e.g. v3.0.4" value={label} disabled={disabled}
            onChange={(e) => onLabel(e.target.value)} />
          <div><Button size="sm" disabled={disabled} onClick={() => input.current?.click()}>Change folder</Button></div>
        </div>
      )}
    </div>
  )
}
