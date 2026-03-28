import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import LineageJournalBrowser from './LineageJournalBrowser';
import LineageJournalStats from './LineageJournalStats';
import LineageDeadLetterPanel from './LineageDeadLetterPanel';

export default function LineageJournalPage() {
  const { t } = useTranslation();

  return (
    <div style={{ padding: 24 }}>
      <h2>{t('integrationSettings.lineage.pageTitle')}</h2>
      <Tabs
        defaultActiveKey="events"
        items={[
          {
            key: 'events',
            label: t('integrationSettings.lineage.eventsTab'),
            children: <LineageJournalBrowser />,
          },
          {
            key: 'stats',
            label: t('integrationSettings.lineage.statsTab'),
            children: <LineageJournalStats />,
          },
          {
            key: 'dead-letters',
            label: t('integrationSettings.lineage.deadLetterTab'),
            children: <LineageDeadLetterPanel />,
          },
        ]}
      />
    </div>
  );
}
