import type { DragEvent, KeyboardEvent } from 'react'
import type { ProjectEntry, ProjectPresentationStatus, ProjectReachability } from '../catalog/types'
import { projectLinkAttributes, projectPresentationStatus } from '../catalog/viewModel'
import { ProjectIcon } from './icons'

const STATUS_LABEL: Record<ProjectPresentationStatus, string> = {
  available: '可用',
  checking: '检测中',
  unavailable: '当前不可用',
  maintenance: '维护中',
  'coming-soon': '即将开放',
}

const DRAG_TYPE = 'text/plain'

export function ProjectCard({
  project,
  reachability,
  dragging,
  dropTarget,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  onMoveBy,
}: {
  project: ProjectEntry
  reachability: ProjectReachability
  dragging: boolean
  dropTarget: boolean
  onDragStart: () => void
  onDragOver: () => void
  onDrop: (fromId: string) => void
  onDragEnd: () => void
  onMoveBy: (delta: number) => void
}) {
  const presentationStatus = projectPresentationStatus(project, reachability)
  const link = projectLinkAttributes(project, reachability)
  const action = link ? (
    <a
      className="project-action"
      href={link.href}
      target={link.target}
      rel={link.rel}
      aria-label={link.ariaLabel}
    >
      进入项目 <span aria-hidden="true">↗</span>
    </a>
  ) : (
    <span className="project-action project-action--disabled" aria-disabled="true">
      {STATUS_LABEL[presentationStatus]}
    </span>
  )

  const startDrag = (event: DragEvent<HTMLButtonElement>) => {
    event.dataTransfer.setData(DRAG_TYPE, project.id)
    event.dataTransfer.effectAllowed = 'move'
    const card = event.currentTarget.closest('.project-card')
    if (card instanceof HTMLElement) {
      try {
        event.dataTransfer.setDragImage(card, 48, 32)
      } catch {
        // 部分浏览器不允许自定义拖影
      }
    }
    onDragStart()
  }

  const over = (event: DragEvent<HTMLElement>) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
    onDragOver()
  }

  const drop = (event: DragEvent<HTMLElement>) => {
    event.preventDefault()
    const fromId = event.dataTransfer.getData(DRAG_TYPE)
    if (fromId) onDrop(fromId)
  }

  const onHandleKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      onMoveBy(-1)
    }
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault()
      onMoveBy(1)
    }
  }

  return (
    <article
      className={`project-card project-card--${presentationStatus}${dragging ? ' project-card--dragging' : ''}${dropTarget ? ' project-card--drop-target' : ''}`}
      onDragOver={over}
      onDrop={drop}
      onDragEnd={onDragEnd}
    >
      <div className="project-card__top">
        <div className="project-card__top-start">
          <button
            type="button"
            className="drag-handle"
            draggable
            aria-label={`调整 ${project.name} 的显示顺序`}
            aria-keyshortcuts="ArrowUp ArrowDown ArrowLeft ArrowRight"
            title="拖动或用方向键调整顺序"
            onDragStart={startDrag}
            onKeyDown={onHandleKey}
          >
            <span aria-hidden="true">⋮⋮</span>
          </button>
          <span className="project-icon"><ProjectIcon name={project.icon} /></span>
        </div>
        <span className={`status status--${presentationStatus}`} aria-live="polite">{STATUS_LABEL[presentationStatus]}</span>
      </div>
      <div>
        <p className="project-category">{project.category}</p>
        <h2>{project.name}</h2>
        <p className="project-summary">{project.summary}</p>
      </div>
      <ul className="capability-list" aria-label={`${project.name} 的能力`}>
        {project.capabilities.map((capability) => <li key={capability}>{capability}</li>)}
      </ul>
      {project.tags.length > 0 && (
        <div className="tag-list" aria-label="技术标签">
          {project.tags.map((tag) => <span key={tag}>{tag}</span>)}
        </div>
      )}
      <div className="project-card__footer">
        <div>
          <span className="target-label">访问地址</span>
          <span className="target-host" title={project.launchUrl}>{project.displayHost ?? '待配置'}</span>
        </div>
        {action}
      </div>
      {(project.roleInChain || project.loginOrgHint || project.ownerTenantHint) && presentationStatus === 'available' && (
        <dl className="federation-hints">
          {project.roleInChain && <div><dt>在链路中</dt><dd>{project.roleInChain}</dd></div>}
          {project.loginOrgHint && <div><dt>登录组织</dt><dd>{project.loginOrgHint}</dd></div>}
          {project.ownerTenantHint && <div><dt>货主租户</dt><dd>{project.ownerTenantHint}</dd></div>}
        </dl>
      )}
      <p className="auth-hint">
        {presentationStatus === 'checking'
          ? '正在检测目标项目是否可访问'
          : presentationStatus === 'unavailable'
            ? '目标项目当前无法访问，将自动重新检测'
            : presentationStatus === 'coming-soon'
              ? '项目正在建设，开放后可从门户进入'
              : presentationStatus === 'maintenance'
                ? '项目正在维护，恢复后可从门户进入'
                : project.ownerTenantHint
                  ? '登录组织各台不同；创建活动只能看见同一货主已投放的商品'
                  : project.loginOrgHint
                    ? '进入后在目标项目登录页选择组织'
                    : '点击进入即可使用，无需登录'}
      </p>
    </article>
  )
}
