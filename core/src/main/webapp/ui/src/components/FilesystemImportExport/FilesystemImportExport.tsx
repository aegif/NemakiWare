/**
 * FilesystemImportExport Component for NemakiWare React UI
 *
 * Admin-only component for importing/exporting content directly from/to
 * the application server's local filesystem without ZIP compression.
 * Uses the custom NemakiWare format with distributed JSON metadata files.
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Button,
  Space,
  message,
  Input,
  Alert,
  Tabs,
  Form,
  Spin,
  Descriptions,
  Typography,
  Select,
  Progress,
  Checkbox
} from 'antd';
import {
  ImportOutlined,
  ExportOutlined,
  FolderOutlined,
  ReloadOutlined,
  CheckCircleOutlined,
  WarningOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';

const { Text } = Typography;

/**
 * Get authentication headers using the same pattern as CmisService (MEDIUM 5).
 * Reads from localStorage to get the auth token.
 */
const getAuthHeaders = (): Record<string, string> => {
  try {
    const authData = localStorage.getItem('nemakiware_auth');
    if (authData) {
      const auth = JSON.parse(authData);
      if (auth.username && auth.token) {
        const credentials = btoa(`${auth.username}:dummy`);
        return {
          'Content-Type': 'application/json',
          'Authorization': `Basic ${credentials}`,
          'nemaki_auth_token': String(auth.token)
        };
      }
    }
  } catch (e) {
    console.error('Failed to get auth headers:', e);
  }
  return { 'Content-Type': 'application/json' };
};

interface FilesystemImportExportProps {
  repositoryId: string;
}

interface ImportExportResult {
  status: 'success' | 'partial' | 'error';
  foldersCreated?: number;
  documentsCreated?: number;
  foldersExported?: number;
  documentsExported?: number;
  targetPath?: string;
  errors?: string[];
  warnings?: string[];
  message?: string;
}

