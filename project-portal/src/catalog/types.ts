export type ProjectStatus = 'available' | 'maintenance' | 'coming-soon'
export type OpenMode = 'same-tab' | 'new-tab'
export type ProjectReachability = 'unchecked' | 'checking' | 'online' | 'offline'
export type ProjectPresentationStatus = ProjectStatus | 'checking' | 'unavailable'

export interface ProjectEntry {
  id: string
  name: string
  summary: string
  category: string
  capabilities: string[]
  tags: string[]
  icon: string
  status: ProjectStatus
  launchUrl?: string
  healthUrl?: string
  displayHost?: string
  openMode: OpenMode
  order: number
}

export interface Catalog {
  schemaVersion: 1
  updatedAt?: string
  projects: ProjectEntry[]
}

export interface ParsedCatalog {
  catalog: Catalog
  issues: string[]
}
