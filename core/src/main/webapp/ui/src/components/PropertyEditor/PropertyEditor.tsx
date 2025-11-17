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

import React, { useState } from 'react';
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

  const handleSubmit = async (values: Record<string, any>) => {
    console.error('!!! PROPERTYEDITOR: handleSubmit CALLED with values:', Object.keys(values));
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

      console.error('!!! PROPERTYEDITOR: Processed values:', processedValues);
      console.error('!!! PROPERTYEDITOR: About to call onSave()');
      await onSave(processedValues);
      console.error('!!! PROPERTYEDITOR: onSave() completed');
      setEditMode(false);
      form.resetFields();
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    console.error('!!! PROPERTYEDITOR: handleCancel CALLED');
    console.error('!!! PROPERTYEDITOR: Stack trace:', new Error().stack);
    setEditMode(false);
    form.resetFields();
  };

  const renderPropertyField = (propId: string, propDef: PropertyDefinition) => {
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

  const getInitialValues = () => {
    const initialValues: Record<string, any> = {};

    Object.entries(safePropDefs).forEach(([propId, propDef]: [string, PropertyDefinition]) => {
      const value = object.properties[propId];
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

  const formatDisplayValue = (value: any, propDef: PropertyDefinition): string => {
    if (value === undefined || value === null) return '-';

    if (propDef.propertyType === 'datetime' && value) {
      return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
    }

    if (Array.isArray(value)) {
      return value.join(', ');
    }

    if (propDef.propertyType === 'boolean') {
      return value ? 'はい' : 'いいえ';
    }

    return String(value);
  };

  // Display Mode: Table view with pagination
  const renderDisplayMode = () => {
    const dataSource = Object.entries(safePropDefs).map(([propId, propDef]) => ({
      key: propId,
      propId,
      displayName: propDef.displayName,
      value: object.properties[propId]?.value,  // FIX: Extract value from property object
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
  const renderEditMode = () => {
    // CRITICAL DIAGNOSTIC (2025-11-17): Log propertyDefinitions to verify updatable field mapping
    console.error('!!! === PROPERTYEDITOR EDIT MODE DIAGNOSTIC ===');
    console.error('!!! Total properties:', Object.keys(safePropDefs).length);
    console.error('!!! Property sample (first 3):', Object.entries(safePropDefs).slice(0, 3).map(([id, def]) => ({
      id,
      updatable: def.updatable,
      updatability: (def as any).updatability,
      propertyType: def.propertyType
    })));

    const editableProps = Object.entries(safePropDefs).filter(
      ([_, propDef]) => propDef.updatable
    );

    console.error('!!! Editable props count:', editableProps.length);
    console.error('!!! Editable props:', editableProps.map(([id, def]) => ({
      id,
      updatable: def.updatable,
      propertyType: def.propertyType
    })));

    // DIAGNOSTIC: Log form field values and errors when Edit Mode renders
    setTimeout(() => {
      const fieldValues = form.getFieldsValue();
      const fieldErrors = form.getFieldsError();
      console.error('!!! PROPERTYEDITOR FORM STATE DIAGNOSTIC:');
      console.error('!!! - Field values:', fieldValues);
      console.error('!!! - Field errors:', fieldErrors);
      console.error('!!! - Is validating:', form.isFieldsValidating());
    }, 100);

    const handleFinishFailed = (errorInfo: any) => {
      console.error('!!! PROPERTYEDITOR: Form validation FAILED');
      console.error('!!! - Error info:', errorInfo);
      console.error('!!! - Failed fields:', errorInfo.errorFields);
      console.error('!!! - Values:', errorInfo.values);
    };

    return (
      <Form
        form={form}
        layout="vertical"
        initialValues={getInitialValues()}
        onFinish={handleSubmit}
        onFinishFailed={handleFinishFailed}
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
