import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { ChartRecord, Environment } from '../api/types'
import { appendFiles, filesFromDrop, filesFromInput } from '../lib/files'
import type { PickedFile } from '../lib/files'
import { ChartPicker } from './ChartPicker'
import { Button, Field, Modal, Segmented, useToast } from './ui'

export interface UploadDefaults {
  app?: string
  version?: string
  environment?: Environment
  supersedes?: string
}

export function FileDrop({ files, onFiles, hint }: { files: PickedFile[]; onFiles: (f: PickedFile[]) => void; hint: string }) {
  const [drag, setDrag] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    folderInput.current?.setAttribute('webkitdirectory', '')
    folderInput.current?.setAttribute('directory', '')
  }, [])

  return (
    <div className={`dropzone ${drag ? 'drag' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={() => setDrag(false)}
      onDrop={async (e) => { e.preventDefault(); setDrag(false); onFiles(await filesFromDrop(e.dataTransfer)) }}>
      <div style={{ fontSize: 26 }}>⎈</div>
      <div style={{ fontWeight: 500, margin: '4px 0' }}>Drop files or a folder here</div>
      <div className="small muted">{hint}</div>
      <div className="row" style={{ justifyContent: 'center', marginTop: 12 }}>
        <Button size="sm" onClick={() => fileInput.current?.click()}>Choose files</Button>
        <Button size="sm" onClick={() => folderInput.current?.click()}>Choose folder</Button>
      </div>
      <input ref={fileInput} type="file" multiple hidden accept=".yaml,.yml,.zip,.tgz,.gz,.tar"
        onChange={(e) => onFiles(filesFromInput(e.target.files))} />
      <input ref={folderInput} type="file" multiple hidden onChange={(e) => onFiles(filesFromInput(e.target.files))} />
      {files.length > 0 && (
        <div className="file-list">
          <div style={{ fontFamily: 'var(--font)', fontWeight: 500, color: 'var(--text)' }}>{files.length} file(s) selected</div>
          {files.slice(0, 40).map((f) => <div key={f.path}>{f.path}</div>)}
          {files.length > 40 && <div>…</div>}
        </div>
      )}
    </div>
  )
}

export function UploadChartDialog({ open, onClose, onUploaded, charts, defaults }: {
  open: boolean
  onClose: () => void
  onUploaded: (chart: ChartRecord) => void
  charts: ChartRecord[]
  defaults?: UploadDefaults
}) {
  const [files, setFiles] = useState<PickedFile[]>([])
  const [app, setApp] = useState('')
  const [version, setVersion] = useState('')
  const [environment, setEnvironment] = useState<Environment>('NON_PROD')
  const [revision, setRevision] = useState('Initial')
  const [notes, setNotes] = useState('')
  const [supersedes, setSupersedes] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  useEffect(() => {
    if (!open) return
    setFiles([])
    setApp(defaults?.app ?? '')
    setVersion(defaults?.version ?? '')
    setEnvironment(defaults?.environment ?? 'NON_PROD')
    setRevision(defaults?.supersedes ? 'Revised' : 'Initial')
    setNotes('')
    setSupersedes(defaults?.supersedes ?? '')
  }, [open, defaults])

  const submit = async () => {
    const form = new FormData()
    appendFiles(form, files)
    if (app) form.append('app', app)
    if (version) form.append('version', version)
    form.append('environment', environment)
    if (revision) form.append('revision', revision)
    if (notes) form.append('notes', notes)
    if (supersedes) form.append('supersedes', supersedes)
    setBusy(true)
    try {
      const chart = await api.uploadChart(form)
      toast(`${chart.app} ${chart.version} uploaded — ${chart.itemCount} configuration items recognised`)
      onUploaded(chart)
      onClose()
    } catch (e) {
      toast((e as Error).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={defaults?.supersedes ? 'Upload new revision' : 'Upload Helm chart'} width={680}
      subtitle="Values files, templates, rendered manifests, a chart folder, .zip or .tgz"
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={busy || files.length === 0} onClick={submit}>{busy ? 'Uploading…' : 'Upload'}</Button>
      </>}>
      <div className="stack-v">
        <FileDrop files={files} onFiles={setFiles} hint="Chart.yaml, values*.yaml and templates/ are recognised automatically" />
        <div className="grid grid-2">
          <Field label="Application" hint="Defaults to the name in Chart.yaml">
            <input className="input" value={app} onChange={(e) => setApp(e.target.value)} placeholder="payments-api" />
          </Field>
          <Field label="Version" hint="Defaults to appVersion in Chart.yaml">
            <input className="input" value={version} onChange={(e) => setVersion(e.target.value)} placeholder="3.1.3" />
          </Field>
        </div>
        <div className="grid grid-2">
          <Field label="Environment">
            <Segmented value={environment} onChange={setEnvironment} options={[
              { value: 'NON_PROD', label: 'NON-PROD' }, { value: 'PROD', label: 'PROD' }, { value: 'OTHER', label: 'Other' },
            ]} />
          </Field>
          <Field label="Revision label" hint="e.g. Initial, Revised, Validated">
            <input className="input" value={revision} onChange={(e) => setRevision(e.target.value)} />
          </Field>
        </div>
        <ChartPicker label="Supersedes (optional)" charts={charts} value={supersedes} onChange={setSupersedes} optional
          hint="Link this upload as a new revision of an earlier chart" />
        <Field label="Notes (optional)">
          <textarea className="textarea" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
      </div>
    </Modal>
  )
}
