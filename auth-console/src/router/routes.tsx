import type { ReactNode } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import ProtectedRoute from '../auth/ProtectedRoute';
import CallbackPage from '../features/session/pages/CallbackPage';
import LoginPage from '../features/session/pages/LoginPage';
import WorkspaceHome from '../features/workspaces/pages/WorkspaceHome';
import WorkspaceShell from '../app/shell/WorkspaceShell';
import WorkspaceOverview from '../app/shell/WorkspaceOverview';
import LegacyWorkspaceRedirect from '../app/shell/LegacyWorkspaceRedirect';
import FeatureGuard from '../app/shell/FeatureGuard';
import GrantsPage from '../features/grants/pages/GrantsPage';
import PlaygroundPage from '../features/playground/pages/PlaygroundPage';
import SchemaViewerPage from '../features/schema/pages/SchemaViewerPage';
import SpacesPage from '../features/spaces/pages/SpacesPage';
import IdentitySyncPage from '../features/identity/pages/IdentitySyncPage';
import AuditPage from '../features/audit/pages/AuditPage';
import GovernancePage from '../features/governance/shell/GovernancePage';
import GovernanceCatalogPage from '../features/catalog/pages/GovernanceCatalogPage';
import GovernanceAccessPage from '../features/access/pages/GovernanceAccessPage';
import GovernanceRequestsPage from '../features/requests/pages/GovernanceRequestsPage';
import GovernanceInvitationsPage from '../features/invitations/pages/GovernanceInvitationsPage';
import GovernancePoliciesPage from '../features/access/pages/GovernancePoliciesPage';
import GovernanceReviewsPage from '../features/reviews/pages/GovernanceReviewsPage';
import GovernancePersonnelPage from '../features/personnel/pages/GovernancePersonnelPage';
import InvitationAcceptPage from '../features/invitations/pages/InvitationAcceptPage';
import GovernancePermissionsPage, {
  GovernanceAuditPage,
  GovernanceGrantDiagnostic,
} from '../features/permissions/pages/GovernancePermissionsPage';

function guarded(feature: string, page: ReactNode) {
  return <FeatureGuard feature={feature}>{page}</FeatureGuard>;
}

const workspacePages = [
  { index: true, element: <WorkspaceOverview /> },
  { path: 'grants', element: guarded('grants', <GrantsPage />) },
  { path: 'playground', element: guarded('playground', <PlaygroundPage />) },
  { path: 'schema', element: guarded('schema', <SchemaViewerPage />) },
  { path: 'spaces', element: guarded('spaces', <SpacesPage />) },
  { path: 'sync', element: guarded('sync', <IdentitySyncPage />) },
  { path: 'audit', element: guarded('audit', <AuditPage />) },
];

// 数据式路由表。/login /callback 公开;工作区在 /w/:id 下。
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/callback', element: <CallbackPage /> },
  {
    path: '/invitations/accept',
    element: (
      <ProtectedRoute requireWorkspaceGroups={false}>
        <InvitationAcceptPage />
      </ProtectedRoute>
    ),
  },
  {
    path: '/governance',
    element: (
      <ProtectedRoute requireWorkspaceGroups={false}>
        <GovernancePage />
      </ProtectedRoute>
    ),
    children: [
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
      { path: 'reviews', element: <GovernanceReviewsPage /> },
    ],
  },
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
  {
    path: '/grants',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  {
    path: '/playground',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  {
    path: '/schema',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  {
    path: '/spaces',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  {
    path: '/sync',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  {
    path: '/audit',
    element: (
      <ProtectedRoute>
        <LegacyWorkspaceRedirect />
      </ProtectedRoute>
    ),
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
