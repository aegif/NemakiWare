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
import { AuthService } from './services/auth';

const customTheme = {
  token: {
    colorPrimary: '#c72439',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f5f5f5',
    colorBorder: '#cccccc',
  },
};

function App() {
  const [isAuthenticated, setIsAuthenticated] = React.useState(false);
  const [repositoryId, setRepositoryId] = React.useState<string>('');

  React.useEffect(() => {
    const authService = AuthService.getInstance();
    (window as any).authService = authService;
    
    const authData = localStorage.getItem('nemakiware_auth');
    if (authData) {
      try {
        const auth = JSON.parse(authData);
        setIsAuthenticated(true);
        setRepositoryId(auth.repositoryId);
      } catch (e) {
        localStorage.removeItem('nemakiware_auth');
      }
    }
  }, []);

  if (!isAuthenticated) {
    return (
      <ConfigProvider theme={customTheme}>
        <Login onLogin={(auth) => {
          setIsAuthenticated(true);
          setRepositoryId(auth.repositoryId);
        }} />
      </ConfigProvider>
    );
  }

  return (
    <ConfigProvider theme={customTheme}>
      <Router basename="/core/ui">
        <Layout repositoryId={repositoryId}>
          <Routes>
            <Route path="/" element={<Navigate to="/documents" replace />} />
            <Route path="/documents" element={<DocumentList repositoryId={repositoryId} />} />
            <Route path="/documents/:objectId" element={<DocumentViewer repositoryId={repositoryId} />} />
            <Route path="/search" element={<SearchResults repositoryId={repositoryId} />} />
            <Route path="/users" element={<UserManagement repositoryId={repositoryId} />} />
            <Route path="/groups" element={<GroupManagement repositoryId={repositoryId} />} />
            <Route path="/types" element={<TypeManagement repositoryId={repositoryId} />} />
            <Route path="/permissions/:objectId" element={<PermissionManagement repositoryId={repositoryId} />} />
            <Route path="/archive" element={<ArchiveManagement repositoryId={repositoryId} />} />
            <Route path="/oidc-callback" element={<Login onLogin={(auth) => {
              setIsAuthenticated(true);
              setRepositoryId(auth.repositoryId);
            }} />} />
          </Routes>
        </Layout>
      </Router>
    </ConfigProvider>
  );
}

export default App;
