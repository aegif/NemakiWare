import { Space, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { OidcSettingsTab } from './OidcSettingsTab';
import { GoogleAuthSettingsTab } from './GoogleAuthSettingsTab';
import { MicrosoftAuthSettingsTab } from './MicrosoftAuthSettingsTab';
import { SamlSettingsTab } from './SamlSettingsTab';
import { DirectorySyncSettingsTab } from './DirectorySyncSettingsTab';
import { PurviewSettingsTab } from './PurviewSettingsTab';

const { Title } = Typography;

interface IntegrationSettingsProps {
  repositoryId: string;
}

export function IntegrationSettings({ repositoryId: _repositoryId }: IntegrationSettingsProps) {
  const { t } = useTranslation();

  const items = [
    {
      key: 'oidc',
      label: t('integrationSettings.tabs.oidc'),
      children: <OidcSettingsTab />,
    },
    {
      key: 'google',
      label: t('integrationSettings.tabs.google'),
      children: <GoogleAuthSettingsTab />,
    },
    {
      key: 'microsoft',
      label: t('integrationSettings.tabs.microsoft'),
      children: <MicrosoftAuthSettingsTab />,
    },
    {
      key: 'saml',
      label: t('integrationSettings.tabs.saml'),
      children: <SamlSettingsTab />,
    },
    {
      key: 'directory-sync',
      label: t('integrationSettings.tabs.directorySync'),
      children: <DirectorySyncSettingsTab />,
    },
    {
      key: 'purview',
      label: <Space size={4}>{t('integrationSettings.tabs.purview')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Beta</Tag></Space>,
      children: <PurviewSettingsTab />,
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Title level={3}>{t('integrationSettings.title')}</Title>
      <Tabs defaultActiveKey="oidc" items={items} />
    </div>
  );
}
