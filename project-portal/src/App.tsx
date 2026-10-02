import { useMemo, useState } from 'react'
import { useCatalog } from './catalog/useCatalog'
import { useProjectReachability } from './catalog/useProjectReachability'
import { useProjectOrder } from './catalog/useProjectOrder'
import { filterProjects, projectLinkAttributes } from './catalog/viewModel'
import type { ProjectEntry } from './catalog/types'
import { EmptyState, ErrorState, LoadingState } from './components/AsyncState'
import { ProjectCard } from './components/ProjectCard'
import { SearchFilters } from './components/SearchFilters'

const EMPTY_PROJECTS: ProjectEntry[] = []

export default function App() {
  const { state, retry } = useCatalog()
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('全部')
  const [onlyAvailable, setOnlyAvailable] = useState(false)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [dropTargetId, setDropTargetId] = useState<string | null>(null)

  const projects = state.kind === 'ready' ? state.value.catalog.projects : EMPTY_PROJECTS
  const { ordered, moveTo, moveBy, reset, hasCustomOrder } = useProjectOrder(projects)
  const { reachability, refresh: refreshReachability, checking } = useProjectReachability(projects)
  const categories = useMemo(() => [...new Set(projects.map((project) => project.category))], [projects])
  const availableCount = projects.filter((project) => projectLinkAttributes(
    project, reachability[project.id] ?? (project.healthUrl ? 'checking' : 'unchecked'),
  )).length
  const visible = useMemo(() => filterProjects(ordered, query, category).filter((project) => (
    !onlyAvailable || projectLinkAttributes(
      project, reachability[project.id] ?? (project.healthUrl ? 'checking' : 'unchecked'),
    ) !== null
  )), [category, onlyAvailable, ordered, query, reachability])
  const filtered = Boolean(query.trim()) || category !== '全部' || onlyAvailable

  const clearFilters = () => {
    setQuery('')
    setCategory('全部')
    setOnlyAvailable(false)
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
        <div className="topbar-meta">
          <a className="browse-link" href="#catalog-title">浏览项目 <span aria-hidden="true">↓</span></a>
          <span className="public-badge"><span aria-hidden="true">◎</span> 无需登录即可浏览</span>
        </div>
      </header>

      <main>
        <section className="hero">
          <p className="eyebrow">UNIFIED CAPABILITY HUB</p>
          <h1>发现并进入<br /><span>正在提供的技术能力</span></h1>
          <p className="hero-copy">从统一入口找到你需要的项目，快速进入身份、AI、规则、交易与协作工作台。</p>
          {state.kind === 'ready' && (
            <SearchFilters
              query={query}
              category={category}
              categories={categories}
              onlyAvailable={onlyAvailable}
              availableCount={availableCount}
              filtered={filtered}
              onQuery={setQuery}
              onCategory={setCategory}
              onOnlyAvailable={setOnlyAvailable}
              onClear={clearFilters}
            />
          )}
        </section>

        <section className="catalog-section" aria-labelledby="catalog-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">PROJECTS</p>
              <h2 id="catalog-title" tabIndex={-1}>能力项目</h2>
              <p className="order-hint">拖动卡片手柄调整顺序，或选中后用方向键移动；本机浏览器会记住。</p>
            </div>
            {state.kind === 'ready' && (
              <div className="section-meta">
                <span className="result-count" role="status">显示 <strong>{visible.length}</strong> / {projects.length} 个项目</span>
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
            onlyAvailable && checking ? (
              <div className="state-card" role="status">
                <div className="state-icon" aria-hidden="true">↻</div>
                <h2>正在检测项目状态</h2>
                <p>检测完成后，将显示当前可进入的项目。</p>
              </div>
            ) : <EmptyState filtered={filtered} onClear={clearFilters} />
          )}
        </section>
      </main>

      <footer>
        <span>免登录浏览目录 · 登录和权限由各项目独立管理</span>
      </footer>
    </div>
  )
}
