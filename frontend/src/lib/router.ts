import { useEffect, useState } from 'react'

export interface Route {
  path: string
  segments: string[]
  query: URLSearchParams
}

function parse(hash: string): Route {
  const raw = hash.replace(/^#/, '') || '/'
  const [path, query = ''] = raw.split('?')
  return { path, segments: path.split('/').filter(Boolean), query: new URLSearchParams(query) }
}

export function useRoute(): Route {
  const [hash, setHash] = useState(window.location.hash)
  useEffect(() => {
    const onChange = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return parse(hash)
}

export function navigate(to: string) {
  window.location.hash = to
  window.scrollTo({ top: 0 })
}

/** Opens an in-app route in a new browser tab. */
export function openTab(to: string) {
  window.open(`${window.location.pathname}${window.location.search}#${to}`, '_blank')
}
