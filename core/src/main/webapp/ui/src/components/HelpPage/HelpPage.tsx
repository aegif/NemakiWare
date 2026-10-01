import React, { useState, useEffect } from 'react';
import { Tabs, Typography, Collapse, Descriptions, Alert, Table, Space, Steps, Image, Divider, Card } from 'antd';
import {
  FileOutlined, FolderOutlined, SearchOutlined, UploadOutlined,
  EyeOutlined, HistoryOutlined, LockOutlined,
  CloudOutlined, KeyOutlined, UserOutlined, GlobalOutlined,
  TeamOutlined, DatabaseOutlined, SendOutlined,
  SyncOutlined, BarChartOutlined, SwapOutlined, ApiOutlined,
  LoginOutlined, QuestionCircleOutlined, DeleteOutlined,
  CheckCircleOutlined, RobotOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { fetchAdapterRegistry, AdapterDescriptor } from '../../services/externalIngest';

const { Title, Paragraph, Text } = Typography;

const HelpImage: React.FC<{ src: string; alt: string }> = ({ src, alt }) => {
  const { t } = useTranslation();
  return (
    <div style={{ margin: '16px 0', textAlign: 'center' }}>
      <Image
        src={`/core/ui/help-images/${src}`}
        alt={alt}
        style={{ maxWidth: '100%', border: '1px solid #d9d9d9', borderRadius: 8 }}
        preview={{ cover: t('help.imageZoom') }}
      />
      <div style={{ color: '#888', fontSize: 12, marginTop: 4 }}>{alt}</div>
    </div>
  );
};

/**
 * HelpPage — accessible both before and after login.
 * When used as a public route (pre-login), authToken is null and
 * only the user guide is shown.
 */
const HelpPage: React.FC = () => {
  const { t } = useTranslation();
  // AuthProvider wraps the entire app (App → AuthProvider → AppContent),
  // so useAuth() is always available here — even on the unauthenticated
  // /help path, where authToken is simply null.
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  return (
    <div style={{ maxWidth: 960, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>
        <QuestionCircleOutlined style={{ marginRight: 8 }} />
        {t('help.title')}
      </Title>
      <Paragraph type="secondary">
        {t('help.imageHint')}
      </Paragraph>

      <Tabs
        defaultActiveKey="user"
        items={[
          {
            key: 'user',
            label: t('help.userGuide'),
            children: <UserGuide />,
          },
          ...(isAdmin ? [{
            key: 'admin',
            label: t('help.adminGuide'),
            children: <AdminGuide />,
          }] : []),
        ]}
      />
    </div>
  );
};

/* ────────────── ユーザーガイド ────────────── */

const UserGuide: React.FC = () => {
  const { t } = useTranslation();

  const sections = [
    {
      key: 'login',
      label: <Space><LoginOutlined /><Text strong>{t('help.user.loginTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="00-login.png" alt={t('help.user.loginScreenAlt')} />
          <Steps
            direction="vertical"
            size="small"
            items={[
              { title: t('help.user.loginStep1'), description: t('help.user.loginStep1Desc') },
              { title: t('help.user.loginStep2'), description: t('help.user.loginStep2Desc') },
              { title: t('help.user.loginStep3'), description: t('help.user.loginStep3Desc') },
            ]}
          />
          <Divider />
          <Text strong>{t('help.user.authMethods')}</Text>
          <Alert type="info" showIcon style={{ marginTop: 8, marginBottom: 8 }}
            message={t('help.user.authConditional')}
          />
          <Descriptions bordered column={1} size="small" style={{ marginTop: 8 }}>
            <Descriptions.Item label={t('help.user.authPw')}>{t('help.user.authPwDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.user.authPasskey')}>{t('help.user.authPasskeyDesc')}</Descriptions.Item>
            <Descriptions.Item label="OIDC">{t('help.user.authOIDCDesc')}</Descriptions.Item>
            <Descriptions.Item label="SAML">{t('help.user.authSAMLDesc')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'documents',
      label: <Space><FolderOutlined /><Text strong>{t('help.user.documentsTitle')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.user.documentsIntro')}</Paragraph>
          <HelpImage src="01-document-list.png" alt={t('help.user.documentsAlt')} />
          <Card size="small" title={t('help.user.screenLayout')}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={t('help.user.leftPane')}>{t('help.user.leftPaneDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.mainArea')}>{t('help.user.mainAreaDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.toolbar')}>{t('help.user.toolbarDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.header')}>{t('help.user.headerDesc')}</Descriptions.Item>
            </Descriptions>
          </Card>
        </>
      ),
    },
    {
      key: 'folder',
      label: <Space><FolderOutlined /><Text strong>{t('help.user.folderTitle')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.folderCreate')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.folderStep1'), description: t('help.user.folderStep1Desc') },
            { title: t('help.user.folderStep2') },
            { title: t('help.user.folderStep3'), description: t('help.user.folderStep3Desc') },
            { title: t('help.user.folderStep4') },
          ]} />
          <Divider />
          <Title level={5}>{t('help.user.folderDelete')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.folderDelStep1') },
            { title: t('help.user.folderDelStep2') },
            { title: t('help.user.folderDelStep3'), description: t('help.user.folderDelStep3Desc') },
          ]} />
        </>
      ),
    },
    {
      key: 'upload',
      label: <Space><UploadOutlined /><Text strong>{t('help.user.uploadTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="03-upload-dialog.png" alt={t('help.user.uploadAlt')} />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.uploadStep1'), description: t('help.user.uploadStep1Desc') },
            { title: t('help.user.uploadStep2'), description: t('help.user.uploadStep2Desc') },
            { title: t('help.user.uploadStep3'), description: t('help.user.uploadStep3Desc') },
            { title: t('help.user.uploadStep4') },
          ]} />
          <Divider />
          <Text strong>{t('help.user.uploadFormats')}</Text>
          <Paragraph>{t('help.user.uploadFormatsOffice')}, {t('help.user.uploadFormatsMore')}</Paragraph>
        </>
      ),
    },
    {
      key: 'detail',
      label: <Space><EyeOutlined /><Text strong>{t('help.user.detailTitle')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.detailOverview')}</Title>
          <Paragraph>{t('help.user.detailIntro')}</Paragraph>
          <HelpImage src="04-document-detail.png" alt={t('help.user.detailAlt')} />
          <Card size="small" title={t('help.user.detailButtons')}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={t('help.user.btnBack')}>{t('help.user.btnBackDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnDownload')}>{t('help.user.btnDownloadDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnCheckout')}>{t('help.user.btnCheckoutDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnTypeChange')}>{t('help.user.btnTypeChangeDesc')}</Descriptions.Item>
              <Descriptions.Item label={t('help.user.btnPerms')}>{t('help.user.btnPermsDesc')}</Descriptions.Item>
            </Descriptions>
          </Card>
          <Divider />
          <Title level={5}>{t('help.user.previewTitle')}</Title>
          <HelpImage src="05-preview.png" alt={t('help.user.previewAlt')} />
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', format: 'PDF', method: t('help.user.prevPdf') },
              { key: '2', format: 'Word / Excel / PPT', method: t('help.user.prevOffice') },
              { key: '3', format: t('help.user.prevImageFmt'), method: t('help.user.prevImage') },
              { key: '4', format: t('help.user.prevTextFmt'), method: t('help.user.prevText') },
              { key: '5', format: t('help.user.prevVideoFmt'), method: t('help.user.prevVideo') },
            ]}
            columns={[
              { title: t('help.user.format'), dataIndex: 'format', width: 200 },
              { title: t('help.user.previewMethod'), dataIndex: 'method' },
            ]}
          />
          <Divider />
          <Title level={5}>{t('help.user.secondaryTitle')}</Title>
          <Paragraph>{t('help.user.secondaryDesc')}</Paragraph>
          <HelpImage src="18-secondary-type.png" alt={t('help.user.secondaryAlt')} />
          <Divider />
          <Title level={5}>{t('help.user.relationshipTitle')}</Title>
          <Paragraph>{t('help.user.relationshipDesc')}</Paragraph>
          <HelpImage src="19-relationship.png" alt={t('help.user.relationshipAlt')} />
        </>
      ),
    },
    {
      key: 'versioning',
      label: <Space><HistoryOutlined /><Text strong>{t('help.user.versionTitle')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.versionCreate')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.verStep1') },
            { title: t('help.user.verStep2'), description: t('help.user.verStep2Desc') },
            { title: t('help.user.verStep3') },
            { title: t('help.user.verStep4'), description: t('help.user.verStep4Desc') },
          ]} />
          <Divider />
          <Title level={5}>{t('help.user.versionHistory')}</Title>
          <HelpImage src="06-version-history.png" alt={t('help.user.versionAlt')} />
          <Paragraph>{t('help.user.versionHistoryDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'search',
      label: <Space><SearchOutlined /><Text strong>{t('help.user.searchTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="07-search.png" alt={t('help.user.searchAlt')} />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.searchStep1'), description: t('help.user.searchStep1Desc') },
            { title: t('help.user.searchStep2') },
            { title: t('help.user.searchStep3') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.searchTip')}
            description={t('help.user.searchTipDesc')}
          />
        </>
      ),
    },
    {
      key: 'acl',
      label: <Space><LockOutlined /><Text strong>{t('help.user.aclTitle')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.user.aclIntro')}</Paragraph>
          <HelpImage src="16-permissions.png" alt={t('help.user.aclAlt')} />
          <Title level={5}>{t('help.user.aclLevels')}</Title>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="cmis:read">{t('help.user.aclRead')}</Descriptions.Item>
            <Descriptions.Item label="cmis:write">{t('help.user.aclWrite')}</Descriptions.Item>
            <Descriptions.Item label="cmis:all">{t('help.user.aclAll')}</Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.aclVsAdmin')}
            description={t('help.user.aclVsAdminDesc')}
          />
          <Divider />
          <Title level={5}>{t('help.user.aclChange')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.aclStep1'), description: t('help.user.aclStep1Desc') },
            { title: t('help.user.aclStep2') },
            { title: t('help.user.aclStep3') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message={t('help.user.aclReq')}
          />
          <Divider />
          <Title level={5}>{t('help.user.aclInherit')}</Title>
          <Paragraph>{t('help.user.aclInheritDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'cloud',
      label: <Space><CloudOutlined /><Text strong>{t('help.user.cloudTitle')}</Text></Space>,
      children: (
        <>
          <Title level={5}>{t('help.user.cloudImport')}</Title>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={t('help.user.cloudPrereq')}
            description={t('help.user.cloudPrereqDesc')}
          />
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.cloudStep1'), description: t('help.user.cloudStep1Desc') },
            { title: t('help.user.cloudStep2') },
            { title: t('help.user.cloudStep3') },
            { title: t('help.user.cloudStep4') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.cloudDupe')}
            description={t('help.user.cloudDupeDesc')}
          />
        </>
      ),
    },
    {
      key: 'passkey',
      label: <Space><KeyOutlined /><Text strong>{t('help.user.passkeyTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="17-account-settings.png" alt={t('help.user.passkeyAlt')} />
          <Title level={5}>{t('help.user.passkeyRegister')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.user.pkStep1') },
            { title: t('help.user.pkStep2') },
            { title: t('help.user.pkStep3') },
            { title: t('help.user.pkStep4'), description: t('help.user.pkStep4Desc') },
            { status: 'finish' as const, title: t('help.user.pkDone'), icon: <CheckCircleOutlined /> },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.user.passkeyReq')}
          />
        </>
      ),
    },
    {
      key: 'language',
      label: <Space><GlobalOutlined /><Text strong>{t('help.user.langTitle')}</Text></Space>,
      children: (
        <Paragraph>{t('help.user.langDesc')}</Paragraph>
      ),
    },
  ];

  return <Collapse defaultActiveKey={['login', 'documents']} items={sections} />;
};

/* ────────────── 管理者ガイド ────────────── */

const AdminGuide: React.FC = () => {
  const { t } = useTranslation();

  // Adapter registry — single source of truth for adapter tables
  const [adapters, setAdapters] = useState<AdapterDescriptor[]>([]);
  useEffect(() => {
    fetchAdapterRegistry().then(setAdapters).catch(() => {});
  }, []);

  const sections = [
    {
      key: 'users',
      label: <Space><UserOutlined /><Text strong>{t('help.admin.usersTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="08-user-management.png" alt={t('help.admin.usersAlt')} />
          <Title level={5}>{t('help.admin.userCreate')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.userStep1') },
            { title: t('help.admin.userStep2') },
            { title: t('help.admin.userStep3'), description: t('help.admin.userStep3Desc') },
            { title: t('help.admin.userStep4'), description: t('help.admin.userStep4Desc') },
            { title: t('help.admin.userStep5') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.userPwReset')}
            description={t('help.admin.userPwResetDesc')}
          />
        </>
      ),
    },
    {
      key: 'groups',
      label: <Space><TeamOutlined /><Text strong>{t('help.admin.groupsTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="09-group-management.png" alt={t('help.admin.groupsAlt')} />
          <Paragraph>{t('help.admin.groupsDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'types',
      label: <Space><FileOutlined /><Text strong>{t('help.admin.typesTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="10-type-management.png" alt={t('help.admin.typesAlt')} />
          <Paragraph>{t('help.admin.typesIntro')}</Paragraph>
          <Title level={5}>{t('help.admin.typeCreate')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.typeStep1') },
            { title: t('help.admin.typeStep2') },
            { title: t('help.admin.typeStep3'), description: t('help.admin.typeStep3Desc') },
            { title: t('help.admin.typeStep4'), description: t('help.admin.typeStep4Desc') },
            { title: t('help.admin.typeStep5') },
          ]} />
        </>
      ),
    },
    {
      key: 'archive',
      label: <Space><DeleteOutlined /><Text strong>{t('help.admin.archiveTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="11-archive.png" alt={t('help.admin.archiveAlt')} />
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label={t('help.admin.archRestore')}>{t('help.admin.archRestoreDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archDownload')}>{t('help.admin.archDownloadDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archForce')}>{t('help.admin.archForceDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.archExtend')}>{t('help.admin.archExtendDesc')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'solr',
      label: <Space><DatabaseOutlined /><Text strong>{t('help.admin.solrTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="12-solr-management.png" alt={t('help.admin.solrAlt')} />
          <Paragraph>{t('help.admin.solrIntro')}</Paragraph>
          <Title level={5}>{t('help.admin.solrFulltext')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.solrStep1')}>{t('help.admin.solrStep1Desc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrFolderReindex')}>{t('help.admin.solrFolderReindexDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrClear')}>{t('help.admin.solrClearDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrOptimize')}>{t('help.admin.solrOptimizeDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrCancel')}>{t('help.admin.solrCancelDesc')}</Descriptions.Item>
          </Descriptions>
          <Title level={5} style={{ marginTop: 16 }}>{t('help.admin.solrRag')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.solrStep2')}>{t('help.admin.solrStep2Desc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrRagClear')}>{t('help.admin.solrRagClearDesc')}</Descriptions.Item>
            <Descriptions.Item label={t('help.admin.solrRagCancel')}>{t('help.admin.solrRagCancelDesc')}</Descriptions.Item>
          </Descriptions>
        </>
      ),
    },
    {
      key: 'ingest',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ingestTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="13-integration-settings.png" alt={t('help.admin.ingestAlt')} />
          <Paragraph>{t('help.admin.ingestIntro')}</Paragraph>

          {/* --- 概念説明 --- */}
          <Title level={5}>{t('help.admin.ingestConcepts')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestConceptConnector')}>
              {t('help.admin.ingestConceptConnectorDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptProfile')}>
              {t('help.admin.ingestConceptProfileDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptArchetype')}>
              {t('help.admin.ingestConceptArchetypeDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptScheduler')}>
              {t('help.admin.ingestConceptSchedulerDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptCircuitBreaker')}>
              {t('help.admin.ingestConceptCircuitBreakerDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestConceptIdempotency')}>
              {t('help.admin.ingestConceptIdempotencyDesc')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- 対応アダプタ一覧 --- */}
          {/* --- 対応アダプタ一覧（AdapterRegistry API から動的生成） --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestAdapters')}</Title>
          <Table size="small" pagination={false}
            dataSource={adapters
              .filter(a => a.sourceSystem !== 'google_drive' && a.sourceSystem !== 'onedrive')
              .map(a => ({
                key: a.sourceSystem,
                adapter: a.displayName,
                archetype: a.archetype,
                params: [...a.requiredParams, ...a.optionalParams].filter(k => k !== 'limit').join(', ') || '-',
              }))}
            columns={[
              { title: t('help.admin.adapterCol'), dataIndex: 'adapter', width: 120 },
              { title: t('help.admin.ingestArchetypeCol'), dataIndex: 'archetype', width: 170 },
              { title: t('help.admin.ingestParamsCol'), dataIndex: 'params' },
            ]}
          />

          {/* --- コネクタ登録手順 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestConnectorSetup')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestConnStep1') },
            { title: t('help.admin.ingestConnStep2') },
            { title: t('help.admin.ingestConnStep3'), description: t('help.admin.ingestConnStep3Desc') },
            { title: t('help.admin.ingestConnStep4'), description: t('help.admin.ingestConnStep4Desc') },
            { title: t('help.admin.ingestConnStep5'), description: t('help.admin.ingestConnStep5Desc') },
            { title: t('help.admin.ingestConnStep6'), description: t('help.admin.ingestConnStep6Desc') },
            { title: t('help.admin.ingestConnStep7') },
          ]} />
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message={t('help.admin.ingestConnNote')}
            description={t('help.admin.ingestConnNoteDesc')}
          />

          {/* --- インポートプロファイル設定 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestProfileSetup')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestProfStep1') },
            { title: t('help.admin.ingestProfStep2') },
            { title: t('help.admin.ingestProfStep3'), description: t('help.admin.ingestProfStep3Desc') },
            { title: t('help.admin.ingestProfStep3b'), description: t('help.admin.ingestProfStep3bDesc') },
            { title: t('help.admin.ingestProfStep4'), description: t('help.admin.ingestProfStep4Desc') },
            { title: t('help.admin.ingestProfStep5'), description: t('help.admin.ingestProfStep5Desc') },
            { title: t('help.admin.ingestProfStep6'), description: t('help.admin.ingestProfStep6Desc') },
            { title: t('help.admin.ingestProfStep7'), description: t('help.admin.ingestProfStep7Desc') },
          ]} />

          {/* --- ポリシー説明 --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestPolicies')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestDedupePolicy')}>
              {t('help.admin.ingestDedupePolicyDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestUpdatePolicy')}>
              {t('help.admin.ingestUpdatePolicyDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestVersionPolicy')}>
              {t('help.admin.ingestVersionPolicyDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDedupeMatchBy')}>
              {t('help.admin.ingestDedupeMatchByDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestAclPolicy')}>
              {t('help.admin.ingestAclPolicyDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestPreserveEml')}>
              {t('help.admin.ingestPreserveEmlDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestClassification')}>
              {t('help.admin.ingestClassificationDesc')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- フォルダ管理者への委譲 (3.1.1-RC3 追加) --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestDelegation')}</Title>
          <Paragraph>
            {t('help.admin.ingestDelegationIntro')}
          </Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestDelegationField1')}>
              {t('help.admin.ingestDelegationField1Desc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDelegationField2')}>
              {t('help.admin.ingestDelegationField2Desc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDelegationField3')}>
              {t('help.admin.ingestDelegationField3Desc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDelegationField4')}>
              {t('help.admin.ingestDelegationField4Desc')}
            </Descriptions.Item>
          </Descriptions>
          <Alert
            type="info"
            showIcon
            style={{ marginTop: 12 }}
            message={t('help.admin.ingestDelegationRunbook')}
            description={
              <ol style={{ margin: 0, paddingLeft: 20 }}>
                <li>{t('help.admin.ingestDelegationRunbook1')}</li>
                <li>{t('help.admin.ingestDelegationRunbook2')}</li>
                <li>{t('help.admin.ingestDelegationRunbook3')}</li>
                <li>{t('help.admin.ingestDelegationRunbook4')}</li>
                <li>{t('help.admin.ingestDelegationRunbook5')}</li>
              </ol>
            }
          />
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 12 }}
            message={t('help.admin.ingestDelegationLimits')}
            description={t('help.admin.ingestDelegationLimitsDesc')}
          />

          {/* --- 委譲の運用フロー (step-by-step) --- */}
          <Title level={5} style={{ marginTop: 16 }}>
            {t('help.admin.ingestDelegationOps')}
          </Title>
          <Paragraph type="secondary">
            {t('help.admin.ingestDelegationOpsIntro')}
          </Paragraph>
          <Steps direction="vertical" size="small" items={[
            {
              title: t('help.admin.ingestDelegationOpsStep1'),
              description: t('help.admin.ingestDelegationOpsStep1Desc'),
            },
            {
              title: t('help.admin.ingestDelegationOpsStep2'),
              description: t('help.admin.ingestDelegationOpsStep2Desc'),
            },
            {
              title: t('help.admin.ingestDelegationOpsStep3'),
              description: t('help.admin.ingestDelegationOpsStep3Desc'),
            },
            {
              title: t('help.admin.ingestDelegationOpsStep4'),
              description: t('help.admin.ingestDelegationOpsStep4Desc'),
            },
            {
              title: t('help.admin.ingestDelegationOpsStep5'),
              description: t('help.admin.ingestDelegationOpsStep5Desc'),
            },
            {
              title: t('help.admin.ingestDelegationOpsStep6'),
              description: t('help.admin.ingestDelegationOpsStep6Desc'),
            },
          ]} />
          <Alert
            type="info"
            showIcon
            style={{ marginTop: 12 }}
            message={t('help.admin.ingestDelegationOpsAudit')}
            description={
              <pre style={{ fontSize: 11, margin: 0, padding: 8, background: '#f5f5f5', overflowX: 'auto' }}>
{t('help.admin.ingestDelegationOpsAuditQuery')}
              </pre>
            }
          />

          {/* --- 実行パターン --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestExecution')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestExecScheduled')}>
              {t('help.admin.ingestExecScheduledDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecManual')}>
              {t('help.admin.ingestExecManualDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecWebhook')}>
              {t('help.admin.ingestExecWebhookDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestExecIdle')}>
              {t('help.admin.ingestExecIdleDesc')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- アダプタ固有パラメータ --- */}
          {/* --- アダプタ固有パラメータ（AdapterRegistry API から動的生成） --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.ingestSchedulerParams')}</Title>
          <Paragraph>{t('help.admin.ingestSchedulerParamsDesc')}</Paragraph>
          <Table size="small" pagination={false}
            dataSource={adapters
              .filter(a => a.sourceSystem !== 'google_drive' && a.sourceSystem !== 'onedrive')
              .map(a => ({
                key: a.sourceSystem,
                adapter: a.displayName,
                required: a.requiredParams.length > 0 ? a.requiredParams.join(', ') : '-',
                optional: a.optionalParams.join(', ') || '-',
                example: a.paramsExample,
              }))}
            columns={[
              { title: t('help.admin.adapterCol'), dataIndex: 'adapter', width: 100 },
              { title: t('help.admin.ingestRequiredCol'), dataIndex: 'required', width: 160 },
              { title: t('help.admin.ingestOptionalCol'), dataIndex: 'optional', width: 120 },
              { title: t('help.admin.ingestExampleCol'), dataIndex: 'example' },
            ]}
          />

          {/* --- チェックポイントとジョブ管理 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestJobManagement')}</Title>
          <Paragraph>{t('help.admin.ingestJobManagementDesc')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestJobStatus')}>
              {t('help.admin.ingestJobStatusDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestCheckpoint')}>
              {t('help.admin.ingestCheckpointDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestContentHash')}>
              {t('help.admin.ingestContentHashDesc')}
            </Descriptions.Item>
          </Descriptions>

          {/* --- DLQ --- */}
          <Title level={5} style={{ marginTop: 24 }}>{t('help.admin.dlq')}</Title>
          <Paragraph>{t('help.admin.ingestDlqIntro')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.ingestDlqTransient')}>
              {t('help.admin.ingestDlqTransientDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.ingestDlqPermanent')}>
              {t('help.admin.ingestDlqPermanentDesc')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.ingestDlqRetry')}
            description={t('help.admin.ingestDlqRetryDesc')}
          />

          {/* --- 具体例 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.ingestExample')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.ingestExStep1'), description: t('help.admin.ingestExStep1Desc') },
            { title: t('help.admin.ingestExStep2'), description: t('help.admin.ingestExStep2Desc') },
            { title: t('help.admin.ingestExStep3'), description: t('help.admin.ingestExStep3Desc') },
            { title: t('help.admin.ingestExStep4'), description: t('help.admin.ingestExStep4Desc') },
            { title: t('help.admin.ingestExStep5'), description: t('help.admin.ingestExStep5Desc') },
          ]} />
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.ingestRateLimit')}
            description={t('help.admin.ingestRateLimitDesc')}
          />
        </>
      ),
    },
    {
      key: 'audit',
      label: <Space><BarChartOutlined /><Text strong>{t('help.admin.auditTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="14-audit-dashboard.png" alt={t('help.admin.auditAlt')} />
          <Paragraph>{t('help.admin.auditDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'webhook',
      label: <Space><SendOutlined /><Text strong>{t('help.admin.webhookTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="20-webhook.png" alt={t('help.admin.webhookAlt')} />
          <Paragraph>{t('help.admin.webhookDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'sync',
      label: <Space><SyncOutlined /><Text strong>{t('help.admin.syncTitle')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.syncDesc')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.syncGoogle')}>
              {t('help.admin.syncGoogleDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.syncMicrosoft')}>
              {t('help.admin.syncMicrosoftDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.syncLdap')}>
              {t('help.admin.syncLdapDesc')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.syncScheduleNote')}
            description={t('help.admin.syncScheduleNoteDesc')}
          />
        </>
      ),
    },
    {
      key: 'importexport',
      label: <Space><SwapOutlined /><Text strong>{t('help.admin.ieTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="22-import-export.png" alt={t('help.admin.ieAlt')} />
          <Paragraph>{t('help.admin.ieDesc')}</Paragraph>
        </>
      ),
    },
    {
      key: 'config',
      label: <Space><DatabaseOutlined /><Text strong>{t('help.admin.configTitle')}</Text></Space>,
      children: (
        <>
          <HelpImage src="21-config-viewer.png" alt={t('help.admin.configAlt')} />
          <Paragraph>{t('help.admin.configDesc')}</Paragraph>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.configNote')}
          />
        </>
      ),
    },
    {
      key: 'api',
      label: <Space><ApiOutlined /><Text strong>{t('help.admin.apiTitle')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.apiDesc')}</Paragraph>
          <Alert type="warning" showIcon
            message={t('help.admin.apiCsrf')}
            description={t('help.admin.apiCsrfDesc')}
          />
        </>
      ),
    },
    {
      key: 'mcp',
      label: <Space><RobotOutlined /><Text strong>{t('help.admin.mcpTitle')}</Text></Space>,
      children: (
        <>
          <Paragraph>{t('help.admin.mcpIntro')}</Paragraph>

          {/* --- エンドポイント --- */}
          <Title level={5}>{t('help.admin.mcpEndpoints')}</Title>
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', path: '/core/mcp/message', method: 'POST', desc: t('help.admin.mcpEndpointMessage') },
              { key: '2', path: '/core/mcp/info', method: 'GET', desc: t('help.admin.mcpEndpointInfo') },
              { key: '3', path: '/core/mcp/health', method: 'GET', desc: t('help.admin.mcpEndpointHealth') },
            ]}
            columns={[
              { title: t('help.admin.mcpPathCol'), dataIndex: 'path', width: 220 },
              { title: t('help.admin.mcpMethodCol'), dataIndex: 'method', width: 80 },
              { title: t('help.admin.mcpDescCol'), dataIndex: 'desc' },
            ]}
          />

          {/* --- 各クライアント接続手順 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpClients')}</Title>
          <Paragraph>{t('help.admin.mcpClientsIntro')}</Paragraph>
          <Alert type="info" showIcon style={{ marginBottom: 16 }}
            message={t('help.admin.mcpAuthNote')}
            description={t('help.admin.mcpAuthNoteDesc')}
          />
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message={t('help.admin.mcpToolsListNote')}
            description={t('help.admin.mcpToolsListNoteDesc')}
          />

          {/* --- Claude Desktop --- */}
          <Title level={5}>{t('help.admin.mcpClaudeDesktop')}</Title>
          <Paragraph>{t('help.admin.mcpCdIntro')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCdStep1'), description: t('help.admin.mcpCdStep1Desc') },
            { title: t('help.admin.mcpCdStep2'), description: t('help.admin.mcpCdStep2Desc') },
            { title: t('help.admin.mcpCdStep3'), description: t('help.admin.mcpCdStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpCdConfig')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- Claude Code --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpClaudeCode')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCcStep1'), description: t('help.admin.mcpCcStep1Desc') },
            { title: t('help.admin.mcpCcStep2'), description: t('help.admin.mcpCcStep2Desc') },
            { title: t('help.admin.mcpCcStep3'), description: t('help.admin.mcpCcStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpConfigExample')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "url": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- Cursor --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpCursor')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpCursorStep1'), description: t('help.admin.mcpCursorStep1Desc') },
            { title: t('help.admin.mcpCursorStep2'), description: t('help.admin.mcpCursorStep2Desc') },
            { title: t('help.admin.mcpCursorStep3'), description: t('help.admin.mcpCursorStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpCursorConfig')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "url": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- Windsurf --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpWindsurf')}</Title>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpWindsurfStep1'), description: t('help.admin.mcpWindsurfStep1Desc') },
            { title: t('help.admin.mcpWindsurfStep2'), description: t('help.admin.mcpWindsurfStep2Desc') },
            { title: t('help.admin.mcpWindsurfStep3'), description: t('help.admin.mcpWindsurfStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpWindsurfConfig')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "serverUrl": "http://localhost:8080/core/mcp/message",
      "headers": {
        "Authorization": "Basic YWRtaW46YWRtaW4="
      }
    }
  }
}`}</pre>
          </Card>

          {/* --- ChatGPT Desktop --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpChatGPT')}</Title>
          <Paragraph>{t('help.admin.mcpChatGPTIntro')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpChatGPTStep1'), description: t('help.admin.mcpChatGPTStep1Desc') },
            { title: t('help.admin.mcpChatGPTStep2'), description: t('help.admin.mcpChatGPTStep2Desc') },
            { title: t('help.admin.mcpChatGPTStep3'), description: t('help.admin.mcpChatGPTStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpChatGPTConfig')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- Gemini CLI --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpGemini')}</Title>
          <Paragraph>{t('help.admin.mcpGeminiIntro')}</Paragraph>
          <Steps direction="vertical" size="small" items={[
            { title: t('help.admin.mcpGeminiStep1'), description: t('help.admin.mcpGeminiStep1Desc') },
            { title: t('help.admin.mcpGeminiStep2'), description: t('help.admin.mcpGeminiStep2Desc') },
            { title: t('help.admin.mcpGeminiStep3'), description: t('help.admin.mcpGeminiStep3Desc') },
          ]} />
          <Card size="small" title={t('help.admin.mcpGeminiConfig')} style={{ marginTop: 12 }}>
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{`{
  "mcpServers": {
    "nemakiware": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/core/mcp/message",
        "--header",
        "Authorization: Basic YWRtaW46YWRtaW4="
      ]
    }
  }
}`}</pre>
          </Card>

          {/* --- その他 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpOther')}</Title>
          <Paragraph>{t('help.admin.mcpOtherDesc')}</Paragraph>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.mcpOtherUrl')}>
              {t('help.admin.mcpOtherUrlValue', { host: t('help.admin.mcpOtherHost') })}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherTransport')}>
              {t('help.admin.mcpOtherTransportDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherAuthHeader')}>
              {t('help.admin.mcpOtherAuthHeaderValue')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpOtherProtocol')}>
              2024-11-05
            </Descriptions.Item>
          </Descriptions>
          <Alert type="warning" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.mcpStdioNote')}
            description={t('help.admin.mcpStdioNoteDesc')}
          />

          {/* --- 利用可能なツール --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpTools')}</Title>
          <Table size="small" pagination={false}
            dataSource={[
              { key: '1', tool: 'nemakiware_login', auth: '-', desc: t('help.admin.mcpToolLogin') },
              { key: '2', tool: 'nemakiware_apikey_login', auth: '-', desc: t('help.admin.mcpToolApikey') },
              { key: '3', tool: 'nemakiware_cloud_login', auth: '-', desc: t('help.admin.mcpToolCloud') },
              { key: '4', tool: 'nemakiware_cloud_login_status', auth: '-', desc: t('help.admin.mcpToolCloudStatus') },
              { key: '5', tool: 'nemakiware_logout', auth: '-', desc: t('help.admin.mcpToolLogout') },
              { key: '6', tool: 'nemakiware_search', auth: t('help.admin.mcpToolAuthReq'), desc: t('help.admin.mcpToolSearch') },
              { key: '7', tool: 'nemakiware_rag_search', auth: t('help.admin.mcpToolAuthReq'), desc: t('help.admin.mcpToolRag') },
              { key: '8', tool: 'nemakiware_similar_documents', auth: t('help.admin.mcpToolAuthReq'), desc: t('help.admin.mcpToolSimilar') },
              { key: '9', tool: 'nemakiware_get_document_content', auth: t('help.admin.mcpToolAuthReq'), desc: t('help.admin.mcpToolContent') },
            ]}
            columns={[
              { title: t('help.admin.mcpToolNameCol'), dataIndex: 'tool', width: 280 },
              { title: t('help.admin.mcpToolAuthCol'), dataIndex: 'auth', width: 50 },
              { title: t('help.admin.mcpDescCol'), dataIndex: 'desc' },
            ]}
          />

          {/* --- 使い方の例 --- */}
          <Divider />
          <Title level={5}>{t('help.admin.mcpUsage')}</Title>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('help.admin.mcpUsageSearch')}>
              {t('help.admin.mcpUsageSearchDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpUsageContent')}>
              {t('help.admin.mcpUsageContentDesc')}
            </Descriptions.Item>
            <Descriptions.Item label={t('help.admin.mcpUsageSimilar')}>
              {t('help.admin.mcpUsageSimilarDesc')}
            </Descriptions.Item>
          </Descriptions>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message={t('help.admin.mcpRagNote')}
            description={t('help.admin.mcpRagNoteDesc')}
          />
        </>
      ),
    },
  ];

  return (
    <>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message={t('help.admin.toggleNote')}
      />
      <Collapse defaultActiveKey={['users']} items={sections} />
    </>
  );
};

export default HelpPage;
