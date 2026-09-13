import { navigate } from './router'

/** The label entered for a side, or the name of the uploaded folder when no label was given. */
export function sideTitle(name: string, label?: string | null): string {
  return label?.trim() || name
}

/** "LABEL\my-service\helm\values.yaml" — tells which side's file is shown. */
export function sidePath(name: string, label: string | null | undefined, path: string): string {
  return [sideTitle(name, label), ...path.split('/').filter(Boolean)].join('\\')
}

/** Channel shared by a file compare tab and its "logical differences" tab. */
export const diffChannel = (id: string, path: string) => `helm-compare:diffs:${id}:${path}`

/** Closes a tab the app opened; a tab opened directly navigates to `fallback` instead. */
export function closeTab(fallback: string) {
  if (window.opener) window.close()
  else navigate(fallback)
}
