import { useMemo, useState } from 'react'
import { useCatalog } from './catalog/useCatalog'
import { filterProjects } from './catalog/viewModel'
import { EmptyState, ErrorState, LoadingState } from './components/AsyncState'
import { ProjectCard } from './components/ProjectCard'
import { SearchFilters } from './components/SearchFilters'

export default function App() {
  const { state, retry } = useCatalog()
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('全部')

  const projects = state.kind === 'ready' ? state.value.catalog.projects : []
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
          <p className="hero-copy">统一查看 AI、推荐与规则平台。选择项目后，由目标系统通过 Casdoor 完成身份验证与权限校验。</p>
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
            {state.kind === 'ready' && <span>{visible.length} / {projects.length} 个项目</span>}
          </div>

          {state.kind === 'loading' && <LoadingState />}
          {state.kind === 'error' && <ErrorState message={state.message} onRetry={retry} />}
          {state.kind === 'ready' && state.value.issues.length > 0 && (
            <div className="catalog-warning" role="status">目录中有 {state.value.issues.length} 个无效配置，已安全忽略。</div>
          )}
          {state.kind === 'ready' && visible.length > 0 && (
            <div className="project-grid">{visible.map((project) => <ProjectCard key={project.id} project={project} />)}</div>
          )}
          {state.kind === 'ready' && visible.length === 0 && (
            <EmptyState filtered={Boolean(query) || category !== '全部'} onClear={clearFilters} />
          )}
        </section>
      </main>

      <footer>
        <span>能力门户仅提供项目导航</span>
        <span>登录与业务权限由各目标项目及 Casdoor 管理</span>
      </footer>
    </div>
  )
}
