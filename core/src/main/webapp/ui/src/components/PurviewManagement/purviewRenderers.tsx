import React from 'react';
import { Empty, Space, Tag, Typography } from 'antd';
import { statusColorMap } from './purviewUtils';

const { Text } = Typography;

export const renderStatusTag = (status?: string): React.ReactNode => {
  if (!status) {
    return <Tag>-</Tag>;
  }
  return <Tag color={statusColorMap[status] || 'default'}>{status}</Tag>;
};

export const renderTagList = (values: string[]): React.ReactNode => {
  if (!values || values.length === 0) {
    return <Text type="secondary">-</Text>;
  }

  return (
    <Space size={[4, 8]} wrap>
      {values.map((value) => (
        <Tag key={value}>{value}</Tag>
      ))}
    </Space>
  );
};

export const tableLocaleFactory = (t: (key: string) => string) => ({
  emptyText: <Empty description={t('common.noData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />,
});
