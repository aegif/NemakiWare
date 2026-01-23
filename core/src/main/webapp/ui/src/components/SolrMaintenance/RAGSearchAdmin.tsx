/**
 * RAGSearchAdmin Component
 *
 * Admin-only RAG search interface with user simulation capability.
 * Allows administrators to test semantic search as different users
 * to verify ACL filtering is working correctly.
 */

import React, { useState, useEffect } from 'react';
import { Form, Select, Space, Typography, Alert } from 'antd';
import { UserOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { User } from '../../types/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { SemanticSearch } from '../SemanticSearch/SemanticSearch';

const { Text } = Typography;

interface RAGSearchAdminProps {
  repositoryId: string;
}

/**
 * Admin RAG Search component with user simulation capability.
 * This component should only be rendered within admin-restricted pages.
 * The backend validates admin privileges when simulateAsUserId is used.
 */
export const RAGSearchAdmin: React.FC<RAGSearchAdminProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { handleAuthError } = useAuth();

  const [users, setUsers] = useState<User[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string | undefined>();
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const cmisService = new CMISService(handleAuthError);

  // Load users list on mount
  useEffect(() => {
    if (repositoryId) {
      loadUsers();
    }
  }, [repositoryId]);

  const loadUsers = async () => {
    setLoadingUsers(true);
    setError(null);
    try {
      const userList = await cmisService.getUsers(repositoryId);
      setUsers(userList);
    } catch (err) {
      console.error('Failed to load users:', err);
      setError(t('rag.admin.loadUsersFailed'));
    } finally {
      setLoadingUsers(false);
    }
  };

  // Handle document click - navigate to document detail
  const handleDocumentClick = (documentId: string) => {
    navigate(`/documents/${documentId}`);
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      {/* User simulation selector */}
      <div style={{
        padding: 16,
        background: '#fafafa',
        borderRadius: 8,
        border: '1px solid #d9d9d9'
      }}>
        <Form layout="vertical">
          <Form.Item
            label={
              <Space>
                <UserOutlined />
                {t('rag.admin.simulateAs')}
                <Text type="secondary" style={{ fontWeight: 'normal' }}>
                  <InfoCircleOutlined style={{ marginRight: 4 }} />
                  {t('rag.admin.simulateDescription')}
                </Text>
              </Space>
            }
          >
            <Select
              allowClear
              showSearch
              placeholder={t('rag.admin.selectUser')}
              value={selectedUserId}
              onChange={setSelectedUserId}
              loading={loadingUsers}
              style={{ maxWidth: 400 }}
              optionFilterProp="label"
              options={users.map(u => ({
                value: u.id,
                label: `${u.name || u.id} (${u.id})`
              }))}
            />
          </Form.Item>
        </Form>

        {selectedUserId && (
          <Alert
            message={t('rag.admin.simulatingAs', { userId: selectedUserId })}
            type="info"
            showIcon
            style={{ marginTop: 8 }}
          />
        )}
      </div>

      {error && (
        <Alert
          message={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
        />
      )}

      {/* Semantic Search with simulation */}
      <SemanticSearch
        repositoryId={repositoryId}
        onDocumentClick={handleDocumentClick}
        simulateAsUserId={selectedUserId}
      />
    </Space>
  );
};

export default RAGSearchAdmin;
