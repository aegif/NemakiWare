import type { PurviewJobState } from '../../services/purviewAdmin';

export interface ActionResult {
  kind: string;
  message: string;
  job?: PurviewJobState;
}

export const statusColorMap: Record<string, string> = {
  COMPLETED: 'success',
  COMPLETED_WITH_ERRORS: 'warning',
  FAILED: 'error',
  REJECTED: 'warning',
  RUNNING: 'processing',
  PENDING: 'processing',
  ARCHIVED: 'blue',
  PURGED: 'default',
  ACTIVE: 'success',
};

export const formatTimestamp = (value?: string): string => {
  if (!value) {
    return '-';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
};

export const isCollectionScope = (repositoryId?: string): boolean =>
  !!repositoryId && repositoryId.startsWith('collection:');

export const parseGovernanceLookupObjectIds = (value: string): string[] => {
  const uniqueObjectIds = new Set<string>();
  for (const candidate of value.split(/[\n,]+/)) {
    const objectId = candidate.trim();
    if (objectId) {
      uniqueObjectIds.add(objectId);
    }
  }
  return Array.from(uniqueObjectIds);
};
