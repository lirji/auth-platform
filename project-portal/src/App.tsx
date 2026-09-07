import { useMemo, useState } from 'react'
import { useCatalog } from './catalog/useCatalog'
import { useProjectReachability } from './catalog/useProjectReachability'
import { filterProjects } from './catalog/viewModel'
import type { ProjectEntry } from './catalog/types'
import { EmptyState, ErrorState, LoadingState } from './components/AsyncState'
import { ProjectCard } from './components/ProjectCard'
import { SearchFilters } from './components/SearchFilters'

const EMPTY_PROJECTS: ProjectEntry[] = []

export default function App() {
  const { state, retry } = useCatalog()
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('全部')

  const projects = state.kind === 'ready' ? state.value.catalog.projects : EMPTY_PROJECTS
  const { reachability, refresh: refreshReachability, checking } = useProjectReachability(projects)
  const categories = useMemo(() => [...new Set(projects.map((project) => project.category))], [projects])
  const visible = useMemo(() => filterProjects(projects, query, category), [category, projects, query])

  const clearFilters = () => {
    setQuery('')
    setCategory('全部')
  }

  return (
    <div className="site-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="能力门户首页">
          <span className="brand-mark" aria-hidden="true">◆</span>
          <span>能力门户</span>
        </a>
        <span className="public-badge"><span aria-hidden="true">◎</span> 无需登录即可浏览</span>
      </header>

      <main>
        <section className="hero">
          <p className="eyebrow">UNIFIED CAPABILITY HUB</p>
          <h1>发现并进入<br /><span>正在提供的技术能力</span></h1>
          <p className="hero-copy">一站式浏览 AI、推荐、规则、流程、风控、对账与权益能力。各台登录组织不同，不要填成同一个。创建活动只能使用同一业务租户（货主）已投放的商品。</p>
          {state.kind === 'ready' && (
            <SearchFilters
              query={query}
              category={category}
              categories={categories}
              onQuery={setQuery}
              onCategory={setCategory}
            />
          )}
        </section>

        <section className="catalog-section" aria-labelledby="catalog-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">PROJECTS</p>
              <h2 id="catalog-title">能力项目</h2>
            </div>
            {state.kind === 'ready' && (
              <div className="section-meta">
                <span>{visible.length} / {projects.length} 个项目</span>
                <button type="button" onClick={() => void refreshReachability()} disabled={checking}>
                  {checking ? '检测中…' : '刷新状态'}
                </button>
              </div>
            )}
          </div>

          {state.kind === 'loading' && <LoadingState />}
          {state.kind === 'error' && <ErrorState message={state.message} onRetry={retry} />}
          {state.kind === 'ready' && state.value.issues.length > 0 && (
            <div className="catalog-warning" role="status">目录中有 {state.value.issues.length} 个无效配置，已安全忽略。</div>
          )}
          {state.kind === 'ready' && visible.length > 0 && (
            <div className="project-grid">{visible.map((project) => (
              <ProjectCard
                key={project.id}
                project={project}
                reachability={reachability[project.id] ?? (project.healthUrl ? 'checking' : 'unchecked')}
              />
            ))}</div>
          )}
          {state.kind === 'ready' && visible.length === 0 && (
            <EmptyState filtered={Boolean(query) || category !== '全部'} onClear={clearFilters} />
          )}
        </section>
      </main>

      <footer>
        <span>能力门户仅提供项目导航</span>
        <span>登录组织各台独立；货主业务租户必须一致</span>
        <a href="http://127.0.0.1:3001" target="_blank" rel="noopener noreferrer">Grafana Tempo :3001</a>
        <span>密码见营销 .env 的 GRAFANA_ADMIN_PASSWORD</span>
      </footer>
    </div>
  )
}
