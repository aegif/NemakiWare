/**
 * SecondaryTypeSelector Component for NemakiWare React UI
 *
 * Provides a UI for selecting and managing secondary types (aspects) for CMIS objects.
 * Secondary types allow attaching additional property definitions to objects.
 *
 * Features:
 * - Display currently assigned secondary types
 * - Multi-select dropdown for adding secondary types
 * - Remove button with confirmation for each assigned type
 * - Warning about data loss when removing types
 * - Integration with CMIS Browser Binding addSecondaryTypeIds/removeSecondaryTypeIds
 *
 * Implementation Date: 2025-12-11
 */

import React, { useState, useEffect } from 'react';
import { Select, Tag, Space, Modal, message, Spin, Typography, Tooltip, Button } from 'antd';
import { ExclamationCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { TypeDefinition, CMISObject } from '../../types/cmis';
import { useAuth } from '../../contexts/AuthContext';

const { Text } = Typography;

interface SecondaryTypeSelectorProps {
  repositoryId: string;
  object: CMISObject;
  onUpdate?: (updatedObject: CMISObject) => void;
  readOnly?: boolean;
}

export const SecondaryTypeSelector: React.FC<SecondaryTypeSelectorProps> = ({
  repositoryId,
  object,
  onUpdate,
  readOnly = false
}) => {
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  const [availableTypes, setAvailableTypes] = useState<TypeDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [selectedTypeId, setSelectedTypeId] = useState<string | undefined>(undefined);

  // Get current secondary type IDs from object properties
  const currentSecondaryTypeIds: string[] =
    object.properties?.['cmis:secondaryObjectTypeIds'] || [];

  useEffect(() => {
    loadAvailableTypes();
  }, [repositoryId]);

  const loadAvailableTypes = async () => {
    setLoading(true);
    try {
      const types = await cmisService.getSecondaryTypes(repositoryId);
      setAvailableTypes(types);
    } catch (error) {
      console.error('Failed to load secondary types:', error);
      message.error('セカンダリタイプの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleAddSecondaryType = async () => {
    if (!selectedTypeId || currentSecondaryTypeIds.includes(selectedTypeId)) return;

    setUpdating(true);
    try {
      // CRITICAL FIX: Pass changeToken from object properties to avoid updateConflict error
      const changeToken = object.properties?.['cmis:changeToken'] as string;
      const updated = await cmisService.updateSecondaryTypes(
        repositoryId,
        object.id,
        [selectedTypeId],
        [],
        changeToken
      );
      message.success(`セカンダリタイプ「${selectedTypeId}」を追加しました`);
      setSelectedTypeId(undefined); // Clear selection after successful add
      onUpdate?.(updated);
    } catch (error) {
      console.error('Failed to add secondary type:', error);
      message.error('セカンダリタイプの追加に失敗しました');
    } finally {
      setUpdating(false);
    }
  };

  const handleRemoveSecondaryType = (typeId: string) => {
    // Find the type definition to show property information in warning
    const typeDef = availableTypes.find(t => t.id === typeId);
    const propertyCount = typeDef?.propertyDefinitions
      ? Object.keys(typeDef.propertyDefinitions).length
      : 0;

    Modal.confirm({
      title: 'セカンダリタイプを削除しますか？',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>
            <strong>{typeId}</strong> を削除すると、このタイプに関連するプロパティ値が失われる可能性があります。
          </p>
          {propertyCount > 0 && (
            <p style={{ color: '#ff4d4f' }}>
              このタイプには {propertyCount} 個のプロパティ定義が含まれています。
            </p>
          )}
          <p>この操作は元に戻せません。続行しますか？</p>
        </div>
      ),
      okText: '削除',
      okButtonProps: { danger: true },
      cancelText: 'キャンセル',
      onOk: async () => {
        setUpdating(true);
        try {
          // CRITICAL FIX: Pass changeToken from object properties to avoid updateConflict error
          const changeToken = object.properties?.['cmis:changeToken'] as string;
          const updated = await cmisService.updateSecondaryTypes(
            repositoryId,
            object.id,
            [],
            [typeId],
            changeToken
          );
          message.success(`セカンダリタイプ「${typeId}」を削除しました`);
          onUpdate?.(updated);
        } catch (error) {
          console.error('Failed to remove secondary type:', error);
          message.error('セカンダリタイプの削除に失敗しました');
        } finally {
          setUpdating(false);
        }
      }
    });
  };

  // Filter out already assigned types from available options
  const availableOptions = availableTypes.filter(
    type => !currentSecondaryTypeIds.includes(type.id)
  );

  if (loading) {
    return (
      <div style={{ padding: '16px', textAlign: 'center' }}>
        <Spin size="small" />
        <Text type="secondary" style={{ marginLeft: 8 }}>セカンダリタイプを読み込み中...</Text>
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 16, padding: '12px', backgroundColor: '#fafafa', borderRadius: '4px' }}>
      <div style={{ marginBottom: 8 }}>
        <Text strong>セカンダリタイプ</Text>
        <Tooltip title="セカンダリタイプを追加すると、オブジェクトに追加のプロパティを設定できます">
          <Text type="secondary" style={{ marginLeft: 8, fontSize: '12px' }}>
            (追加プロパティ)
          </Text>
        </Tooltip>
      </div>

      {/* Currently assigned secondary types */}
      <div style={{ marginBottom: 12 }}>
        {currentSecondaryTypeIds.length === 0 ? (
          <Text type="secondary">セカンダリタイプが割り当てられていません</Text>
        ) : (
          <Space wrap>
            {currentSecondaryTypeIds.map(typeId => {
              const typeDef = availableTypes.find(t => t.id === typeId);
              return (
                <Tag
                  key={typeId}
                  color="blue"
                  closable={!readOnly}
                  onClose={(e) => {
                    e.preventDefault();
                    handleRemoveSecondaryType(typeId);
                  }}
                >
                  {typeDef?.displayName || typeId}
                </Tag>
              );
            })}
          </Space>
        )}
      </div>

      {/* Add secondary type selector with explicit add button */}
      {!readOnly && availableOptions.length > 0 && (
        <Space.Compact style={{ width: '100%', maxWidth: 500 }}>
          <Select
            style={{ flex: 1 }}
            placeholder="追加するセカンダリタイプを選択..."
            loading={updating}
            disabled={updating}
            showSearch
            optionFilterProp="label"
            value={selectedTypeId}
            onChange={setSelectedTypeId}
          >
            {availableOptions.map(type => (
              <Select.Option key={type.id} value={type.id} label={type.displayName || type.id}>
                <div>
                  <span>{type.displayName || type.id}</span>
                  {type.description && (
                    <span style={{ color: '#888', marginLeft: 8, fontSize: '12px' }}>
                      - {type.description}
                    </span>
                  )}
                </div>
              </Select.Option>
            ))}
          </Select>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleAddSecondaryType}
            loading={updating}
            disabled={!selectedTypeId || updating}
          >
            追加
          </Button>
        </Space.Compact>
      )}

      {!readOnly && availableOptions.length === 0 && availableTypes.length > 0 && (
        <Text type="secondary">すべてのセカンダリタイプが割り当て済みです</Text>
      )}

      {availableTypes.length === 0 && (
        <Text type="secondary">利用可能なセカンダリタイプがありません</Text>
      )}
    </div>
  );
};
