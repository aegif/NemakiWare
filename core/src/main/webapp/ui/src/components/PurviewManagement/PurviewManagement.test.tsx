import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { PurviewManagement } from './PurviewManagement';

const mockService = {
  getStateOverview: vi.fn(),
  getSchemaDiff: vi.fn(),
  testConnection: vi.fn(),
  lookupGovernanceBulk: vi.fn(),
  applyTypeDefinitions: vi.fn(),
  startFullSync: vi.fn(),
  startIncrementalSync: vi.fn(),
  reconcileArchives: vi.fn(),
  reconcileCloudMetadata: vi.fn(),
  reconcileContainment: vi.fn(),
  reconcileTypes: vi.fn(),
  resolveDeletes: vi.fn(),
  retryFailed: vi.fn(),
};

const mockHandleAuthError = vi.fn();
const stableT = (key: string, params?: Record<string, unknown>) => {
  if (params?.count !== undefined) {
    return `${key}:${params.count}`;
  }
  return key;
};
const stableTranslation = { t: stableT };

vi.mock('../../services/purviewAdmin', () => ({
  PurviewAdminService: class {
    constructor() {
      return mockService;
    }
  },
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({
    handleAuthError: mockHandleAuthError,
  }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => stableTranslation,
}));

const baseStateOverview = {
  collection: 'NemakiWare',
  schemaState: {
    collection: 'NemakiWare',
    schemaVersion: '1',
    schemaHash: 'schema-hash-001',
    lastAppliedAt: '2026-03-20T10:00:00Z',
    lastAppliedBy: 'admin',
    lastDiffSummary: 'up to date',
  },
  jobs: [
    {
      jobId: 'job-collection',
      jobKind: 'TYPE_BOOTSTRAP',
      repositoryId: 'collection:NemakiWare',
      status: 'COMPLETED',
      startedAt: '2026-03-20T10:00:00Z',
      endedAt: '2026-03-20T10:00:10Z',
      processedCount: 3,
      failedCount: 0,
      checkpoint: 'schema-hash-001',
      errorSummary: '',
    },
    {
      jobId: 'job-bedroom',
      jobKind: 'FULL_SYNC',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T11:00:00Z',
      endedAt: '2026-03-20T11:05:00Z',
      processedCount: 42,
      failedCount: 0,
      checkpoint: 'token-001',
      errorSummary: '',
    },
    {
      jobId: 'job-canopy',
      jobKind: 'FULL_SYNC',
      repositoryId: 'canopy',
      status: 'FAILED',
      startedAt: '2026-03-20T09:00:00Z',
      endedAt: '2026-03-20T09:05:00Z',
      processedCount: 1,
      failedCount: 1,
      checkpoint: 'token-000',
      errorSummary: 'failure',
    },
  ],
  cursors: [
    {
      repositoryId: 'bedroom',
      streamKind: 'content-change-log',
      cursor: 'token-001',
      cursorKind: 'changeToken',
      lastRunAt: '2026-03-20T11:05:00Z',
      lastSuccessAt: '2026-03-20T11:05:00Z',
      lastErrorAt: '',
      lastErrorMessage: '',
      consecutiveFailureCount: 0,
      deadLetterCount: 0,
    },
  ],
  locks: [
    {
      repositoryId: 'bedroom',
      jobKind: 'FULL_SYNC',
      locked: false,
      ownerJobId: '',
      lockedAt: '',
    },
  ],
  tombstones: [
    {
      repositoryId: 'bedroom',
      objectId: 'doc-001',
      typeName: 'nemaki_document',
      qualifiedName: 'nemaki://bedroom/document/doc-001',
      changeToken: 'token-delete',
      firstSeenAt: '2026-03-20T11:10:00Z',
      dueAt: '2026-03-20T11:20:00Z',
      status: 'PENDING',
    },
  ],
  deadLetters: [
    {
      repositoryId: 'bedroom',
      streamKind: 'content-change-log',
      entryKey: 'doc-002',
      typeName: 'nemaki_document',
      qualifiedName: 'nemaki://bedroom/document/doc-002',
      firstFailedAt: '2026-03-20T11:00:00Z',
      lastFailedAt: '2026-03-20T11:10:00Z',
      failureCount: 2,
      checkpoint: 'token-002',
      errorSummary: 'publish failure',
    },
  ],
};

const baseSchemaDiff = {
  collection: 'NemakiWare',
  currentSchemaVersion: '1',
  currentSchemaHash: 'schema-hash-001',
  desiredSchemaVersion: '2',
  desiredSchemaHash: 'schema-hash-002',
  applyRequired: true,
  customTypeNames: ['nemaki_document', 'nemaki_archive'],
  relationshipTypeNames: ['nemaki_document_has_archive'],
  businessMetadataNames: ['nemakiGovernance'],
};

const baseConnectionStatus = {
  status: 'success',
  connected: true,
  featureEnabled: true,
  endpoint: 'https://example-account.purview.azure.com',
  atlasBasePath: 'datamap/api/atlas/v2',
  message: 'Purview connection succeeded',
};

describe('PurviewManagement', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockService.getStateOverview.mockResolvedValue(baseStateOverview);
    mockService.getSchemaDiff.mockResolvedValue(baseSchemaDiff);
    mockService.testConnection.mockResolvedValue(baseConnectionStatus);
    mockService.lookupGovernanceBulk.mockResolvedValue([
      {
        status: 'OK',
        featureEnabled: true,
        available: true,
        supportedObjectType: true,
        entityFound: true,
        repositoryId: 'bedroom',
        objectId: 'doc-lookup-001',
        objectBaseType: 'cmis:document',
        entityTypeName: 'nemaki_document',
        qualifiedName: 'nemaki://bedroom/objects/doc-lookup-001',
        atlasBasePath: 'datamap/api/atlas/v2',
        message: 'Purview governance metadata loaded',
        classifications: [{ typeName: 'HighlyConfidential', entityStatus: 'ACTIVE' }],
        glossaryTerms: [{ displayText: 'Quarterly Report' }],
        labels: ['finance'],
        businessMetadata: {
          nemakiGovernance: {
            ownerDepartment: 'Finance',
          },
        },
      },
      {
        objectId: 'missing-001',
        status: 'NOT_FOUND',
        message: 'Object not found',
      },
    ]);
    mockService.applyTypeDefinitions.mockResolvedValue({
      applied: true,
      message: 'schema applied',
      collection: 'NemakiWare',
      schemaVersion: '2',
      schemaHash: 'schema-hash-002',
      lastAppliedAt: '2026-03-20T12:00:00Z',
      lastAppliedBy: 'admin',
      jobId: 'job-bootstrap-002',
      jobKind: 'TYPE_BOOTSTRAP',
      repositoryId: 'collection:NemakiWare',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:00:00Z',
      endedAt: '2026-03-20T12:00:10Z',
      processedCount: 4,
      failedCount: 0,
      checkpoint: 'schema-hash-002',
      errorSummary: '',
    });
    mockService.startFullSync.mockResolvedValue({
      jobId: 'job-full-sync-002',
      jobKind: 'FULL_SYNC',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:10:00Z',
      endedAt: '2026-03-20T12:11:00Z',
      processedCount: 43,
      failedCount: 0,
      checkpoint: 'token-003',
      errorSummary: '',
    });
    mockService.startIncrementalSync.mockResolvedValue({
      jobId: 'job-incremental-001',
      jobKind: 'INCREMENTAL_SYNC',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:15:00Z',
      endedAt: '2026-03-20T12:15:10Z',
      processedCount: 3,
      failedCount: 0,
      checkpoint: 'token-004',
      errorSummary: '',
    });
    mockService.reconcileArchives.mockResolvedValue({
      jobId: 'job-archive-001',
      jobKind: 'ARCHIVE_RECONCILIATION',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:16:00Z',
      endedAt: '2026-03-20T12:16:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
    mockService.reconcileCloudMetadata.mockResolvedValue({
      jobId: 'job-cloud-001',
      jobKind: 'CLOUD_METADATA_RECONCILIATION',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:17:00Z',
      endedAt: '2026-03-20T12:17:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
    mockService.reconcileContainment.mockResolvedValue({
      jobId: 'job-containment-001',
      jobKind: 'CONTAINMENT_RECONCILIATION',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:18:00Z',
      endedAt: '2026-03-20T12:18:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
    mockService.reconcileTypes.mockResolvedValue({
      jobId: 'job-types-001',
      jobKind: 'TYPE_RECONCILIATION',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:19:00Z',
      endedAt: '2026-03-20T12:19:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
    mockService.resolveDeletes.mockResolvedValue({
      jobId: 'job-delete-001',
      jobKind: 'DELETE_RESOLUTION',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:20:00Z',
      endedAt: '2026-03-20T12:20:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
    mockService.retryFailed.mockResolvedValue({
      jobId: 'job-retry-001',
      jobKind: 'RETRY_FAILED',
      repositoryId: 'bedroom',
      status: 'COMPLETED',
      startedAt: '2026-03-20T12:21:00Z',
      endedAt: '2026-03-20T12:21:10Z',
      processedCount: 1,
      failedCount: 0,
      checkpoint: '',
      errorSummary: '',
    });
  });

  it('renders summary from state overview, schema diff, and connection probe', async () => {
    render(<PurviewManagement repositoryId="bedroom" />);

    await waitFor(() => {
      expect(mockService.getStateOverview).toHaveBeenCalledTimes(1);
      expect(mockService.getSchemaDiff).toHaveBeenCalledTimes(1);
      expect(mockService.testConnection).toHaveBeenCalledTimes(1);
    });

    expect(screen.getByText('purviewManagement.title')).toBeInTheDocument();
    expect(screen.getByText('https://example-account.purview.azure.com')).toBeInTheDocument();
    expect(screen.getByText('datamap/api/atlas/v2')).toBeInTheDocument();
    expect(screen.getAllByText('schema-hash-001')).toHaveLength(2);
    expect(screen.getByText('purviewManagement.alerts.applyRequired')).toBeInTheDocument();
    expect(screen.getByText('purviewManagement.stats.jobs')).toBeInTheDocument();
    expect(screen.getByText('purviewManagement.stats.deadLetters')).toBeInTheDocument();
  });

  it('starts full sync for the active repository and refreshes the overview', async () => {
    render(<PurviewManagement repositoryId="bedroom" />);

    await waitFor(() => {
      expect(mockService.getStateOverview).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByText('purviewManagement.actions.fullSync'));

    await waitFor(() => {
      expect(mockService.startFullSync).toHaveBeenCalledWith('bedroom');
    });

    await waitFor(() => {
      expect(mockService.getStateOverview).toHaveBeenCalledTimes(2);
    });

    expect(screen.getByText('job-full-sync-002')).toBeInTheDocument();
    expect(screen.getAllByText('FULL_SYNC').length).toBeGreaterThan(0);
  });

  it('looks up governance for repository objects in bulk', async () => {
    render(<PurviewManagement repositoryId="bedroom" />);

    await waitFor(() => {
      expect(mockService.getStateOverview).toHaveBeenCalledTimes(1);
    });

    fireEvent.change(screen.getByPlaceholderText('purviewManagement.governanceLookup.objectIdPlaceholder'), {
      target: { value: 'doc-lookup-001,\nmissing-001' },
    });
    fireEvent.click(screen.getByText('purviewManagement.actions.lookupGovernance'));

    await waitFor(() => {
      expect(mockService.lookupGovernanceBulk).toHaveBeenCalledWith('bedroom', ['doc-lookup-001', 'missing-001']);
    });

    expect(screen.getByText('HighlyConfidential')).toBeInTheDocument();
    expect(screen.getByText('Quarterly Report')).toBeInTheDocument();
    expect(screen.getByText('ownerDepartment: Finance')).toBeInTheDocument();
    expect(screen.getByText('Object not found')).toBeInTheDocument();
  });
});
