import type { ProjectEntry, ProjectPresentationStatus, ProjectReachability } from './types'

function normalize(value: string) {
  return value.trim().toLocaleLowerCase('zh-CN')
}

export function filterProjects(projects: ProjectEntry[], query: string, category: string): ProjectEntry[] {
  const needle = normalize(query)
  return projects.filter((project) => {
    const categoryMatch = category === '全部' || project.category === category
    if (!categoryMatch || !needle) return categoryMatch
    return normalize([
      project.name,
      project.summary,
      project.category,
      ...project.capabilities,
      ...project.tags,
      project.displayHost ?? '',
    ].join(' ')).includes(needle)
  })
}

export interface ProjectLinkAttributes {
  href: string
  target?: '_blank'
  rel?: 'noopener noreferrer'
  ariaLabel: string
}

export function projectPresentationStatus(
  project: ProjectEntry,
  reachability: ProjectReachability = 'unchecked',
): ProjectPresentationStatus {
  if (project.status !== 'available') return project.status
  if (reachability === 'checking') return 'checking'
  if (reachability === 'offline') return 'unavailable'
  return 'available'
}

export function projectLinkAttributes(
  project: ProjectEntry,
  reachability: ProjectReachability = 'unchecked',
): ProjectLinkAttributes | null {
  if (project.status !== 'available' || !project.launchUrl || reachability === 'checking' || reachability === 'offline') return null
  const newTab = project.openMode === 'new-tab'
  return {
    href: project.launchUrl,
    target: newTab ? '_blank' : undefined,
    rel: newTab ? 'noopener noreferrer' : undefined,
    ariaLabel: `${newTab ? '在新标签页' : ''}进入 ${project.name}`,
  }
}
