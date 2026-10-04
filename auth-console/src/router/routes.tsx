import type { ReactNode } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import ProtectedRoute from '../auth/ProtectedRoute'
import CallbackPage from '../pages/CallbackPage'
import LoginPage from '../pages/LoginPage'
import WorkspaceHome from '../pages/WorkspaceHome'
import WorkspaceShell from '../pages/WorkspaceShell'
import WorkspaceOverview from '../pages/WorkspaceOverview'
import LegacyWorkspaceRedirect from '../pages/LegacyWorkspaceRedirect'
import FeatureGuard from '../pages/FeatureGuard'
import GrantsPage from '../pages/GrantsPage'
import PlaygroundPage from '../pages/PlaygroundPage'
import SchemaViewerPage from '../pages/SchemaViewerPage'
import SpacesPage from '../pages/SpacesPage'
import IdentitySyncPage from '../pages/IdentitySyncPage'
import AuditPage from '../pages/AuditPage'
import GovernancePage from '../pages/GovernancePage'
import GovernanceCatalogPage from '../pages/GovernanceCatalogPage'
import GovernanceAccessPage from '../pages/GovernanceAccessPage'
import GovernanceRequestsPage from '../pages/GovernanceRequestsPage'
import GovernanceInvitationsPage from '../pages/GovernanceInvitationsPage'
import GovernancePoliciesPage from '../pages/GovernancePoliciesPage'
import GovernancePersonnelPage from '../pages/GovernancePersonnelPage'
import InvitationAcceptPage from '../pages/InvitationAcceptPage'
import GovernancePermissionsPage, { GovernanceAuditPage, GovernanceGrantDiagnostic } from '../pages/GovernancePermissionsPage'

function guarded(feature: string, page: ReactNode) {
  return <FeatureGuard feature={feature}>{page}</FeatureGuard>
}

const workspacePages = [
  { index: true, element: <WorkspaceOverview /> },
  { path: 'grants', element: guarded('grants', <GrantsPage />) },
  { path: 'playground', element: guarded('playground', <PlaygroundPage />) },
  { path: 'schema', element: guarded('schema', <SchemaViewerPage />) },
  { path: 'spaces', element: guarded('spaces', <SpacesPage />) },
  { path: 'sync', element: guarded('sync', <IdentitySyncPage />) },
  { path: 'audit', element: guarded('audit', <AuditPage />) },
]

// 数据式路由表。/login /callback 公开;工作区在 /w/:id 下。
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/callback', element: <CallbackPage /> },
  { path: '/invitations/accept', element: <ProtectedRoute requireWorkspaceGroups={false}><InvitationAcceptPage /></ProtectedRoute> },
  { path: '/governance', element: <ProtectedRoute requireWorkspaceGroups={false}><GovernancePage /></ProtectedRoute>, children: [
    { path: 'menus', element: <GovernanceCatalogPage menus /> },
    { path: 'catalog', element: <GovernanceCatalogPage /> },
    { path: 'roles', element: <GovernanceAccessPage view="roles" /> },
    { path: 'grants', element: <GovernanceAccessPage view="grants" /> },
    { path: 'access', element: <GovernanceAccessPage /> },
    { path: 'requests', element: <GovernanceRequestsPage /> },
    { path: 'invitations', element: <GovernanceInvitationsPage /> },
    { path: 'policies', element: <GovernancePoliciesPage /> },
    { path: 'permissions', element: <GovernancePermissionsPage /> },
    { path: 'diagnostic', element: <GovernanceGrantDiagnostic /> },
    { path: 'audit', element: <GovernanceAuditPage /> },
    { path: 'personnel', element: <GovernancePersonnelPage /> },
  ] },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <WorkspaceHome />
      </ProtectedRoute>
    ),
  },
  {
    path: '/w/:workspaceId',
    element: (
      <ProtectedRoute>
        <WorkspaceShell />
      </ProtectedRoute>
    ),
    children: workspacePages,
  },
  { path: '/grants', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '/playground', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '/schema', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '/spaces', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '/sync', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '/audit', element: <ProtectedRoute><LegacyWorkspaceRedirect /></ProtectedRoute> },
  { path: '*', element: <Navigate to="/" replace /> },
])
