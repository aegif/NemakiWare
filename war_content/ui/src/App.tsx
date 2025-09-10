import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { Layout } from './components/Layout/Layout';
import { DocumentList } from './components/DocumentList/DocumentList';
import { DocumentViewer } from './components/DocumentViewer/DocumentViewer';
import { UserManagement } from './components/UserManagement/UserManagement';
import { GroupManagement } from './components/GroupManagement/GroupManagement';
import { TypeManagement } from './components/TypeManagement/TypeManagement';
import { PermissionManagement } from './components/PermissionManagement/PermissionManagement';
import { SearchResults } from './components/SearchBar/SearchResults';
import { ArchiveManagement } from './components/ArchiveManagement/ArchiveManagement';
import { Login } from './components/Login/Login';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { ProtectedRoute } from './components/ProtectedRoute/ProtectedRoute';

const customTheme = {
  token: {
    colorPrimary: '#1890ff',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f5f5f5',
    colorBorder: '#cccccc',
  },
};

function AppContent() {
  const { isAuthenticated, authToken, login } = useAuth();

  if (!isAuthenticated || !authToken) {
    return <Login onLogin={async (auth) => {
      // AuthContext will handle the authentication state update
      console.log('AppContent: Login successful with auth:', auth);
      // The AuthContext's useEffect will detect the localStorage change and update state
    }} />;
  }

  return (
    <Router basename="/core/ui">
      <Layout repositoryId={authToken.repositoryId}>
        <Routes>
          <Route path="/" element={<Navigate to="/documents" replace />} />
          <Route path="/documents" element={
            <ProtectedRoute>
              <DocumentList repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/documents/:objectId" element={
            <ProtectedRoute>
              <DocumentViewer repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/search" element={
            <ProtectedRoute>
              <SearchResults repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/users" element={
            <ProtectedRoute>
              <UserManagement repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/groups" element={
            <ProtectedRoute>
              <GroupManagement repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/types" element={
            <ProtectedRoute>
              <TypeManagement repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/permissions/:objectId" element={
            <ProtectedRoute>
              <PermissionManagement repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/archive" element={
            <ProtectedRoute>
              <ArchiveManagement repositoryId={authToken.repositoryId} />
            </ProtectedRoute>
          } />
          <Route path="/oidc-callback" element={<Login onLogin={() => {}} />} />
          <Route path="/saml-callback" element={<Login onLogin={() => {}} />} />
        </Routes>
      </Layout>
    </Router>
  );
}

function App() {
  return (
    <ConfigProvider theme={customTheme}>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </ConfigProvider>
  );
}

export default App;
