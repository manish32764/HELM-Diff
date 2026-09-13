export interface PickedFile {
  file: File
  path: string
}

export function filesFromInput(list: FileList | null): PickedFile[] {
  return Array.from(list ?? []).map((file) => ({ file, path: file.webkitRelativePath || file.name }))
}

/** Reads dropped files and folders (recursively), keeping relative paths. */
export async function filesFromDrop(dt: DataTransfer): Promise<PickedFile[]> {
  const entries = Array.from(dt.items)
    .map((item) => item.webkitGetAsEntry?.())
    .filter((e): e is FileSystemEntry => !!e)
  if (entries.length === 0) return Array.from(dt.files).map((file) => ({ file, path: file.name }))

  const out: PickedFile[] = []
  const walk = async (entry: FileSystemEntry, prefix: string): Promise<void> => {
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject))
      out.push({ file, path: prefix + file.name })
    } else if (entry.isDirectory) {
      const reader = (entry as FileSystemDirectoryEntry).createReader()
      let batch: FileSystemEntry[]
      do {
        batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
        for (const child of batch) await walk(child, `${prefix}${entry.name}/`)
      } while (batch.length > 0)
    }
  }
  for (const entry of entries) await walk(entry, '')
  return out
}

const SKIPPED_DIRS = new Set(['node_modules', 'target', '__MACOSX'])
const SKIPPED_FILES = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini'])
const MAX_FILE_BYTES = 10 * 1024 * 1024

/** Skips version-control folders, build output and very large files. */
export function isComparable({ file, path }: PickedFile): boolean {
  const segments = path.split('/')
  const dirs = segments.slice(0, -1)
  if (dirs.some((d) => d.startsWith('.') || SKIPPED_DIRS.has(d))) return false
  if (SKIPPED_FILES.has(segments[segments.length - 1])) return false
  return file.size <= MAX_FILE_BYTES
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function appendFiles(form: FormData, files: PickedFile[]) {
  files.forEach(({ file, path }) => form.append('files', file, path))
}
