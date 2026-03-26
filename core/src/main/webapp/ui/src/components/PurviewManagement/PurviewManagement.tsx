import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Space, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import {
  PurviewAdminService,
  PurviewConnectionStatus,
  PurviewJobState,
  PurviewSchemaApplyJobResult,
  PurviewSchemaDiff,
  PurviewStateOverview,
} from '../../services/purviewAdmin';
import type { PurviewGovernanceBulkItemView } from '../../services/purviewGovernance';
import type { ActionResult } from './purviewUtils';
import { isCollectionScope } from './purviewUtils';
import { ConnectionCard } from './ConnectionCard';
import { SchemaCard } from './SchemaCard';
import { SyncActionsCard } from './SyncActionsCard';
import { GovernanceLookupCard } from './GovernanceLookupCard';
import { StateTablesCard } from './StateTablesCard';

interface PurviewManagementProps {
  repositoryId: string;
}

export const PurviewManagement: React.FC<PurviewManagementProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const service = useMemo(
    () => new PurviewAdminService(() => handleAuthError(null)),
    [handleAuthError]
  );

  const [overview, setOverview] = useState<PurviewStateOverview | null>(null);
  const [schemaDiff, setSchemaDiff] = useState<PurviewSchemaDiff | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<PurviewConnectionStatus | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const [actionResult, setActionResult] = useState<ActionResult | null>(null);
  const initialLoadRef = useRef(true);

  const loadAll = useCallback(async () => {
    setRefreshing(true);
    const results = await Promise.allSettled([
      service.getStateOverview(),
      service.getSchemaDiff(),
      service.testConnection(),
    ]);

    const nextErrors: string[] = [];

    const [overviewResult, diffResult, connectionResult] = results;

    if (overviewResult.status === 'fulfilled') {
      setOverview(overviewResult.value);
    } else {
      nextErrors.push(overviewResult.reason instanceof Error ? overviewResult.reason.message : t('common.unknownError'));
    }

    if (diffResult.status === 'fulfilled') {
      setSchemaDiff(diffResult.value);
    } else {
      nextErrors.push(diffResult.reason instanceof Error ? diffResult.reason.message : t('common.unknownError'));
    }

    if (connectionResult.status === 'fulfilled') {
      setConnectionStatus(connectionResult.value);
    } else {
      nextErrors.push(
        connectionResult.reason instanceof Error ? connectionResult.reason.message : t('common.unknownError')
      );
    }

    setLoadError(nextErrors.length > 0 ? nextErrors.join(' / ') : null);
    setRefreshing(false);
    initialLoadRef.current = false;
  }, [service, t]);

  useEffect(() => {
    void loadAll();
  }, [loadAll, repositoryId]);

  const matchesScope = useCallback(
    (value?: string) => !value || value === repositoryId || isCollectionScope(value),
    [repositoryId]
  );

  const jobs = useMemo(
    () =>
      (overview?.jobs ?? [])
        .filter((job) => matchesScope(job.repositoryId))
        .sort((a, b) => (b.startedAt || '').localeCompare(a.startedAt || '')),
    [overview, matchesScope]
  );

  const cursors = useMemo(
    () =>
      (overview?.cursors ?? [])
        .filter((cursor) => matchesScope(cursor.repositoryId))
        .sort((a, b) => a.streamKind.localeCompare(b.streamKind)),
    [overview, matchesScope]
  );

  const locks = useMemo(
    () =>
      (overview?.locks ?? [])
        .filter((lock) => matchesScope(lock.repositoryId))
        .sort((a, b) => a.jobKind.localeCompare(b.jobKind)),
    [overview, matchesScope]
  );

  const tombstones = useMemo(
    () =>
      (overview?.tombstones ?? [])
        .filter((tombstone) => matchesScope(tombstone.repositoryId))
        .sort((a, b) => (b.dueAt || '').localeCompare(a.dueAt || '')),
    [overview, matchesScope]
  );

  const deadLetters = useMemo(
    () =>
      (overview?.deadLetters ?? [])
        .filter((deadLetter) => matchesScope(deadLetter.repositoryId))
        .sort((a, b) => (b.lastFailedAt || '').localeCompare(a.lastFailedAt || '')),
    [overview, matchesScope]
  );

  const hasRunningJobs = jobs.some((job) => job.status === 'RUNNING');

  useEffect(() => {
    if (!hasRunningJobs) {
      return;
    }

    const intervalId = window.setInterval(() => {
      void loadAll();
    }, 5000);

    return () => window.clearInterval(intervalId);
  }, [hasRunningJobs, loadAll]);

  const handleRefresh = async () => {
    await loadAll();
  };

  const handleConnectionProbe = async () => {
    setRunningAction('test-connection');
    try {
      const result = await service.testConnection();
      setConnectionStatus(result);
      setActionResult({
        kind: result.connected ? 'COMPLETED' : 'FAILED',
        message: result.message,
      });
      if (result.connected) {
        message.success(result.message);
      } else {
        message.warning(result.message);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      setActionResult({ kind: 'FAILED', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setRunningAction(null);
    }
  };

  const handleGovernanceLookup = async (
    repoId: string,
    objectIds: string[]
  ): Promise<PurviewGovernanceBulkItemView[]> => {
    setRunningAction('lookup-governance');
    try {
      const result = await service.lookupGovernanceBulk(repoId, objectIds);
      message.success(t('purviewManagement.messages.governanceLookupCompleted', { count: result.length }));
      return result;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      message.error(errorMessage);
      throw error;
    } finally {
      setRunningAction(null);
    }
  };

  const runJobAction = async (
    actionKey: string,
    runner: () => Promise<PurviewJobState | PurviewSchemaApplyJobResult>,
    successMessageKey: string
  ) => {
    setRunningAction(actionKey);
    try {
      const result = await runner();
      const actionMessage =
        'message' in result && result.message
          ? result.message
          : t(successMessageKey);
      setActionResult({
        kind: result.status,
        message: actionMessage,
        job: result,
      });

      if (result.status === 'FAILED') {
        message.error(result.errorSummary || actionMessage);
      } else if (result.status === 'REJECTED' || result.status === 'COMPLETED_WITH_ERRORS') {
        message.warning(result.errorSummary || actionMessage);
      } else {
        message.success(actionMessage);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      setActionResult({ kind: 'FAILED', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setRunningAction(null);
      await loadAll();
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <ConnectionCard
        repositoryId={repositoryId}
        overview={overview}
        schemaDiff={schemaDiff}
        connectionStatus={connectionStatus}
        loadError={loadError}
        refreshing={refreshing}
        runningAction={runningAction}
        actionResult={actionResult}
        jobCount={jobs.length}
        cursorCount={cursors.length}
        deadLetterCount={deadLetters.length}
        tombstoneCount={tombstones.length}
        onRefresh={handleRefresh}
        onConnectionProbe={handleConnectionProbe}
      />

      <SchemaCard schemaDiff={schemaDiff} />

      <SyncActionsCard
        runningAction={runningAction}
        onApplySchema={() =>
          runJobAction(
            'apply-schema',
            () => service.applyTypeDefinitions(),
            'purviewManagement.messages.schemaApplied'
          )
        }
        onFullSync={() =>
          runJobAction(
            'full-sync',
            () => service.startFullSync(repositoryId),
            'purviewManagement.messages.fullSyncStarted'
          )
        }
        onIncrementalSync={() =>
          runJobAction(
            'incremental-sync',
            () => service.startIncrementalSync(repositoryId),
            'purviewManagement.messages.incrementalSyncStarted'
          )
        }
        onReconcileTypes={() =>
          runJobAction(
            'reconcile-types',
            () => service.reconcileTypes(repositoryId),
            'purviewManagement.messages.typeReconciliationStarted'
          )
        }
        onReconcileArchives={() =>
          runJobAction(
            'reconcile-archives',
            () => service.reconcileArchives(repositoryId),
            'purviewManagement.messages.archiveReconciliationStarted'
          )
        }
        onReconcileCloudMetadata={() =>
          runJobAction(
            'reconcile-cloud',
            () => service.reconcileCloudMetadata(repositoryId),
            'purviewManagement.messages.cloudMetadataReconciliationStarted'
          )
        }
        onReconcileContainment={() =>
          runJobAction(
            'reconcile-containment',
            () => service.reconcileContainment(repositoryId),
            'purviewManagement.messages.containmentReconciliationStarted'
          )
        }
        onResolveDeletes={() =>
          runJobAction(
            'resolve-deletes',
            () => service.resolveDeletes(repositoryId),
            'purviewManagement.messages.deleteResolutionStarted'
          )
        }
        onRetryFailed={() =>
          runJobAction(
            'retry-failed',
            () => service.retryFailed(repositoryId),
            'purviewManagement.messages.retryFailedStarted'
          )
        }
        onPurgeJobHistory={async () => {
          setRunningAction('purge-job-history');
          try {
            const result = await service.purgeJobHistory(50);
            message.success(
              t('purviewManagement.messages.purgeJobHistoryCompleted', {
                count: result.purgedCount,
              })
            );
          } catch (error) {
            const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
            message.error(errorMessage);
          } finally {
            setRunningAction(null);
            await loadAll();
          }
        }}
      />

      <GovernanceLookupCard
        repositoryId={repositoryId}
        runningAction={runningAction}
        onLookup={handleGovernanceLookup}
      />

      <StateTablesCard
        jobs={jobs}
        cursors={cursors}
        deadLetters={deadLetters}
        tombstones={tombstones}
        locks={locks}
      />
    </Space>
  );
};
