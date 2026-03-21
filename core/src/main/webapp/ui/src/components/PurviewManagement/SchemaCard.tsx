import React from 'react';
import { Card, Descriptions, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { PurviewSchemaDiff } from '../../services/purviewAdmin';
import { renderStatusTag, renderTagList } from './purviewRenderers';

const { Text } = Typography;

interface SchemaCardProps {
  schemaDiff: PurviewSchemaDiff | null;
}

export const SchemaCard: React.FC<SchemaCardProps> = ({ schemaDiff }) => {
  const { t } = useTranslation();

  return (
    <Card
      title={t('purviewManagement.sections.schema')}
      extra={schemaDiff?.applyRequired ? renderStatusTag('PENDING') : renderStatusTag('COMPLETED')}
    >
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label={t('purviewManagement.schema.current')}>
          <Space wrap>
            <Text>{schemaDiff?.currentSchemaVersion || '-'}</Text>
            <Text code>{schemaDiff?.currentSchemaHash || '-'}</Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label={t('purviewManagement.schema.desired')}>
          <Space wrap>
            <Text>{schemaDiff?.desiredSchemaVersion || '-'}</Text>
            <Text code>{schemaDiff?.desiredSchemaHash || '-'}</Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label={t('purviewManagement.schema.customTypes')}>
          {renderTagList(schemaDiff?.customTypeNames || [])}
        </Descriptions.Item>
        <Descriptions.Item label={t('purviewManagement.schema.relationshipTypes')}>
          {renderTagList(schemaDiff?.relationshipTypeNames || [])}
        </Descriptions.Item>
        <Descriptions.Item label={t('purviewManagement.schema.businessMetadata')}>
          {renderTagList(schemaDiff?.businessMetadataNames || [])}
        </Descriptions.Item>
      </Descriptions>
    </Card>
  );
};
