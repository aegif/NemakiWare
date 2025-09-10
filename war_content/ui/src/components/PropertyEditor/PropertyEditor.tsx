import React from 'react';
import { Form, Input, InputNumber, DatePicker, Switch, Select, Button, Space, Tooltip } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { PropertyDefinition, CMISObject } from '../../types/cmis';

interface PropertyEditorProps {
  object: CMISObject;
  propertyDefinitions: Record<string, PropertyDefinition>;
  onSave: (properties: Record<string, any>) => Promise<void>;
  readOnly?: boolean;
}

export const PropertyEditor: React.FC<PropertyEditorProps> = ({
  object,
  propertyDefinitions,
  onSave,
  readOnly = false
}) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = React.useState(false);

  const handleSubmit = async (values: Record<string, any>) => {
    setLoading(true);
    try {
      const processedValues: Record<string, any> = {};
      
      Object.entries(values).forEach(([key, value]) => {
        const propDef = propertyDefinitions[key];
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
    } finally {
      setLoading(false);
    }
  };

  const renderPropertyField = (propId: string, propDef: PropertyDefinition) => {
    const value = object.properties[propId];
    
    if (readOnly) {
      let displayValue = value;
      if (propDef.propertyType === 'datetime' && value) {
        displayValue = dayjs(value).format('YYYY-MM-DD HH:mm:ss');
      } else if (Array.isArray(value)) {
        displayValue = value.join(', ');
      }
      return <Input value={displayValue} disabled />;
    }

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
        return <Switch checkedChildren="はい" unCheckedChildren="いいえ" />;
      
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
    
    Object.entries(propertyDefinitions).forEach(([propId, propDef]: [string, PropertyDefinition]) => {
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

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={getInitialValues()}
      onFinish={handleSubmit}
    >
      {Object.entries(propertyDefinitions)
        .filter(([_, propDef]) => propDef.updatable || readOnly)
        .map(([propId, propDef]) => (
        <Form.Item
          key={propId}
          name={propId}
          label={
            <Space>
              {propDef.displayName}
              {propDef.description && (
                <Tooltip title={propDef.description}>
                  <InfoCircleOutlined style={{ color: '#c72439' }} />
                </Tooltip>
              )}
              {propDef.required && <span style={{ color: 'red' }}>*</span>}
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
      
      {!readOnly && (
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={loading}>
              保存
            </Button>
            <Button onClick={() => form.resetFields()}>
              リセット
            </Button>
          </Space>
        </Form.Item>
      )}
    </Form>
  );
};
