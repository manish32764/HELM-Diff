import { SourceViewerProvider } from './components/SourceViewer'
import { ToastProvider } from './components/ui'
import { useRoute } from './lib/router'
import { AnalysisPage } from './pages/AnalysisPage'
import { ChartsPage } from './pages/ChartsPage'
import { HomePage } from './pages/HomePage'
import { PortfolioPage } from './pages/PortfolioPage'
import { DiffComparePage, FourChartPage, HistoryPage, PairwisePage } from './pages/SetupPages'

const NAV = [
  { section: null, items: [
    { to: '/', icon: '⌂', label: 'Overview' },
    { to: '/charts', icon: '⎈', label: 'Chart library' },
  ] },
  { section: 'Analyse', items: [
    { to: '/compare', icon: '⇆', label: 'Two-chart diff', hint: 'A↔B' },
    { to: '/diff-compare', icon: '⧉', label: 'Two-diff comparison', hint: '2 diffs' },
    { to: '/four-chart', icon: '⊞', label: 'Four-chart analysis', hint: '4 charts' },
    { to: '/portfolio', icon: '▦', label: 'Portfolio', hint: 'all' },
  ] },
  { section: 'Audit', items: [
    { to: '/history', icon: '◷', label: 'History' },
  ] },
]

export default function App() {
  const route = useRoute()
  const first = route.segments[0] ?? ''

  const page = (() => {
    switch (first) {
      case '': return <HomePage />
      case 'charts': return <ChartsPage />
      case 'compare': return <PairwisePage key={route.query.toString()} route={route} />
      case 'diff-compare': return <DiffComparePage />
      case 'four-chart': return <FourChartPage />
      case 'portfolio': return <PortfolioPage />
      case 'history': return <HistoryPage />
      case 'analysis': return <AnalysisPage key={route.segments[1]} id={route.segments[1]} />
      default: return <HomePage />
    }
  })()

  const activeFor = (to: string) => (to === '/' ? first === '' : `/${first}` === to)
    || (first === 'analysis' && to === '/history')

  return (
    <ToastProvider>
      <SourceViewerProvider>
        <div className="app">
          <nav className="sidebar">
            <div className="brand">
              <div className="brand-mark">⎈</div>
              <div>
                <div className="brand-name">Helm Compare</div>
                <div className="brand-sub">Configuration intelligence</div>
              </div>
            </div>
            {NAV.map((group, i) => (
              <div key={i}>
                {group.section && <div className="nav-section">{group.section}</div>}
                {group.items.map((item) => (
                  <a key={item.to} href={`#${item.to}`} className={`nav-item ${activeFor(item.to) ? 'active' : ''}`}>
                    <span className="nav-icon">{item.icon}</span>
                    {item.label}
                    {'hint' in item && <span className="nav-hint">{item.hint}</span>}
                  </a>
                ))}
              </div>
            ))}
            <div className="sidebar-foot">Secret values are never shown — only how they are provided.</div>
          </nav>
          <main className="main">{page}</main>
        </div>
      </SourceViewerProvider>
    </ToastProvider>
  )
}
