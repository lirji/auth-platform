import { useMemo, useState } from 'react'
import { useCatalog } from './catalog/useCatalog'
import { useProjectReachability } from './catalog/useProjectReachability'
import { useProjectOrder } from './catalog/useProjectOrder'
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
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [dropTargetId, setDropTargetId] = useState<string | null>(null)

  const projects = state.kind === 'ready' ? state.value.catalog.projects : EMPTY_PROJECTS
  const { ordered, moveTo, moveBy, reset, hasCustomOrder } = useProjectOrder(projects)
  const { reachability, refresh: refreshReachability, checking } = useProjectReachability(projects)
  const categories = useMemo(() => [...new Set(projects.map((project) => project.category))], [projects])
  const visible = useMemo(() => filterProjects(ordered, query, category), [category, ordered, query])

  const clearFilters = () => {
    setQuery('')
    setCategory('全部')
  }

  const finishDrag = () => {
    setDraggingId(null)
    setDropTargetId(null)
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
          <p className="hero-copy">一站式浏览身份、AI、推荐、规则、流程、协同办公、风控、对账、权益、交易与仓储能力。各台登录组织不同，不要填成同一个。创建活动只能使用同一业务租户（货主）已投放的商品。</p>
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
              <p className="order-hint">按卡片左上角手柄拖动可调整顺序，本机浏览器会记住；也可选中手柄后用方向键微调。</p>
            </div>
            {state.kind === 'ready' && (
              <div className="section-meta">
                <span>{visible.length} / {projects.length} 个项目</span>
                {hasCustomOrder && (
                  <button type="button" onClick={reset}>恢复默认顺序</button>
                )}
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
            <div className={`project-grid${draggingId ? ' project-grid--dragging' : ''}`}>
              {visible.map((project) => (
                <ProjectCard
                  key={project.id}
                  project={project}
                  reachability={reachability[project.id] ?? (project.healthUrl ? 'checking' : 'unchecked')}
                  dragging={draggingId === project.id}
                  dropTarget={dropTargetId === project.id && draggingId !== project.id}
                  onDragStart={() => setDraggingId(project.id)}
                  onDragOver={() => {
                    if (draggingId && draggingId !== project.id) setDropTargetId(project.id)
                  }}
                  onDrop={(fromId) => {
                    moveTo(fromId, project.id, visible)
                    finishDrag()
                  }}
                  onDragEnd={finishDrag}
                  onMoveBy={(delta) => moveBy(project.id, delta, visible)}
                />
              ))}
            </div>
          )}
          {state.kind === 'ready' && visible.length === 0 && (
            <EmptyState filtered={Boolean(query) || category !== '全部'} onClear={clearFilters} />
          )}
        </section>
      </main>

      <footer>
        <span>能力门户只负责发现并进入项目，登录与权限由各项目自己处理。</span>
      </footer>
    </div>
  )
}
