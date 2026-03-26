import React from 'react';
import { Button, Card, Modal, Space, Typography } from 'antd';
import { DeleteOutlined, SyncOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Text } = Typography;

interface SyncActionsCardProps {
  runningAction: string | null;
  onApplySchema: () => void;
  onFullSync: () => void;
  onIncrementalSync: () => void;
  onReconcileTypes: () => void;
  onReconcileArchives: () => void;
  onReconcileCloudMetadata: () => void;
  onReconcileContainment: () => void;
  onResolveDeletes: () => void;
  onRetryFailed: () => void;
  onPurgeJobHistory: () => void;
}

export const SyncActionsCard: React.FC<SyncActionsCardProps> = ({
  runningAction,
  onApplySchema,
  onFullSync,
  onIncrementalSync,
  onReconcileTypes,
  onReconcileArchives,
  onReconcileCloudMetadata,
  onReconcileContainment,
  onResolveDeletes,
  onRetryFailed,
  onPurgeJobHistory,
}) => {
  const { t } = useTranslation();

  return (
    <Card title={t('purviewManagement.sections.actions')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Text strong>{t('purviewManagement.actionGroups.bootstrap')}</Text>
          <div style={{ marginTop: 8 }}>
            <Space wrap>
              <Button
                type="primary"
                onClick={onApplySchema}
                loading={runningAction === 'apply-schema'}
              >
                {t('purviewManagement.actions.applyTypeDefinitions')}
              </Button>
            </Space>
          </div>
        </div>

        <div>
          <Text strong>{t('purviewManagement.actionGroups.sync')}</Text>
          <div style={{ marginTop: 8 }}>
            <Space wrap>
              <Button
                type="primary"
                icon={<SyncOutlined />}
                onClick={onFullSync}
                loading={runningAction === 'full-sync'}
              >
                {t('purviewManagement.actions.fullSync')}
              </Button>
              <Button
                onClick={onIncrementalSync}
                loading={runningAction === 'incremental-sync'}
              >
                {t('purviewManagement.actions.incrementalSync')}
              </Button>
            </Space>
          </div>
        </div>

        <div>
          <Text strong>{t('purviewManagement.actionGroups.reconciliation')}</Text>
          <div style={{ marginTop: 8 }}>
            <Space wrap>
              <Button
                onClick={onReconcileTypes}
                loading={runningAction === 'reconcile-types'}
              >
                {t('purviewManagement.actions.reconcileTypes')}
              </Button>
              <Button
                onClick={onReconcileArchives}
                loading={runningAction === 'reconcile-archives'}
              >
                {t('purviewManagement.actions.reconcileArchives')}
              </Button>
              <Button
                onClick={onReconcileCloudMetadata}
                loading={runningAction === 'reconcile-cloud'}
              >
                {t('purviewManagement.actions.reconcileCloudMetadata')}
              </Button>
              <Button
                onClick={onReconcileContainment}
                loading={runningAction === 'reconcile-containment'}
              >
                {t('purviewManagement.actions.reconcileContainment')}
              </Button>
              <Button
                onClick={onResolveDeletes}
                loading={runningAction === 'resolve-deletes'}
              >
                {t('purviewManagement.actions.resolveDeletes')}
              </Button>
              <Button
                onClick={onRetryFailed}
                loading={runningAction === 'retry-failed'}
              >
                {t('purviewManagement.actions.retryFailed')}
              </Button>
            </Space>
          </div>
        </div>

        <div>
          <Text strong>{t('purviewManagement.actionGroups.maintenance')}</Text>
          <div style={{ marginTop: 8 }}>
            <Space wrap>
              <Button
                danger
                icon={<DeleteOutlined />}
                onClick={() => {
                  Modal.confirm({
                    title: t('purviewManagement.purgeJobHistory.confirmTitle'),
                    content: t('purviewManagement.purgeJobHistory.confirmContent'),
                    okText: t('common.ok'),
                    cancelText: t('common.cancel'),
                    onOk: onPurgeJobHistory,
                  });
                }}
                loading={runningAction === 'purge-job-history'}
              >
                {t('purviewManagement.actions.purgeJobHistory')}
              </Button>
            </Space>
          </div>
        </div>
      </Space>
    </Card>
  );
};
