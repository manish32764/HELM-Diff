import { useEffect, useMemo, useRef, useState } from 'react'
import { api, uploadWithProgress } from '../api/client'
import type { FolderCompareInfo } from '../api/types'
import { FolderIcon } from '../components/FolderIcons'
import { SideTag } from '../components/Sides'
import { Button, Card, Spinner, useToast } from '../components/ui'
import { filesFromDrop, filesFromInput, formatBytes, isComparable } from '../lib/files'
import type { PickedFile } from '../lib/files'
import { formatDate } from '../lib/labels'
import { navigate } from '../lib/router'
import { sideLetter } from '../lib/sides'
import { useAsync } from '../lib/useAsync'

interface PickedFolder {
  root: string
  files: PickedFile[]
  subfolders: string[]
  totalBytes: number
}

interface Slot {
  folder: PickedFolder | null
  label: string
  secrets: File | null
}

const EMPTY_SLOT: Slot = { folder: null, label: '', secrets: null }

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
  const [slots, setSlots] = useState<Slot[]>([EMPTY_SLOT, EMPTY_SLOT, EMPTY_SLOT])
  const [progress, setProgress] = useState<number | null>(null)
  const history = useAsync(() => api.folderCompares(), [])
  const toast = useToast()

  const update = (index: number, patch: Partial<Slot>) => setSlots((all) => all.map((s, i) => (i === index ? { ...s, ...patch } : s)))
  const picked = slots.filter((s) => s.folder)

  const match = useMemo(() => {
    const folders = slots.map((s) => s.folder).filter((f): f is PickedFolder => !!f)
    if (folders.length < 2) return null
    const sets = folders.map((f) => new Set(f.subfolders))
    const all = [...new Set(folders.flatMap((f) => f.subfolders))]
    return {
      everywhere: all.filter((s) => sets.every((set) => set.has(s))).length,
      partial: all.filter((s) => !sets.every((set) => set.has(s))).length,
      files: folders.reduce((n, f) => n + f.files.length, 0),
      bytes: folders.reduce((n, f) => n + f.totalBytes, 0),
    }
  }, [slots])

  const compare = async () => {
    if (picked.length < 2) return
    const form = new FormData()
    picked.forEach((slot, k) => {
      slot.folder!.files.forEach(({ file, path }) => form.append(`side${k}`, file, path))
      if (slot.label) form.append(`label${k}`, slot.label)
      if (slot.secrets) form.append(`secrets${k}`, slot.secrets, slot.secrets.name)
    })
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
          <h1 className="page-title">Compare folders</h1>
          <div className="page-subtitle">
            Choose two or three parent folders, each with one sub-folder per microservice. Sub-folders with the same name are
            matched and every Helm file inside is compared — logically, the way Kubernetes sees it. Any folder can be hidden
            later to compare the other two.
          </div>
        </div>
      </header>

      <div className="pick-grid">
        {slots.map((slot, i) => (
          <FolderPickCard key={i} index={i} slot={slot} optional={i === 2} disabled={busy || (i === 2 && !slots[0].folder && !slots[1].folder)}
            onChange={(patch) => update(i, patch)} />
        ))}
      </div>

      <div className="pick-footer">
        {match ? (
          <div className="pick-match">
            <span><span className="dot dot-green" /><b>{match.everywhere}</b> sub-folders in every folder</span>
            <span><span className="dot dot-orange" /><b>{match.partial}</b> not in every folder</span>
            <span className="muted">{match.files} files · {formatBytes(match.bytes)}</span>
          </div>
        ) : (
          <span className="small muted">Choose at least two folders to compare.</span>
        )}
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
          <Button variant="primary" size="lg" disabled={picked.length < 2} onClick={compare}>
            Compare {picked.length === 3 ? 'three' : 'two'} folders
          </Button>
        )}
      </div>

      <Card title="Recent comparisons" flush>
        {history.loading ? <Spinner /> : !history.data?.length ? (
          <div className="empty">No comparisons yet.</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Folders</th><th>Microservice folders</th><th>Files</th><th>Compared</th><th /></tr></thead>
              <tbody>
                {history.data.map((h) => (
                  <tr key={h.id} className="clickable" onClick={() => navigate(`/folders/${h.id}`)}>
                    <td>
                      <div className="history-sides">
                        {(h.sides ?? []).map((side, i) => <SideTag key={i} index={i} side={side} />)}
                      </div>
                    </td>
                    <td className="small nowrap">
                      <span className="dot dot-green" /> {h.summary.identicalFolders + (h.summary.logicallySameFolders ?? 0)} identical ·{' '}
                      <span className="dot dot-red" /> {h.summary.differentFolders} differ ·{' '}
                      <span className="dot dot-orange" /> {h.summary.partialFolders ?? 0} not in all
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

function FolderPickCard({ index, slot, optional, disabled, onChange }: {
  index: number
  slot: Slot
  optional: boolean
  disabled: boolean
  onChange: (patch: Partial<Slot>) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const jsonInput = useRef<HTMLInputElement>(null)
  const [drag, setDrag] = useState(false)
  const toast = useToast()
  const { folder, label, secrets } = slot

  useEffect(() => {
    input.current?.setAttribute('webkitdirectory', '')
    input.current?.setAttribute('directory', '')
  }, [])

  const pickJson = async (file?: File) => {
    if (!file) return
    try {
      JSON.parse(await file.text())
      onChange({ secrets: file })
    } catch {
      toast(`${file.name} is not valid JSON`, 'error')
    }
  }

  const accept = (files: PickedFile[]) => {
    const f = toFolder(files)
    if (!f) toast('That folder does not contain any files to compare', 'error')
    onChange({ folder: f })
  }

  return (
    <div className={`pick-card side-tone-${index} ${drag ? 'drag' : ''} ${folder ? 'picked' : ''} ${optional && !folder ? 'optional' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={() => setDrag(false)}
      onDrop={async (e) => { e.preventDefault(); setDrag(false); if (!disabled) accept(await filesFromDrop(e.dataTransfer)) }}>
      <div className="pick-side">
        <span className="side-letter">{sideLetter(index)}</span>
        Folder {sideLetter(index)}{optional && <span className="pick-optional">optional</span>}
        {folder && (
          <button className="chip-x pick-clear" disabled={disabled} onClick={() => onChange({ folder: null, label: '', secrets: null })}
            aria-label="Remove folder" title="Remove folder">×</button>
        )}
      </div>
      <input ref={input} type="file" multiple hidden onChange={(e) => { accept(filesFromInput(e.target.files)); e.target.value = '' }} />
      {!folder ? (
        <div className="pick-empty">
          <FolderIcon tone="gray" />
          <div className="pick-title">{optional ? 'Add a third folder' : 'Choose a parent folder'}</div>
          <div className="small muted">{optional ? 'Compare three environments at once' : '…or drop it here'}</div>
          <Button variant={optional ? 'secondary' : 'primary'} disabled={disabled} onClick={() => input.current?.click()}>Choose folder</Button>
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
          <input className="input" placeholder={`Label (optional), e.g. PROD — shown instead of ${folder.root}`} value={label} disabled={disabled}
            onChange={(e) => onChange({ label: e.target.value })} />
          <div className="pick-secrets">
            <input ref={jsonInput} type="file" accept=".json,application/json" hidden
              onChange={(e) => { pickJson(e.target.files?.[0]); e.target.value = '' }} />
            <div className="pick-secrets-row">
              <span className="pick-secrets-label">AKeyless values JSON <span className="faint">(optional)</span></span>
              {secrets ? (
                <span className="chip mono">
                  {secrets.name}
                  <button className="chip-x" disabled={disabled} onClick={() => onChange({ secrets: null })} aria-label="Remove JSON" title="Remove">×</button>
                </span>
              ) : (
                <Button size="sm" disabled={disabled} onClick={() => jsonInput.current?.click()}>Choose JSON…</Button>
              )}
            </div>
            <div className="pick-hint">
              Only when this environment reads secrets from AKeyless — the value of each path: <code>{'{ "/Platform/…/API_KEY": "value" }'}</code>
            </div>
          </div>
          <div><Button size="sm" disabled={disabled} onClick={() => input.current?.click()}>Change folder</Button></div>
        </div>
      )}
    </div>
  )
}
