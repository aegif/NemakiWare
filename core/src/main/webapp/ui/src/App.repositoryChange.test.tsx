import { it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import App from './App';

// R349 (R344 batch, 3.4.2): a sign-in to another repository — another tab's, too: the auth context follows localStorage —
// reaches the screens that are open. None of them may keep the previous repository's rows, selection or an answer still
// in flight: the authenticated routes start afresh for the new repository. The route components and the auth context are
// stand-ins; a screen here is a probe that counts its mounts. The test names are single words because the
// negative-control runner reads them as lock names.
const auth = vi.hoisted(() => ({
  value: { isAuthenticated: true, authToken: { repositoryId: 'bedroom', token: 'first', username: 'admin' } } as {
    isAuthenticated: boolean;
    authToken: { repositoryId: string; token: string; username: string };
  },
}));
const mounts = vi.hoisted(() => ({ count: 0 }));
vi.mock('./contexts/AuthContext', () => ({
  AuthProvider: ({ children }: { children: unknown }) => children,
  useAuth: () => auth.value,
}));
vi.mock('./services/auth', () => ({ AuthService: { getInstance: () => ({ getAuthHeaders: () => ({}) }) } }));
vi.mock('./components/DocumentList/DocumentList', async () => {
  const React = await import('react');
  return {
    DocumentList: ({ repositoryId }: { repositoryId: string }) => {
      const [mount] = React.useState(() => ++mounts.count);
      return <div>{`documents of ${repositoryId}, mount ${mount}`}</div>;
    },
  };
});
vi.mock('./components/Layout/Layout', () => ({ Layout: ({ children }: { children: unknown }) => <div>{children as never}</div> }));
vi.mock('./components/ProtectedRoute/ProtectedRoute', () => ({ ProtectedRoute: ({ children }: { children: unknown }) => children }));
vi.mock('./components/AdminRoute/AdminRoute', () => ({ AdminRoute: ({ children }: { children: unknown }) => children }));
vi.mock('./components/SetupWizard/SetupWizard', () => ({ SetupWizard: () => null }));
vi.mock('./components/DocumentViewer/DocumentViewer', () => ({ DocumentViewer: () => null }));
vi.mock('./components/UserManagement/UserManagement', () => ({ UserManagement: () => null }));
vi.mock('./components/GroupManagement/GroupManagement', () => ({ GroupManagement: () => null }));
vi.mock('./components/TypeManagement/TypeManagement', () => ({ TypeManagement: () => null }));
vi.mock('./components/PermissionManagement/PermissionManagement', () => ({ PermissionManagement: () => null }));
vi.mock('./components/SearchBar/SearchResults', () => ({ SearchResults: () => null }));
vi.mock('./components/ArchiveManagement/ArchiveManagement', () => ({ ArchiveManagement: () => null }));
vi.mock('./components/SolrMaintenance/SolrMaintenance', () => ({ SolrMaintenance: () => null }));
vi.mock('./components/AuditDashboard/AuditDashboard', () => ({ AuditDashboard: () => null }));
vi.mock('./components/ApiDocs/ApiDocs', () => ({ ApiDocs: () => null }));
vi.mock('./components/HelpPage/HelpPage', () => ({ default: () => null }));
vi.mock('./components/FilesystemImportExport/FilesystemImportExport', () => ({ FilesystemImportExport: () => null }));
vi.mock('./components/WebhookManagement/WebhookManagement', () => ({ WebhookManagement: () => null }));
vi.mock('./components/CloudDirectorySync/CloudDirectorySync', () => ({ CloudDirectorySync: () => null }));
vi.mock('./components/ConfigViewer/ConfigViewer', () => ({ ConfigViewer: () => null }));
vi.mock('./components/IntegrationSettings/IntegrationSettings', () => ({ IntegrationSettings: () => null }));
vi.mock('./components/LineageJournal/LineageJournalPage', () => ({ default: () => null }));
vi.mock('./components/EvidenceAnchoring/EvidenceAnchoring', () => ({ EvidenceAnchoring: () => null }));
vi.mock('./components/PurviewManagement/PurviewManagement', () => ({ PurviewManagement: () => null }));
vi.mock('./components/RssTokenManagement/RssTokenManagement', () => ({ RssTokenManagement: () => null }));
vi.mock('./components/AccountSettings/AccountSettings', () => ({ AccountSettings: () => null }));
vi.mock('./components/McpCloudLogin/McpCloudLogin', () => ({ McpCloudLogin: () => null }));
vi.mock('./components/Login/Login', () => ({ Login: () => null }));

beforeEach(() => {
  auth.value = { isAuthenticated: true, authToken: { repositoryId: 'bedroom', token: 'first', username: 'admin' } };
  window.location.hash = '#/documents';
  // No MCP sign-in is pending (the routes read localStorage before they render).
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => undefined, removeItem: () => undefined });
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(
    JSON.stringify(String(url).includes('setup/state') ? { setupRequired: false } : {}),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/** Waits until {@code text} is on the screen; a timeout fails this lock's own expect(), not the query's harness error. */
async function shown(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is not shown`).not.toBeNull());
}

it('aSignInToAnotherRepositoryStartsTheScreenAfresh', async () => {
  const { rerender } = render(<App />);
  const first = mounts.count + 1;
  await shown(`documents of bedroom, mount ${first}`);

  auth.value = { isAuthenticated: true, authToken: { repositoryId: 'canopy', token: 'second', username: 'admin' } };
  rerender(<App />);
  await shown(`documents of canopy, mount ${first + 1}`);
  expect(screen.queryByText(`documents of canopy, mount ${first}`), 'the previous repository\'s screen kept').toBeNull();
});

it('aNewTokenForTheSameRepositoryKeepsTheScreen', async () => {
  const { rerender } = render(<App />);
  const first = mounts.count + 1;
  await shown(`documents of bedroom, mount ${first}`);

  auth.value = { isAuthenticated: true, authToken: { repositoryId: 'bedroom', token: 'renewed', username: 'admin' } };
  rerender(<App />);
  await shown(`documents of bedroom, mount ${first}`);
  expect(mounts.count, 'a screen started afresh for the same repository').toBe(first);
});
