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

export function ProjectCard({ project, reachability }: { project: ProjectEntry; reachability: ProjectReachability }) {
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

  return (
    <article className={`project-card project-card--${presentationStatus}`}>
      <div className="project-card__top">
        <span className="project-icon"><ProjectIcon name={project.icon} /></span>
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
      <p className="auth-hint">
        {presentationStatus === 'checking'
          ? '正在检测目标项目是否可访问'
          : presentationStatus === 'unavailable'
            ? '目标项目当前无法访问，将自动重新检测'
            : '进入后由目标项目通过 Casdoor 统一登录'}
      </p>
    </article>
  )
}
