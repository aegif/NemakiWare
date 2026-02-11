import React, { useState, useEffect, useCallback } from 'react';
import { Button, Table, Modal, Input, Space, Typography, Tag, message, Popconfirm } from 'antd';
import { KeyOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { WebAuthnService, PasskeyCredential } from '../../services/webauthn';

const { Title, Text } = Typography;

interface PasskeyManagementProps {
  repositoryId: string;
}

const webauthnService = new WebAuthnService();

const PasskeyManagement: React.FC<PasskeyManagementProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [credentials, setCredentials] = useState<PasskeyCredential[]>([]);
  const [loading, setLoading] = useState(false);
  const [registerLoading, setRegisterLoading] = useState(false);
  const [displayName, setDisplayName] = useState('');
  const [showRegisterModal, setShowRegisterModal] = useState(false);

  const isSupported = webauthnService.isSupported();

  const loadCredentials = useCallback(async () => {
    setLoading(true);
    try {
      const creds = await webauthnService.listCredentials(repositoryId);
      setCredentials(creds);
    } catch (err: any) {
      message.error(err.message || t('passkey.messages.loadError'));
    } finally {
      setLoading(false);
    }
  }, [repositoryId, t]);

  useEffect(() => {
    if (isSupported) {
      loadCredentials();
    }
  }, [isSupported, loadCredentials]);

  const handleRegister = async () => {
    setRegisterLoading(true);
    try {
      // Step 1: Get registration options from server
      const options = await webauthnService.registerBegin(repositoryId);

      // Step 2: Create credential via browser WebAuthn API
      const credential = await navigator.credentials.create({
        publicKey: options,
      }) as PublicKeyCredential;

      if (!credential) {
        throw new Error('Credential creation cancelled');
      }

      // Step 3: Send credential to server for verification
      await webauthnService.registerComplete(repositoryId, credential, displayName || undefined);

      message.success(t('passkey.messages.registerSuccess'));
      setShowRegisterModal(false);
      setDisplayName('');
      loadCredentials();
    } catch (err: any) {
      if (err.name === 'NotAllowedError') {
        message.warning(t('passkey.messages.cancelled'));
      } else {
        message.error(err.message || t('passkey.messages.registerError'));
      }
    } finally {
      setRegisterLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await webauthnService.deleteCredential(repositoryId, id);
      message.success(t('passkey.messages.deleteSuccess'));
      loadCredentials();
    } catch (err: any) {
      message.error(err.message || t('passkey.messages.deleteError'));
    }
  };

  const columns = [
    {
      title: t('passkey.columns.name'),
      dataIndex: 'displayName',
      key: 'displayName',
      render: (text: string) => (
        <Space>
          <KeyOutlined />
          {text}
        </Space>
      ),
    },
    {
      title: t('passkey.columns.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (timestamp: number | null) => {
        if (!timestamp) return '-';
        return new Date(timestamp).toLocaleDateString();
      },
    },
    {
      title: t('passkey.columns.transports'),
      dataIndex: 'transports',
      key: 'transports',
      render: (transports: string[] | null) => {
        if (!transports || transports.length === 0) return '-';
        return transports.map(t => <Tag key={t}>{t}</Tag>);
      },
    },
    {
      title: t('passkey.columns.actions'),
      key: 'actions',
      render: (_: any, record: PasskeyCredential) => (
        <Popconfirm
          title={t('passkey.confirmDelete')}
          onConfirm={() => handleDelete(record.id)}
          okText={t('common.ok')}
          cancelText={t('common.cancel')}
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            {t('common.delete')}
          </Button>
        </Popconfirm>
      ),
    },
  ];

  if (!isSupported) {
    return (
      <div>
        <Title level={5}><KeyOutlined /> {t('passkey.title')}</Title>
        <Text type="warning">{t('passkey.notSupported')}</Text>
      </div>
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={5} style={{ margin: 0 }}><KeyOutlined /> {t('passkey.title')}</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setShowRegisterModal(true)}
        >
          {t('passkey.register')}
        </Button>
      </div>

      <Table
        dataSource={credentials}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        size="small"
        locale={{ emptyText: t('passkey.noCredentials') }}
      />

      <Modal
        title={t('passkey.registerModal.title')}
        open={showRegisterModal}
        onOk={handleRegister}
        onCancel={() => { setShowRegisterModal(false); setDisplayName(''); }}
        confirmLoading={registerLoading}
        okText={t('passkey.registerModal.ok')}
        cancelText={t('common.cancel')}
      >
        <p>{t('passkey.registerModal.description')}</p>
        <Input
          placeholder={t('passkey.registerModal.displayNamePlaceholder')}
          value={displayName}
          onChange={e => setDisplayName(e.target.value)}
          style={{ marginTop: 8 }}
        />
      </Modal>
    </div>
  );
};

export default PasskeyManagement;
