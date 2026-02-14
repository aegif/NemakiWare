/**
 * AdminRoute Component for NemakiWare React UI
 *
 * Route protection component that restricts access to admin-only pages.
 * Non-admin users attempting to access admin routes are redirected to /documents.
 *
 * Admin Detection:
 * - Uses isAdmin flag from /me endpoint (set on the server side)
 * - No username-based fallback to avoid permission display inconsistency
 *
 * Usage:
 * ```tsx
 * <Route path="/users" element={
 *   <AdminRoute>
 *     <UserManagement repositoryId={repositoryId} />
 *   </AdminRoute>
 * } />
 * ```
 */

import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';

interface AdminRouteProps {
  children: React.ReactNode;
}

export const AdminRoute: React.FC<AdminRouteProps> = ({ children }) => {
  const { authToken } = useAuth();

  // Check if user is admin via isAdmin flag from /me endpoint
  const isAdmin = authToken?.isAdmin === true;

  if (!isAdmin) {
    // Non-admin users are redirected to documents page
    console.warn('AdminRoute: Access denied for non-admin user:', authToken?.username);
    return <Navigate to="/documents" replace />;
  }

  return <>{children}</>;
};
