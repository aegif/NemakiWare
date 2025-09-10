import React, { useState, useEffect } from 'react';
import { Form, Input, Select, DatePicker, InputNumber, Button, message } from 'antd';
import { ActionForm, ActionFormField } from '../../types/cmis';
import { ActionService } from '../../services/action';

interface ActionFormRendererProps {
  repositoryId: string;
  objectId: string;
  actionId: string;
  onComplete: () => void;
}

export const ActionFormRenderer: React.FC<ActionFormRendererProps> = ({
  repositoryId,
  objectId,
  actionId,
  onComplete
}) => {
  const [form] = Form.useForm();
  const [actionForm, setActionForm] = useState<ActionForm | null>(null);
  const [loading, setLoading] = useState(false);
  const [executing, setExecuting] = useState(false);

  const actionService = new ActionService();

  useEffect(() => {
    loadActionForm();
  }, [actionId]);

  const loadActionForm = async () => {
    setLoading(true);
    try {
      const formDef = await actionService.getActionForm(repositoryId, actionId, objectId);
      setActionForm(formDef);
      
      const defaultValues: Record<string, any> = {};
      formDef.fields.forEach(field => {
        if (field.defaultValue !== undefined) {
          defaultValues[field.name] = field.defaultValue;
        }
      });
      form.setFieldsValue(defaultValues);
    } catch (error) {
      console.error('Failed to load action form:', error);
      message.error('フォームの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (values: Record<string, any>) => {
    setExecuting(true);
    try {
      const result = await actionService.executeAction(repositoryId, actionId, objectId, values);
      if (result.success) {
        message.success(result.message || 'アクションが正常に実行されました');
        onComplete();
      } else {
        message.error(result.message || 'アクションの実行に失敗しました');
      }
    } catch (error) {
      console.error('Action execution failed:', error);
      message.error('アクションの実行中にエラーが発生しました');
    } finally {
      setExecuting(false);
    }
  };

  const renderFormField = (field: ActionFormField) => {
    switch (field.type) {
      case 'select':
        return (
          <Select placeholder={`${field.label}を選択`}>
            {field.options?.map(option => (
              <Select.Option key={option.value} value={option.value}>
                {option.label}
              </Select.Option>
            ))}
          </Select>
        );
      case 'textarea':
        return <Input.TextArea rows={4} placeholder={field.label} />;
      case 'number':
        return <InputNumber style={{ width: '100%' }} placeholder={field.label} />;
      case 'date':
        return <DatePicker style={{ width: '100%' }} placeholder={field.label} />;
      default:
        return <Input placeholder={field.label} />;
    }
  };

  if (loading || !actionForm) {
    return <div>読み込み中...</div>;
  }

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
    >
      {actionForm.fields.map(field => (
        <Form.Item
          key={field.name}
          name={field.name}
          label={field.label}
          rules={[{ required: field.required, message: `${field.label}は必須です` }]}
        >
          {renderFormField(field)}
        </Form.Item>
      ))}
      
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={executing}>
          実行
        </Button>
      </Form.Item>
    </Form>
  );
};
