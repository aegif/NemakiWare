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
import { useTranslation } from 'react-i18next';
import { CMISService } from '../../services/cmis';
import { TypeDefinition, CMISObject } from '../../types/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { getSafeArrayValue, getSafeStringValue } from '../../utils/cmisPropertyUtils';

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
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  const [availableTypes, setAvailableTypes] = useState<TypeDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [selectedTypeId, setSelectedTypeId] = useState<string | undefined>(undefined);

  // Get current secondary type IDs from object properties
  // CRITICAL FIX: Use getSafeArrayValue to handle CMIS Browser Binding format {value: [...]}
  const currentSecondaryTypeIds: string[] =
    getSafeArrayValue(object.properties?.['cmis:secondaryObjectTypeIds']);

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
      message.error(t('secondaryTypes.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const handleAddSecondaryType = async () => {
    if (!selectedTypeId || currentSecondaryTypeIds.includes(selectedTypeId)) return;

    setUpdating(true);
    try {
      // CRITICAL FIX: Use getSafeStringValue to handle CMIS Browser Binding format {value: "..."}
      const changeToken = getSafeStringValue(object.properties?.['cmis:changeToken']);
      const updated = await cmisService.updateSecondaryTypes(
        repositoryId,
        object.id,
        [selectedTypeId],
        [],
        changeToken
      );
      message.success(t('secondaryTypes.messages.addSuccess', { typeId: selectedTypeId }));
      setSelectedTypeId(undefined); // Clear selection after successful add
      onUpdate?.(updated);
    } catch (error) {
      console.error('Failed to add secondary type:', error);
      message.error(t('secondaryTypes.messages.addError'));
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
      title: t('secondaryTypes.confirmDelete.title'),
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>
            {t('secondaryTypes.confirmDelete.content', { typeId })}
          </p>
          {propertyCount > 0 && (
            <p style={{ color: '#ff4d4f' }}>
              {t('secondaryTypes.confirmDelete.propertyCount', { count: propertyCount })}
            </p>
          )}
          <p>{t('secondaryTypes.confirmDelete.irreversible')}</p>
        </div>
      ),
      okText: t('common.delete'),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: async () => {
        setUpdating(true);
        try {
          // CRITICAL FIX: Use getSafeStringValue to handle CMIS Browser Binding format {value: "..."}
          const changeToken = getSafeStringValue(object.properties?.['cmis:changeToken']);
          const updated = await cmisService.updateSecondaryTypes(
            repositoryId,
            object.id,
            [],
            [typeId],
            changeToken
          );
          message.success(t('secondaryTypes.messages.removeSuccess', { typeId }));
          onUpdate?.(updated);
        } catch (error) {
          console.error('Failed to remove secondary type:', error);
          message.error(t('secondaryTypes.messages.removeError'));
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
        <Text type="secondary" style={{ marginLeft: 8 }}>{t('secondaryTypes.loading')}</Text>
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 16, padding: '12px', backgroundColor: '#fafafa', borderRadius: '4px' }}>
      <div style={{ marginBottom: 8 }}>
        <Text strong>{t('secondaryTypes.title')}</Text>
        <Tooltip title={t('secondaryTypes.tooltip')}>
          <Text type="secondary" style={{ marginLeft: 8, fontSize: '12px' }}>
            ({t('secondaryTypes.additionalProperties')})
          </Text>
        </Tooltip>
      </div>

      {/* Currently assigned secondary types */}
      <div style={{ marginBottom: 12 }}>
        {currentSecondaryTypeIds.length === 0 ? (
          <Text type="secondary">{t('secondaryTypes.noAssigned')}</Text>
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
            placeholder={t('secondaryTypes.selectPlaceholder')}
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
            {t('common.add')}
          </Button>
        </Space.Compact>
      )}

      {!readOnly && availableOptions.length === 0 && availableTypes.length > 0 && (
        <Text type="secondary">{t('secondaryTypes.allAssigned')}</Text>
      )}

      {availableTypes.length === 0 && (
        <Text type="secondary">{t('secondaryTypes.noAvailable')}</Text>
      )}
    </div>
  );
};
