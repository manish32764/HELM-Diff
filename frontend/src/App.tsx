import { useEffect, useState } from 'react'
import { SourceViewerProvider } from './components/SourceViewer'
import { ToastProvider } from './components/ui'
import { useRoute } from './lib/router'
import { AnalysisPage } from './pages/AnalysisPage'
import { ChartsPage } from './pages/ChartsPage'
import { EnvVarsPage } from './pages/EnvVarsPage'
import { FileComparePage } from './pages/FileComparePage'
import { FolderHomePage } from './pages/FolderHomePage'
import { FolderTreePage } from './pages/FolderTreePage'
import { HomePage } from './pages/HomePage'
import { PortfolioPage } from './pages/PortfolioPage'
import { DiffComparePage, FourChartPage, HistoryPage, PairwisePage } from './pages/SetupPages'

interface NavItem {
  to: string
  icon: string
  label: string
  hint?: string
}

const NAV: { section: string | null; items: NavItem[] }[] = [
  { section: null, items: [
    { to: '/', icon: '⧉', label: 'Compare folders' },
  ] },
  { section: 'Advanced analysis', items: [
    { to: '/analyses', icon: '⌂', label: 'Overview' },
    { to: '/charts', icon: '⎈', label: 'Chart library' },
    { to: '/compare', icon: '⇆', label: 'Two-chart diff', hint: 'A↔B' },
    { to: '/diff-compare', icon: '◫', label: 'Two-diff comparison', hint: '2 diffs' },
    { to: '/four-chart', icon: '⊞', label: 'Four-chart analysis', hint: '4 charts' },
    { to: '/portfolio', icon: '▦', label: 'Portfolio', hint: 'all' },
    { to: '/history', icon: '◷', label: 'History' },
  ] },
]

const COLLAPSED_KEY = 'helm-compare:sidebar-collapsed'

export default function App() {
  const route = useRoute()
  const first = route.segments[0] ?? ''
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(COLLAPSED_KEY) === '1'
    } catch {
      return false
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0')
    } catch {
      // storage unavailable
    }
  }, [collapsed])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return
      if (e.key === '[' && !e.ctrlKey && !e.metaKey && !e.altKey) setCollapsed((c) => !c)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const page = (() => {
    switch (first) {
      case '': return <FolderHomePage />
      case 'folders':
        if (route.segments[2] === 'file') {
          return <FileComparePage key={route.query.get('path') ?? ''} id={route.segments[1]} path={route.query.get('path') ?? ''}
            initialDiff={route.query.get('diff') ?? undefined} />
        }
        if (route.segments[2] === 'env') {
          return <EnvVarsPage key={`${route.query.get('path')}|${route.query.get('scope')}`} id={route.segments[1]}
            path={route.query.get('path') ?? ''} scope={route.query.get('scope') ?? undefined} />
        }
        return <FolderTreePage key={route.segments[1]} id={route.segments[1]} />
      case 'analyses': return <HomePage />
      case 'charts': return <ChartsPage />
      case 'compare': return <PairwisePage key={route.query.toString()} route={route} />
      case 'diff-compare': return <DiffComparePage />
      case 'four-chart': return <FourChartPage />
      case 'portfolio': return <PortfolioPage />
      case 'history': return <HistoryPage />
      case 'analysis': return <AnalysisPage key={route.segments[1]} id={route.segments[1]} />
      default: return <FolderHomePage />
    }
  })()

  const activeFor = (to: string) => {
    if (to === '/') return first === '' || first === 'folders'
    if (to === '/history') return first === 'history' || first === 'analysis'
    return `/${first}` === to
  }
  const mainClass = first !== 'folders' ? '' : route.segments[2] ? 'compact' : 'fill'

  return (
    <ToastProvider>
      <SourceViewerProvider>
        <div className="app">
          <nav className={`sidebar ${collapsed ? 'collapsed' : ''}`}>
            <button className="sidebar-toggle" onClick={() => setCollapsed((c) => !c)}
              title={collapsed ? 'Expand sidebar  [' : 'Collapse sidebar  ['} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
              {collapsed ? '›' : '‹'}
            </button>
            <div className="brand">
              <div className="brand-mark">⎈</div>
              <div className="brand-text">
                <div className="brand-name">Helm Compare</div>
                <div className="brand-sub">Configuration intelligence</div>
              </div>
            </div>
            {NAV.map((group, i) => (
              <div key={i}>
                {group.section && <div className="nav-section">{group.section}</div>}
                {group.items.map((item) => (
                  <a key={item.to} href={`#${item.to}`} title={item.label}
                    className={`nav-item ${activeFor(item.to) ? 'active' : ''}`}>
                    <span className="nav-icon">{item.icon}</span>
                    <span className="nav-label">{item.label}</span>
                    {item.hint && <span className="nav-hint">{item.hint}</span>}
                  </a>
                ))}
              </div>
            ))}
            <div className="sidebar-foot">Press [ to collapse or expand this panel.</div>
          </nav>
          <main className={`main ${mainClass}`}>{page}</main>
        </div>
      </SourceViewerProvider>
    </ToastProvider>
  )
}
