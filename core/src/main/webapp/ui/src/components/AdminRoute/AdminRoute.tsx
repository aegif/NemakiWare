/**
 * AdminRoute Component for NemakiWare React UI
 *
 * Route protection component that restricts access to admin-only pages.
 * Non-admin users attempting to access admin routes are redirected to /documents.
 *
 * Admin Detection:
 * - Currently checks if username === 'admin'
 * - For OIDC/SAML, the username from the token is used
 * - Future enhancement: Check for admin role in token claims
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

  // Check if user is admin
  // For now, we simply check username === 'admin'
  // This works for both basic auth and OIDC/SAML (username is set from token)
  const isAdmin = authToken?.username === 'admin';

  if (!isAdmin) {
    // Non-admin users are redirected to documents page
    console.warn('AdminRoute: Access denied for non-admin user:', authToken?.username);
    return <Navigate to="/documents" replace />;
  }

  return <>{children}</>;
};
