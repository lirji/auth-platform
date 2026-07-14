import { createBrowserRouter, Navigate } from 'react-router-dom'
import ProtectedRoute from '../auth/ProtectedRoute'
import AppLayout from '../components/layout/AppLayout'
import CallbackPage from '../pages/CallbackPage'
import OverviewPage from '../pages/OverviewPage'
import GrantsPage from '../pages/GrantsPage'
import PlaygroundPage from '../pages/PlaygroundPage'
import SchemaViewerPage from '../pages/SchemaViewerPage'
import SpacesPage from '../pages/SpacesPage'
import IdentitySyncPage from '../pages/IdentitySyncPage'
import AuditPage from '../pages/AuditPage'

// 数据式路由表。/callback 公开;其余在 ProtectedRoute(未登录跳 Casdoor)+ AppLayout 下。
export const router = createBrowserRouter([
  { path: '/callback', element: <CallbackPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'grants', element: <GrantsPage /> },
      { path: 'playground', element: <PlaygroundPage /> },
      { path: 'schema', element: <SchemaViewerPage /> },
      { path: 'spaces', element: <SpacesPage /> },
      { path: 'sync', element: <IdentitySyncPage /> },
      { path: 'audit', element: <AuditPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
