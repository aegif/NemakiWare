/**
 * TypeMigrationModal Component
 *
 * A modal dialog for migrating CMIS objects to a different type.
 * This is a NemakiWare-specific extension - CMIS standard does not support changing object types.
 *
 * Features:
 * - Displays current object type
 * - Shows compatible types (same base type)
 * - Provides input forms for additional required properties
 * - Supports various property types (string, integer, boolean, datetime, decimal)
 * - Warns about additional required properties
 * - Performs type migration via REST API
 *
 * @since 2025-12-11
 * @updated 2025-12-11 - Added property input forms
 */

import React, { useState, useEffect } from 'react';
import {
  Modal,
  Select,
  Alert,
  Spin,
  Typography,
  Space,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Switch,
  DatePicker,
  Divider
} from 'antd';
import { SwapOutlined, WarningOutlined, FormOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { CompatibleType, MigrationPropertyDefinition } from '../../types/typeMigration';
import dayjs from 'dayjs';

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
  const [form] = Form.useForm();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  const coerceSingleValue = (value: unknown, propDef: MigrationPropertyDefinition) => {
    if (value === undefined || value === null) return undefined;
    if (typeof value === 'string' && value.trim() === '') return undefined;

    switch (propDef.propertyType) {
      case 'boolean': {
        if (typeof value === 'boolean') return value;
        if (typeof value === 'string') {
          const normalized = value.trim().toLowerCase();
          if (normalized === 'true') return true;
          if (normalized === 'false') return false;
        }
        return Boolean(value);
      }
      case 'integer': {
        const parsed = typeof value === 'number' ? value : Number(value);
        return Number.isNaN(parsed) ? undefined : Math.trunc(parsed);
      }
      case 'decimal': {
        const parsed = typeof value === 'number' ? value : Number(value);
        return Number.isNaN(parsed) ? undefined : parsed;
      }
      case 'datetime': {
        if (typeof value === 'string' || typeof value === 'number' || value instanceof Date || dayjs.isDayjs(value)) {
          const parsedDate = dayjs(value);
          return parsedDate.isValid() ? parsedDate.toISOString() : undefined;
        }
        return undefined;
      }
      default:
        return value;
    }
  };

  const coerceMultiValue = (value: unknown, propDef: MigrationPropertyDefinition) => {
    if (value === undefined || value === null) return [];

    const values = Array.isArray(value)
      ? value
      : String(value)
          .split(',')
          .map((entry) => entry.trim())
          .filter((entry) => entry.length > 0);

    return values
      .map((entry) => coerceSingleValue(entry, propDef))
      .filter((entry): entry is Exclude<ReturnType<typeof coerceSingleValue>, undefined> => entry !== undefined);
  };

  const getInitialFormValue = (propDef: MigrationPropertyDefinition) => {
    if (propDef.defaultValue === undefined || propDef.defaultValue === null) {
      return undefined;
    }

    if (propDef.cardinality === 'multi') {
      if (Array.isArray(propDef.defaultValue)) {
        return propDef.defaultValue.join(', ');
      }
      return String(propDef.defaultValue);
    }

    if (propDef.propertyType === 'datetime') {
      if (
        typeof propDef.defaultValue === 'string' ||
        typeof propDef.defaultValue === 'number' ||
        propDef.defaultValue instanceof Date ||
        dayjs.isDayjs(propDef.defaultValue)
      ) {
        const parsedDate = dayjs(propDef.defaultValue);
        return parsedDate.isValid() ? parsedDate : undefined;
      }
      return undefined;
    }

    if (propDef.propertyType === 'boolean' || propDef.propertyType === 'integer' || propDef.propertyType === 'decimal') {
      return coerceSingleValue(propDef.defaultValue, propDef);
    }

    return propDef.defaultValue;
  };

  useEffect(() => {
    if (visible && objectId) {
      loadCompatibleTypes();
      form.resetFields();
      setSelectedType(null);
    }
  }, [visible, objectId, repositoryId]);

  // Reset form when type selection changes
  useEffect(() => {
    form.resetFields();
    if (!selectedType) return;

    const selected = compatibleTypes[selectedType];
    if (!selected) return;

    const defaultValues: Record<string, unknown> = {};
    Object.entries(selected.additionalRequiredProperties || {}).forEach(([propId, propDef]) => {
      const initialValue = getInitialFormValue(propDef);
      if (initialValue !== undefined) {
        defaultValues[propId] = initialValue;
      }
    });

    if (Object.keys(defaultValues).length > 0) {
      form.setFieldsValue(defaultValues);
    }
  }, [selectedType, compatibleTypes, form]);

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

    try {
      // Validate form if there are additional properties
      const formValues = await form.validateFields();

      setLoading(true);
      setError(null);

      // Transform form values to proper types
      const additionalProperties: Record<string, unknown> = {};
      const selectedTypeInfo = compatibleTypes[selectedType];

      if (selectedTypeInfo && selectedTypeInfo.additionalRequiredProperties) {
        for (const [propId, propDef] of Object.entries(selectedTypeInfo.additionalRequiredProperties)) {
          const value = formValues[propId];

          if (propDef.cardinality === 'multi') {
            const normalizedValues = coerceMultiValue(value, propDef);
            if (normalizedValues.length > 0) {
              additionalProperties[propId] = normalizedValues;
            }
            continue;
          }

          const normalizedValue = coerceSingleValue(value, propDef);
          if (normalizedValue !== undefined) {
            additionalProperties[propId] = normalizedValue;
          }
        }
      }

      await cmisService.migrateObjectType(
        repositoryId,
        objectId,
        selectedType,
        Object.keys(additionalProperties).length > 0 ? additionalProperties : undefined
      );
      onSuccess(selectedType);
      onClose();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) {
        // Form validation error - don't show as error message
        return;
      }
      setError(`タイプの変更に失敗しました: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    setSelectedType(null);
    setError(null);
    form.resetFields();
    onClose();
  };

  const selectedTypeInfo = selectedType ? compatibleTypes[selectedType] : null;
  const additionalProps = selectedTypeInfo?.additionalRequiredProperties || {};
  const hasAdditionalRequiredProps = Object.keys(additionalProps).length > 0;

  const typeOptions = Object.values(compatibleTypes).map((type) => ({
    value: type.id,
    label: type.displayName || type.id,
    description: type.description,
  }));

  /**
   * Render input component based on property type
   */
  const renderPropertyInput = (propDef: MigrationPropertyDefinition) => {
    const { propertyType, cardinality } = propDef;

    // Multi-value properties - use text area for now
    if (cardinality === 'multi') {
      return (
        <Input.TextArea
          placeholder="複数の値はカンマで区切って入力"
          rows={2}
        />
      );
    }

    // Single-value properties
    switch (propertyType) {
      case 'boolean':
        return <Switch checkedChildren="True" unCheckedChildren="False" />;

      case 'integer':
        return (
          <InputNumber
            style={{ width: '100%' }}
            placeholder="整数値を入力"
            precision={0}
          />
        );

      case 'decimal':
        return (
          <InputNumber
            style={{ width: '100%' }}
            placeholder="小数値を入力"
          />
        );

      case 'datetime':
        return (
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder="日時を選択"
          />
        );

      case 'html':
        return (
          <Input.TextArea
            placeholder="HTMLを入力"
            rows={3}
          />
        );

      case 'uri':
        return (
          <Input
            placeholder="URIを入力 (例: https://example.com)"
            type="url"
          />
        );

      case 'id':
      case 'string':
      default:
        return <Input placeholder="値を入力" />;
    }
  };

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
      width={650}
      destroyOnClose
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
          {selectedTypeInfo && selectedTypeInfo.description && (
            <div>
              <Text type="secondary">
                {selectedTypeInfo.description}
              </Text>
            </div>
          )}

          {/* Additional required properties form */}
          {hasAdditionalRequiredProps && selectedTypeInfo && (
            <>
              <Divider style={{ margin: '12px 0' }} />
              <Alert
                message={
                  <Space>
                    <FormOutlined />
                    <span>追加の必須プロパティ</span>
                  </Space>
                }
                description="新しいタイプには以下の必須プロパティがあります。値を入力してください。"
                type="warning"
                showIcon
                icon={<WarningOutlined />}
              />

              <Form
                form={form}
                layout="vertical"
                size="small"
                style={{ marginTop: 8 }}
              >
                {Object.entries(additionalProps).map(([propId, propDef]) => (
                  <Form.Item
                    key={propId}
                    name={propId}
                    label={
                      <Space>
                        <Text strong>{propDef.displayName}</Text>
                        <Text type="secondary" style={{ fontSize: '12px' }}>
                          ({propDef.propertyType})
                        </Text>
                      </Space>
                    }
                    rules={[
                      {
                        required: propDef.required,
                        message: `${propDef.displayName}は必須です`,
                      },
                    ]}
                    tooltip={propDef.description}
                    valuePropName={propDef.propertyType === 'boolean' ? 'checked' : 'value'}
                  >
                    {renderPropertyInput(propDef)}
                  </Form.Item>
                ))}
              </Form>
            </>
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
