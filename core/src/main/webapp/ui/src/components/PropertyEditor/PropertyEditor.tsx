/**
 * PropertyEditor Component - Display/Edit Mode Separation
 *
 * Two-mode property management:
 * 1. Display Mode (default): Table view with pagination, all properties visible
 * 2. Edit Mode: Form view with only updatable properties
 *
 * Design Philosophy:
 * - Separation of concerns: Read and write operations are distinct user intents
 * - Progressive disclosure: Edit mode revealed only when user initiates editing
 * - Clear visual hierarchy: Read-only properties grayed out in display mode, hidden in edit mode
 * - Scalability: Table with pagination handles dozens of custom properties efficiently
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  Form,
  Input,
  InputNumber,
  DatePicker,
  Switch,
  Select,
  Button,
  Space,
  Tooltip,
  Table,
  Typography
} from 'antd';
import { InfoCircleOutlined, EditOutlined, SaveOutlined, CloseOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { getSafeBooleanValue } from '../../utils/cmisPropertyUtils';
import { PropertyDefinition, CMISObject } from '../../types/cmis';

const { Text } = Typography;

interface PropertyEditorProps {
  object: CMISObject;
  propertyDefinitions: Record<string, PropertyDefinition>;
  onSave: (properties: Record<string, any>) => Promise<void>;
  readOnly?: boolean;
}

export const PropertyEditor: React.FC<PropertyEditorProps> = ({
  object,
  propertyDefinitions = {},
  onSave,
  readOnly = false
}) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [editMode, setEditMode] = useState(false);

  const safePropDefs = propertyDefinitions || {};

  // CRITICAL FIX (2025-12-18): Track previous object ID to detect object changes
  const prevObjectIdRef = useRef<string | null>(null);

  // CRITICAL FIX (2025-12-18): getInitialValues must be defined before useEffect
  // to avoid stale closure issues
  const getInitialValuesInternal = () => {
    const initialValues: Record<string, any> = {};

    Object.entries(safePropDefs).forEach(([propId, propDef]: [string, PropertyDefinition]) => {
      let value = object.properties[propId];

      // Extract actual value from Browser Binding format
      if (typeof value === 'object' && value !== null && !Array.isArray(value) && 'value' in value) {
        value = value.value;
      }

      if (value !== undefined && value !== null) {
        if (propDef.propertyType === 'datetime' && value) {
          initialValues[propId] = dayjs(value);
        } else {
          initialValues[propId] = value;
        }
      } else if (propDef.defaultValue && propDef.defaultValue.length > 0) {
        if (propDef.cardinality === 'multi') {
          initialValues[propId] = propDef.defaultValue;
        } else {
          initialValues[propId] = propDef.defaultValue[0];
        }
      }
    });

    return initialValues;
  };

  // CRITICAL FIX (2025-12-18): Sync form values when object prop changes
  // This fixes the issue where Description disappears when re-editing after save
  // Root cause: Form's initialValues are only read once at mount, not on prop changes
  // Solution: Use useEffect to explicitly update form values when object changes
  useEffect(() => {
    const currentObjectId = object?.id;

    // Only sync if the object has changed (by ID) or if we're in edit mode
    if (editMode && form) {
      // Use setTimeout to ensure React has finished updating the DOM
      // before we set the form values
      setTimeout(() => {
        const freshValues = getInitialValuesInternal();
        form.setFieldsValue(freshValues);
      }, 0);
    }

    // Update ref for next comparison
    prevObjectIdRef.current = currentObjectId;
  }, [object, editMode, form, safePropDefs]);

  const handleSubmit = async (values: Record<string, any>) => {
    setLoading(true);
    try {
      const processedValues: Record<string, any> = {};

      Object.entries(values).forEach(([key, value]) => {
        const propDef = safePropDefs[key];
        if (propDef) {
          if (propDef.propertyType === 'datetime' && value) {
            processedValues[key] = dayjs(value).toISOString();
          } else if (propDef.cardinality === 'multi' && !Array.isArray(value)) {
            processedValues[key] = value ? [value] : [];
          } else {
            processedValues[key] = value;
          }
        }
      });

      await onSave(processedValues);
      setEditMode(false);
      // CRITICAL FIX (2025-12-18): Removed form.resetFields() here
      // The useEffect hook now handles syncing form values with the object prop
      // This fixes the issue where Description disappeared when re-editing after save
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    setEditMode(false);
    // CRITICAL FIX (2025-12-18): Removed form.resetFields() here
    // The useEffect hook handles syncing form values with the object prop
    // When editMode becomes true again, the form will get fresh values
  };

  const renderPropertyField = (_propId: string, propDef: PropertyDefinition) => {
    switch (propDef.propertyType) {
      case 'string':
        if (propDef.choices && propDef.choices.length > 0) {
          return (
            <Select
              mode={propDef.cardinality === 'multi' ? 'multiple' : undefined}
              options={propDef.choices.map(choice => ({
                label: choice.displayName,
                value: choice.value[0]
              }))}
              placeholder={`${propDef.displayName}を選択`}
            />
          );
        }
        return propDef.cardinality === 'multi' ?
          <Select
            mode="tags"
            placeholder={`${propDef.displayName}を入力（複数可）`}
          /> :
          <Input
            maxLength={propDef.maxLength}
            placeholder={propDef.displayName}
          />;

      case 'integer':
        return (
          <InputNumber
            min={propDef.minValue}
            max={propDef.maxValue}
            style={{ width: '100%' }}
            placeholder={propDef.displayName}
          />
        );

      case 'decimal':
        return (
          <InputNumber
            min={propDef.minValue}
            max={propDef.maxValue}
            step={0.01}
            style={{ width: '100%' }}
            placeholder={propDef.displayName}
          />
        );

      case 'boolean':
        return <Switch
          checkedChildren="はい"
          unCheckedChildren="いいえ"
        />;

      case 'datetime':
        return (
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder={`${propDef.displayName}を選択`}
          />
        );

      default:
        return <Input placeholder={propDef.displayName} />;
    }
  };

  // CRITICAL FIX (2025-12-18): Use getInitialValuesInternal defined above
  // This ensures form values are correctly synced with the object prop
  const getInitialValues = getInitialValuesInternal;

  const formatDisplayValue = (value: any, propDef: PropertyDefinition): string => {
    if (value === undefined || value === null) return '-';

    // CRITICAL FIX (2025-11-18): Handle both formats:
    // 1. Direct value (from AtomPub): value = "text"
    // 2. Browser Binding format: value = {value: "text", ...}
    let actualValue = value;
    if (typeof value === 'object' && value !== null && !Array.isArray(value) && 'value' in value) {
      actualValue = value.value;
    }

    if (actualValue === undefined || actualValue === null) return '-';

    if (propDef.propertyType === 'datetime' && actualValue) {
      return dayjs(actualValue).format('YYYY-MM-DD HH:mm:ss');
    }

    if (Array.isArray(actualValue)) {
      return actualValue.join(', ');
    }

    if (propDef.propertyType === 'boolean') {
      // CRITICAL FIX (2025-12-14): Convert string 'true'/'false' to actual boolean
      // JavaScript evaluates string 'false' as truthy, causing incorrect display
      const boolValue = getSafeBooleanValue(actualValue);
      return boolValue ? 'はい' : 'いいえ';
    }

    return String(actualValue);
  };

  // Display Mode: Table view with pagination
  const renderDisplayMode = () => {
    const dataSource = Object.entries(safePropDefs).map(([propId, propDef]) => ({
      key: propId,
      propId,
      displayName: propDef.displayName,
      value: object.properties[propId],  // FIXED: Properties are stored directly, not as {value: ...}
      propertyType: propDef.propertyType,
      updatable: propDef.updatable,
      required: propDef.required,
      description: propDef.description,
      propDef
    }));

    const columns = [
      {
        title: 'プロパティ名',
        dataIndex: 'displayName',
        key: 'displayName',
        width: '30%',
        render: (text: string, record: any) => (
          <Space>
            <Text style={{ color: record.updatable ? 'inherit' : '#888' }}>
              {text}
              {record.required && <span style={{ color: 'red', marginLeft: 4 }}>*</span>}
            </Text>
            {record.description && (
              <Tooltip title={record.description}>
                <InfoCircleOutlined style={{ color: '#1890ff' }} />
              </Tooltip>
            )}
            {!record.updatable && (
              <Tooltip title="このプロパティは読み取り専用です">
                <Text type="secondary" style={{ fontSize: 12 }}>(読み取り専用)</Text>
              </Tooltip>
            )}
            {record.propId === 'cmis:secondaryObjectTypeIds' && (
              <Tooltip title="上部の「セカンダリタイプ」セクションで編集できます">
                <Text type="secondary" style={{ fontSize: 12 }}>(専用UI)</Text>
              </Tooltip>
            )}
          </Space>
        ),
      },
      {
        title: '値',
        dataIndex: 'value',
        key: 'value',
        render: (value: any, record: any) => (
          <Text style={{ color: record.updatable ? 'inherit' : '#888' }}>
            {formatDisplayValue(value, record.propDef)}
          </Text>
        ),
      },
    ];

    return (
      <div>
        {!readOnly && (
          <div style={{ marginBottom: 16 }}>
            <Button
              type="primary"
              icon={<EditOutlined />}
              onClick={() => setEditMode(true)}
            >
              編集
            </Button>
          </div>
        )}
        <Table
          dataSource={dataSource}
          columns={columns}
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            showTotal: (total) => `全 ${total} 件`,
          }}
          size="small"
        />
      </div>
    );
  };

  // Edit Mode: Form view with only updatable properties
  // Exclude cmis:secondaryObjectTypeIds - it should only be edited via SecondaryTypeSelector UI
  const renderEditMode = () => {
    const editableProps = Object.entries(safePropDefs).filter(
      ([propId, propDef]) => propDef.updatable && propId !== 'cmis:secondaryObjectTypeIds'
    );

    return (
      <Form
        form={form}
        layout="vertical"
        initialValues={getInitialValues()}
        onFinish={handleSubmit}
      >
        {editableProps.map(([propId, propDef]) => (
          <Form.Item
            key={propId}
            name={propId}
            label={
              <Space>
                {propDef.displayName}
                {propDef.description && (
                  <Tooltip title={propDef.description}>
                    <InfoCircleOutlined style={{ color: '#1890ff' }} />
                  </Tooltip>
                )}
              </Space>
            }
            rules={[
              {
                required: propDef.required,
                message: `${propDef.displayName}は必須です`
              }
            ]}
          >
            {renderPropertyField(propId, propDef)}
          </Form.Item>
        ))}

        <Form.Item>
          <Space>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              icon={<SaveOutlined />}
            >
              保存
            </Button>
            <Button
              onClick={handleCancel}
              icon={<CloseOutlined />}
            >
              キャンセル
            </Button>
          </Space>
        </Form.Item>
      </Form>
    );
  };

  return editMode ? renderEditMode() : renderDisplayMode();
};
