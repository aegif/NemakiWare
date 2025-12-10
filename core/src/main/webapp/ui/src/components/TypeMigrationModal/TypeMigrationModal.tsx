/**
 * TypeMigrationModal Component
 *
 * A modal dialog for migrating CMIS objects to a different type.
 * This is a NemakiWare-specific extension - CMIS standard does not support changing object types.
 *
 * Features:
 * - Displays current object type
 * - Shows compatible types (same base type)
 * - Warns about additional required properties
 * - Performs type migration via REST API
 *
 * @since 2025-12-11
 */

import React, { useState, useEffect } from 'react';
import { Modal, Select, Alert, Spin, Typography, Space, Descriptions } from 'antd';
import { SwapOutlined, WarningOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';

const { Text, Paragraph } = Typography;

interface TypeMigrationModalProps {
  visible: boolean;
  repositoryId: string;
  objectId: string;
  objectName: string;
  currentType: string;
  onClose: () => void;
  onSuccess: (newTypeId: string) => void;
}

interface CompatibleType {
  id: string;
  displayName: string;
  description: string;
  baseTypeId: string;
  additionalRequiredProperties: Record<string, string>;
}

export const TypeMigrationModal: React.FC<TypeMigrationModalProps> = ({
  visible,
  repositoryId,
  objectId,
  objectName,
  currentType,
  onClose,
  onSuccess,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadingTypes, setLoadingTypes] = useState(false);
  const [selectedType, setSelectedType] = useState<string | null>(null);
  const [compatibleTypes, setCompatibleTypes] = useState<Record<string, CompatibleType>>({});
  const [currentTypeDisplayName, setCurrentTypeDisplayName] = useState<string>('');
  const [baseType, setBaseType] = useState<string>('');
  const [error, setError] = useState<string | null>(null);

  const cmisService = new CMISService();

  useEffect(() => {
    if (visible && objectId) {
      loadCompatibleTypes();
    }
  }, [visible, objectId, repositoryId]);

  const loadCompatibleTypes = async () => {
    setLoadingTypes(true);
    setError(null);
    try {
      const result = await cmisService.getCompatibleTypesForMigration(repositoryId, objectId);
      setCompatibleTypes(result.compatibleTypes);
      setCurrentTypeDisplayName(result.currentTypeDisplayName);
      setBaseType(result.baseType);
    } catch (e) {
      setError(`互換タイプの取得に失敗しました: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoadingTypes(false);
    }
  };

  const handleMigrate = async () => {
    if (!selectedType) return;

    setLoading(true);
    setError(null);
    try {
      await cmisService.migrateObjectType(repositoryId, objectId, selectedType);
      onSuccess(selectedType);
      onClose();
    } catch (e) {
      setError(`タイプの変更に失敗しました: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    setSelectedType(null);
    setError(null);
    onClose();
  };

  const selectedTypeInfo = selectedType ? compatibleTypes[selectedType] : null;
  const hasAdditionalRequiredProps = selectedTypeInfo &&
    Object.keys(selectedTypeInfo.additionalRequiredProperties).length > 0;

  const typeOptions = Object.values(compatibleTypes).map((type) => ({
    value: type.id,
    label: type.displayName || type.id,
    description: type.description,
  }));

  return (
    <Modal
      title={
        <Space>
          <SwapOutlined />
          <span>オブジェクトタイプの変更</span>
        </Space>
      }
      open={visible}
      onOk={handleMigrate}
      onCancel={handleCancel}
      okText="タイプを変更"
      cancelText="キャンセル"
      okButtonProps={{
        disabled: !selectedType || loading,
        loading: loading,
        danger: hasAdditionalRequiredProps,
      }}
      width={600}
    >
      {loadingTypes ? (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin size="large" />
          <Paragraph style={{ marginTop: 16 }}>互換タイプを読み込み中...</Paragraph>
        </div>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          {/* Object Information */}
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="オブジェクト名">{objectName}</Descriptions.Item>
            <Descriptions.Item label="現在のタイプ">
              {currentTypeDisplayName || currentType}
            </Descriptions.Item>
            <Descriptions.Item label="ベースタイプ">{baseType}</Descriptions.Item>
          </Descriptions>

          {/* Warning about CMIS non-standard operation */}
          <Alert
            message="注意: CMIS標準外の操作"
            description="オブジェクトタイプの変更はCMIS 1.1標準では定義されていません。この機能はNemakiWare固有の拡張です。"
            type="info"
            showIcon
          />

          {/* Type selector */}
          <div>
            <Text strong>新しいタイプを選択:</Text>
            <Select
              style={{ width: '100%', marginTop: 8 }}
              placeholder="タイプを選択してください"
              value={selectedType}
              onChange={setSelectedType}
              options={typeOptions}
              showSearch
              optionFilterProp="label"
              disabled={Object.keys(compatibleTypes).length === 0}
            />
          </div>

          {/* No compatible types message */}
          {Object.keys(compatibleTypes).length === 0 && !loadingTypes && (
            <Alert
              message="互換タイプなし"
              description="このオブジェクトに変更可能な互換タイプがありません。同じベースタイプ（ドキュメント/フォルダ）を持つカスタムタイプを定義してください。"
              type="warning"
              showIcon
            />
          )}

          {/* Selected type description */}
          {selectedTypeInfo && (
            <div>
              <Text type="secondary">
                {selectedTypeInfo.description || '説明なし'}
              </Text>
            </div>
          )}

          {/* Additional required properties warning */}
          {hasAdditionalRequiredProps && (
            <Alert
              message="追加の必須プロパティ"
              description={
                <div>
                  <Paragraph>
                    新しいタイプには以下の必須プロパティがありますが、現在のオブジェクトには存在しません:
                  </Paragraph>
                  <ul>
                    {Object.entries(selectedTypeInfo.additionalRequiredProperties).map(
                      ([propId, propName]) => (
                        <li key={propId}>
                          <strong>{propName}</strong> ({propId})
                        </li>
                      )
                    )}
                  </ul>
                  <Paragraph type="warning">
                    <WarningOutlined /> タイプ変更後、これらのプロパティに値を設定する必要があります。
                  </Paragraph>
                </div>
              }
              type="warning"
              showIcon
            />
          )}

          {/* Error message */}
          {error && (
            <Alert message="エラー" description={error} type="error" showIcon />
          )}
        </Space>
      )}
    </Modal>
  );
};

export default TypeMigrationModal;