export const FilesystemImportExport: React.FC<FilesystemImportExportProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const { authToken, handleAuthError } = useAuth();
  const [loading, setLoading] = useState(false);
  const [folders, setFolders] = useState<CMISObject[]>([]);
  const [foldersLoading, setFoldersLoading] = useState(false);
  const [importResult, setImportResult] = useState<ImportExportResult | null>(null);
  const [exportResult, setExportResult] = useState<ImportExportResult | null>(null);
  const [importForm] = Form.useForm();
  const [exportForm] = Form.useForm();

  const cmisService = new CMISService(() => handleAuthError(null));

  // Load root folders for selection
  const loadFolders = useCallback(async () => {
    setFoldersLoading(true);
    try {
      const rootFolder = await cmisService.getRootFolder(repositoryId);
      if (rootFolder) {
        const children = await cmisService.getChildren(repositoryId, rootFolder.id);
        const folderList = children.filter(child => child.baseTypeId === 'cmis:folder');
        setFolders([rootFolder, ...folderList]);
      }
    } catch (error: unknown) {
      console.error('Failed to load folders:', error);
    } finally {
      setFoldersLoading(false);
    }
  }, [repositoryId]);

  useEffect(() => {
    loadFolders();
  }, [loadFolders]);

  const handleImport = async (values: { sourcePath: string; targetFolderId: string }) => {
    setLoading(true);
    setImportResult(null);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/importexport/filesystem/import/${values.targetFolderId}`,
        {
          method: 'POST',
          headers: getAuthHeaders(),
          body: JSON.stringify({ sourcePath: values.sourcePath })
        }
      );

      const result: ImportExportResult = await response.json();
      setImportResult(result);

      if (result.status === 'success') {
        message.success(t('filesystemImportExport.import.success'));
      } else if (result.status === 'partial') {
        message.warning(t('filesystemImportExport.import.partial'));
      } else {
        message.error(result.message || t('filesystemImportExport.import.error'));
      }
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('filesystemImportExport.import.error')}: ${errorMessage}`);
      setImportResult({ status: 'error', message: errorMessage });
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async (values: { targetPath: string; sourceFolderId: string; allowOverwrite?: boolean }) => {
    setLoading(true);
    setExportResult(null);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/importexport/filesystem/export/${values.sourceFolderId}`,
        {
          method: 'POST',
          headers: getAuthHeaders(),
          body: JSON.stringify({ 
            targetPath: values.targetPath,
            allowOverwrite: values.allowOverwrite || false
          })
        }
      );

      const result: ImportExportResult = await response.json();
      setExportResult(result);

      if (result.status === 'success') {
        message.success(t('filesystemImportExport.export.success'));
      } else if (result.status === 'partial') {
        message.warning(t('filesystemImportExport.export.partial'));
      } else {
        message.error(result.message || t('filesystemImportExport.export.error'));
      }
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('filesystemImportExport.export.error')}: ${errorMessage}`);
      setExportResult({ status: 'error', message: errorMessage });
    } finally {
      setLoading(false);
    }
  };

  const renderImportResult = () => {
    if (!importResult) return null;

    return (
      <Card size="small" style={{ marginTop: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label={t('filesystemImportExport.result.status')}>
            {importResult.status === 'success' && (
              <Text type="success"><CheckCircleOutlined /> {t('filesystemImportExport.result.success')}</Text>
            )}
            {importResult.status === 'partial' && (
              <Text type="warning"><WarningOutlined /> {t('filesystemImportExport.result.partial')}</Text>
            )}
            {importResult.status === 'error' && (
              <Text type="danger"><WarningOutlined /> {t('filesystemImportExport.result.error')}</Text>
            )}
          </Descriptions.Item>
          {importResult.foldersCreated !== undefined && (
            <Descriptions.Item label={t('filesystemImportExport.result.foldersCreated')}>
              {importResult.foldersCreated}
            </Descriptions.Item>
          )}
          {importResult.documentsCreated !== undefined && (
            <Descriptions.Item label={t('filesystemImportExport.result.documentsCreated')}>
              {importResult.documentsCreated}
            </Descriptions.Item>
          )}
        </Descriptions>
        {importResult.warnings && importResult.warnings.length > 0 && (
          <Alert
            type="warning"
            message={t('filesystemImportExport.result.warnings')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {importResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            }
            style={{ marginTop: 8 }}
          />
        )}
        {importResult.errors && importResult.errors.length > 0 && (
          <Alert
            type="error"
            message={t('filesystemImportExport.result.errors')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {importResult.errors.map((e, i) => <li key={i}>{e}</li>)}
              </ul>
            }
            style={{ marginTop: 8 }}
          />
        )}
      </Card>
    );
  };

  const renderExportResult = () => {
    if (!exportResult) return null;

    return (
      <Card size="small" style={{ marginTop: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label={t('filesystemImportExport.result.status')}>
            {exportResult.status === 'success' && (
              <Text type="success"><CheckCircleOutlined /> {t('filesystemImportExport.result.success')}</Text>
            )}
            {exportResult.status === 'partial' && (
              <Text type="warning"><WarningOutlined /> {t('filesystemImportExport.result.partial')}</Text>
            )}
            {exportResult.status === 'error' && (
              <Text type="danger"><WarningOutlined /> {t('filesystemImportExport.result.error')}</Text>
            )}
          </Descriptions.Item>
          {exportResult.foldersExported !== undefined && (
            <Descriptions.Item label={t('filesystemImportExport.result.foldersExported')}>
              {exportResult.foldersExported}
            </Descriptions.Item>
          )}
          {exportResult.documentsExported !== undefined && (
            <Descriptions.Item label={t('filesystemImportExport.result.documentsExported')}>
              {exportResult.documentsExported}
            </Descriptions.Item>
          )}
          {exportResult.targetPath && (
            <Descriptions.Item label={t('filesystemImportExport.result.targetPath')}>
              <Text code>{exportResult.targetPath}</Text>
            </Descriptions.Item>
          )}
        </Descriptions>
        {exportResult.errors && exportResult.errors.length > 0 && (
          <Alert
            type="error"
            message={t('filesystemImportExport.result.errors')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {exportResult.errors.map((e, i) => <li key={i}>{e}</li>)}
              </ul>
            }
            style={{ marginTop: 8 }}
          />
        )}
      </Card>
    );
  };

  const tabItems = [
    {
      key: 'import',
      label: (
        <span>
          <ImportOutlined />
          {t('filesystemImportExport.import.title')}
        </span>
      ),
      children: (
        <Card>
          <Alert
            message={t('filesystemImportExport.import.description')}
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
          />
          <Form
            form={importForm}
            layout="vertical"
            onFinish={handleImport}
          >
            <Form.Item
              name="sourcePath"
              label={t('filesystemImportExport.import.sourcePath')}
              rules={[{ required: true, message: t('filesystemImportExport.import.sourcePathRequired') }]}
              extra={t('filesystemImportExport.import.sourcePathHelp')}
            >
              <Input
                prefix={<FolderOutlined />}
                placeholder="/path/to/import/directory"
              />
            </Form.Item>
            <Form.Item
              name="targetFolderId"
              label={t('filesystemImportExport.import.targetFolder')}
              rules={[{ required: true, message: t('filesystemImportExport.import.targetFolderRequired') }]}
            >
              <Select
                loading={foldersLoading}
                placeholder={t('filesystemImportExport.import.selectFolder')}
                showSearch
                optionFilterProp="children"
              >
                {folders.map(folder => (
                  <Select.Option key={folder.id} value={folder.id}>
                    <FolderOutlined /> {folder.name}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item>
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<ImportOutlined />}
                  loading={loading}
                >
                  {t('filesystemImportExport.import.button')}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  onClick={loadFolders}
                  loading={foldersLoading}
                >
                  {t('filesystemImportExport.refreshFolders')}
                </Button>
              </Space>
            </Form.Item>
          </Form>
          {renderImportResult()}
        </Card>
      )
    },
    {
      key: 'export',
      label: (
        <span>
          <ExportOutlined />
          {t('filesystemImportExport.export.title')}
        </span>
      ),
      children: (
        <Card>
          <Alert
            message={t('filesystemImportExport.export.description')}
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
          />
          <Form
            form={exportForm}
            layout="vertical"
            onFinish={handleExport}
          >
            <Form.Item
              name="sourceFolderId"
              label={t('filesystemImportExport.export.sourceFolder')}
              rules={[{ required: true, message: t('filesystemImportExport.export.sourceFolderRequired') }]}
            >
              <Select
                loading={foldersLoading}
                placeholder={t('filesystemImportExport.export.selectFolder')}
                showSearch
                optionFilterProp="children"
              >
                {folders.map(folder => (
                  <Select.Option key={folder.id} value={folder.id}>
                    <FolderOutlined /> {folder.name}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item
              name="targetPath"
              label={t('filesystemImportExport.export.targetPath')}
              rules={[{ required: true, message: t('filesystemImportExport.export.targetPathRequired') }]}
              extra={t('filesystemImportExport.export.targetPathHelp')}
            >
              <Input
                prefix={<FolderOutlined />}
                placeholder="/path/to/export/directory"
              />
            </Form.Item>
            <Form.Item
              name="allowOverwrite"
              valuePropName="checked"
              initialValue={false}
            >
              <Checkbox>
                {t('filesystemImportExport.export.allowOverwrite')}
              </Checkbox>
            </Form.Item>
            <Form.Item>
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<ExportOutlined />}
                  loading={loading}
                >
                  {t('filesystemImportExport.export.button')}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  onClick={loadFolders}
                  loading={foldersLoading}
                >
                  {t('filesystemImportExport.refreshFolders')}
                </Button>
              </Space>
            </Form.Item>
          </Form>
          {renderExportResult()}
        </Card>
      )
    }
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card title={t('filesystemImportExport.title')}>
        <Alert
          message={t('filesystemImportExport.adminOnly')}
          description={t('filesystemImportExport.adminOnlyDescription')}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        <Tabs items={tabItems} />
      </Card>
    </div>
  );
};

export default FilesystemImportExport;
