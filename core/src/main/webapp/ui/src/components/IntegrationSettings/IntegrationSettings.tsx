import { Space, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { OidcSettingsTab } from './OidcSettingsTab';
import { GoogleAuthSettingsTab } from './GoogleAuthSettingsTab';
import { MicrosoftAuthSettingsTab } from './MicrosoftAuthSettingsTab';
import { SamlSettingsTab } from './SamlSettingsTab';
import { DirectorySyncSettingsTab } from './DirectorySyncSettingsTab';
import { PurviewSettingsTab } from './PurviewSettingsTab';
import { AtlasSettingsTab } from './AtlasSettingsTab';
import { DataplexSettingsTab } from './DataplexSettingsTab';
import { LineageSettingsTab } from './LineageSettingsTab';
import PropertyMappingSection from './PropertyMappingSection';

const { Title } = Typography;

interface IntegrationSettingsProps {
  repositoryId: string;
}

export function IntegrationSettings({ repositoryId }: IntegrationSettingsProps) {
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
    {
      key: 'atlas',
      label: <Space size={4}>{t('integrationSettings.tabs.atlas')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Beta</Tag></Space>,
      children: <AtlasSettingsTab />,
    },
    {
      key: 'dataplex',
      label: <Space size={4}>{t('integrationSettings.tabs.dataplex')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Beta</Tag></Space>,
      children: <DataplexSettingsTab />,
    },
    {
      key: 'lineage',
      label: <Space size={4}>{t('integrationSettings.tabs.lineage')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Beta</Tag></Space>,
      children: <LineageSettingsTab />,
    },
    {
      key: 'property-mapping',
      label: <Space size={4}>{t('integrationSettings.tabs.propertyMapping')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>Beta</Tag></Space>,
      children: <PropertyMappingSection repositoryId={repositoryId} />,
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Title level={3}>{t('integrationSettings.title')}</Title>
      <Tabs defaultActiveKey="oidc" items={items} />
    </div>
  );
}
