import { Radio, Alert, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

const { Paragraph, Text } = Typography;

/**
 * Whether this deployment records provenance.
 *
 * `undefined` means "do not change what is stored" — the shape the apply endpoint reads as
 * "the wizard did not ask". It matters because provenance cannot be reconstructed after the
 * fact: silently switching it off for a deployment that had it on is not recoverable.
 */
export type ProvenanceConfig = {
  journaled: boolean;
};

interface ProvenanceStepProps {
  value: ProvenanceConfig;
  onChange: (value: ProvenanceConfig) => void;
}

export function ProvenanceStep({ value, onChange }: ProvenanceStepProps) {
  const { t } = useTranslation();

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Paragraph>{t('setup.provenance.description')}</Paragraph>

      <Radio.Group
        value={value.journaled}
        onChange={(e) => onChange({ journaled: e.target.value })}
      >
        <Space direction="vertical">
          <Radio value={true}>
            <Text strong>{t('setup.provenance.on')}</Text>
            <br />
            <Text type="secondary">{t('setup.provenance.onHint')}</Text>
          </Radio>
          <Radio value={false}>
            <Text strong>{t('setup.provenance.off')}</Text>
            <br />
            <Text type="secondary">{t('setup.provenance.offHint')}</Text>
          </Radio>
        </Space>
      </Radio.Group>

      {/* Said before the choice is made, not after. Storage growth is the cost an operator
          cannot see until it has already happened. */}
      <Alert
        type="info"
        showIcon
        message={t('setup.provenance.storageTitle')}
        description={t('setup.provenance.storageBody')}
      />
    </Space>
  );
}
