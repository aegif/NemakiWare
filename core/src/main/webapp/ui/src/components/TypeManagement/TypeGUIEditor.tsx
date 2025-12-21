import React, { useState, useEffect } from 'react';
import {
  Form,
  Input,
  Select,
  Switch,
  Button,
  Card,
  Space,
  Collapse,
  Divider,
  Alert,
  Tabs,
  Typography,
  Tooltip,
  Row,
  Col
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  QuestionCircleOutlined,
  CodeOutlined,
  FormOutlined
} from '@ant-design/icons';
import { TypeDefinition } from '../../types/cmis';

const { TextArea } = Input;
const { Panel } = Collapse;
const { Text } = Typography;

// CMIS Base Types
const BASE_TYPES = [
  { value: 'cmis:document', label: 'ドキュメント (cmis:document)' },
  { value: 'cmis:folder', label: 'フォルダ (cmis:folder)' },
  { value: 'cmis:relationship', label: 'リレーションシップ (cmis:relationship)' },
  { value: 'cmis:policy', label: 'ポリシー (cmis:policy)' },
  { value: 'cmis:item', label: 'アイテム (cmis:item)' },
  { value: 'cmis:secondary', label: 'セカンダリ (cmis:secondary)' }
];

// Property Types
const PROPERTY_TYPES = [
  { value: 'string', label: '文字列 (string)' },
  { value: 'integer', label: '整数 (integer)' },
  { value: 'decimal', label: '小数 (decimal)' },
  { value: 'boolean', label: '真偽値 (boolean)' },
  { value: 'datetime', label: '日時 (datetime)' }
];

// Cardinality Options
const CARDINALITY_OPTIONS = [
  { value: 'single', label: '単一値 (single)' },
  { value: 'multi', label: '複数値 (multi)' }
];

// Updatability Options
const UPDATABILITY_OPTIONS = [
  { value: 'readwrite', label: '読み書き可能 (readwrite)' },
  { value: 'readonly', label: '読み取り専用 (readonly)' },
  { value: 'oncreate', label: '作成時のみ (oncreate)' }
];

interface PropertyFormData {
  id: string;
  displayName: string;
  description: string;
  propertyType: 'string' | 'integer' | 'decimal' | 'boolean' | 'datetime';
  cardinality: 'single' | 'multi';
  updatability: 'readwrite' | 'readonly' | 'oncreate';
  required: boolean;
  queryable: boolean;
  openChoice: boolean;
}

interface TypeFormData {
  id: string;
  localName: string;
  localNamespace: string;
  displayName: string;
  description: string;
  baseId: string;
  parentId: string;
  creatable: boolean;
  queryable: boolean;
  fulltextIndexed: boolean;
  includedInSupertypeQuery: boolean;
  controllablePolicy: boolean;
  controllableACL: boolean;
  propertyDefinitions: PropertyFormData[];
  allowedSourceTypes?: string[];
  allowedTargetTypes?: string[];
}

// Extract prefix from type ID (e.g., "nemaki:customDocument" -> "nemaki:")
const extractPrefix = (typeId: string): string => {
  if (!typeId) return '';
  const colonIndex = typeId.indexOf(':');
  if (colonIndex > 0) {
    return typeId.substring(0, colonIndex + 1);
  }
  return '';
};

interface TypeGUIEditorProps {
  initialValue: TypeDefinition | null;
  existingTypes: TypeDefinition[];
  onSave: (typeDefinition: any) => void;
  onCancel: () => void;
  isEditing?: boolean;
}

// Convert TypeDefinition to form data
const typeDefinitionToFormData = (typeDef: TypeDefinition | null): TypeFormData => {
  if (!typeDef) {
    return {
      id: '',
      localName: '',
      localNamespace: '',
      displayName: '',
      description: '',
      baseId: 'cmis:document',
      parentId: '',
      creatable: true,
      queryable: true,
      fulltextIndexed: true,
      includedInSupertypeQuery: true,
      controllablePolicy: true,
      controllableACL: true,
      propertyDefinitions: [],
      allowedSourceTypes: [],
      allowedTargetTypes: []
    };
  }

  // Convert propertyDefinitions from Record to array
  const properties: PropertyFormData[] = [];
  if (typeDef.propertyDefinitions) {
    Object.entries(typeDef.propertyDefinitions).forEach(([id, prop]) => {
      properties.push({
        id: prop.id || id,
        displayName: prop.displayName || '',
        description: prop.description || '',
        propertyType: prop.propertyType || 'string',
        cardinality: prop.cardinality || 'single',
        updatability: prop.updatable ? 'readwrite' : 'readonly',
        required: prop.required || false,
        queryable: prop.queryable || false,
        openChoice: false
      });
    });
  }

  return {
    id: typeDef.id || '',
    localName: typeDef.id || '',
    localNamespace: '',
    displayName: typeDef.displayName || '',
    description: typeDef.description || '',
    baseId: typeDef.baseTypeId || 'cmis:document',
    parentId: typeDef.parentTypeId || '',
    creatable: typeDef.creatable !== false,
    queryable: typeDef.queryable !== false,
    fulltextIndexed: true,
    includedInSupertypeQuery: true,
    controllablePolicy: true,
    controllableACL: true,
    propertyDefinitions: properties,
    allowedSourceTypes: typeDef.allowedSourceTypes || [],
    allowedTargetTypes: typeDef.allowedTargetTypes || []
  };
};

// Convert form data to JSON for API
const formDataToJson = (formData: TypeFormData): any => {
  const propertyDefinitions: PropertyFormData[] = formData.propertyDefinitions || [];
  
  const result: any = {
    id: formData.id,
    localName: formData.localName || formData.id,
    localNamespace: formData.localNamespace || '',
    displayName: formData.displayName || formData.id,
    description: formData.description || '',
    baseId: formData.baseId,
    parentId: formData.parentId || formData.baseId,
    creatable: formData.creatable,
    queryable: formData.queryable,
    fulltextIndexed: formData.fulltextIndexed,
    includedInSupertypeQuery: formData.includedInSupertypeQuery,
    controllablePolicy: formData.controllablePolicy,
    controllableACL: formData.controllableACL,
    propertyDefinitions: propertyDefinitions.map(prop => ({
      id: prop.id,
      displayName: prop.displayName || prop.id,
      description: prop.description || '',
      propertyType: prop.propertyType,
      cardinality: prop.cardinality,
      updatability: prop.updatability,
      required: prop.required,
      queryable: prop.queryable,
      openChoice: prop.openChoice
    }))
  };

  // Add relationship-specific fields if baseId is cmis:relationship
  if (formData.baseId === 'cmis:relationship') {
    if (formData.allowedSourceTypes && formData.allowedSourceTypes.length > 0) {
      result.allowedSourceTypes = formData.allowedSourceTypes;
    }
    if (formData.allowedTargetTypes && formData.allowedTargetTypes.length > 0) {
      result.allowedTargetTypes = formData.allowedTargetTypes;
    }
  }

  return result;
};

export const TypeGUIEditor: React.FC<TypeGUIEditorProps> = ({
  initialValue,
  existingTypes,
  onSave,
  onCancel,
  isEditing = false
}) => {
  const [form] = Form.useForm();
  const [formData, setFormData] = useState<TypeFormData>(typeDefinitionToFormData(initialValue));
  const [activeTab, setActiveTab] = useState<string>('gui');
  const [jsonText, setJsonText] = useState<string>('');
  const [jsonError, setJsonError] = useState<string>('');
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [validationWarnings, setValidationWarnings] = useState<string[]>([]);

  // Initialize form with initial value
  useEffect(() => {
    const data = typeDefinitionToFormData(initialValue);
    setFormData(data);
    form.setFieldsValue(data);
    setJsonText(JSON.stringify(formDataToJson(data), null, 2));
  }, [initialValue, form]);

  // Update JSON preview when form data changes
  useEffect(() => {
    if (activeTab === 'gui') {
      setJsonText(JSON.stringify(formDataToJson(formData), null, 2));
    }
  }, [formData, activeTab]);

  // Validate form data - returns { errors, warnings }
  const validateFormData = (data: TypeFormData): { errors: string[], warnings: string[] } => {
    const errors: string[] = [];
    const warnings: string[] = [];

    if (!data.id || data.id.trim() === '') {
      errors.push('タイプIDは必須です');
    } else if (!/^[a-zA-Z][a-zA-Z0-9_:]*$/.test(data.id)) {
      errors.push('タイプIDは英字で始まり、英数字、アンダースコア、コロンのみ使用できます');
    }

    if (!data.baseId) {
      errors.push('ベースタイプは必須です');
    }

    // Check for duplicate type ID (only for new types)
    if (!isEditing && existingTypes.some(t => t.id === data.id)) {
      errors.push('このタイプIDは既に存在します');
    }

    // Check for duplicate display name (warning level)
    if (data.displayName && existingTypes.some(t => t.displayName === data.displayName && t.id !== data.id)) {
      warnings.push(`表示名 "${data.displayName}" は既に他のタイプで使用されています`);
    }

    // Validate property definitions
    const propertyIds = new Set<string>();
    data.propertyDefinitions.forEach((prop, index) => {
      if (!prop.id || prop.id.trim() === '') {
        errors.push(`プロパティ ${index + 1}: IDは必須です`);
      } else {
        // Validate property ID format
        if (!/^[a-zA-Z][a-zA-Z0-9_:]*$/.test(prop.id)) {
          errors.push(`プロパティ ${index + 1}: IDは英字で始まり、英数字、アンダースコア、コロンのみ使用できます`);
        }
        if (propertyIds.has(prop.id)) {
          errors.push(`プロパティ ${index + 1}: ID "${prop.id}" は重複しています`);
        } else {
          propertyIds.add(prop.id);
        }
      }

      if (!prop.propertyType) {
        errors.push(`プロパティ ${index + 1}: データ型は必須です`);
      }

      // Check for duplicate property display name within the type (warning)
      const duplicateDisplayName = data.propertyDefinitions.filter(
        (p, i) => i !== index && p.displayName && p.displayName === prop.displayName
      );
      if (prop.displayName && duplicateDisplayName.length > 0) {
        warnings.push(`プロパティ ${index + 1}: 表示名 "${prop.displayName}" は重複しています`);
      }
    });

    // Validate relationship-specific fields
    if (data.baseId === 'cmis:relationship') {
      // Check if allowedSourceTypes reference existing types (warning)
      if (data.allowedSourceTypes) {
        data.allowedSourceTypes.forEach(typeId => {
          if (!existingTypes.some(t => t.id === typeId)) {
            warnings.push(`ソースタイプ "${typeId}" は未定義です`);
          }
        });
      }
      // Check if allowedTargetTypes reference existing types (warning)
      if (data.allowedTargetTypes) {
        data.allowedTargetTypes.forEach(typeId => {
          if (!existingTypes.some(t => t.id === typeId)) {
            warnings.push(`ターゲットタイプ "${typeId}" は未定義です`);
          }
        });
      }
    }

    return { errors, warnings };
  };

  // Handle form field changes
  const handleFormChange = (_changedValues: any, allValues: any) => {
    const newFormData = { ...formData, ...allValues };
    setFormData(newFormData);
    const { errors, warnings } = validateFormData(newFormData);
    setValidationErrors(errors);
    setValidationWarnings(warnings);
  };

  // Handle JSON text changes
  const handleJsonChange = (value: string) => {
    setJsonText(value);
    try {
      const parsed = JSON.parse(value);
      setJsonError('');
      
      // Handle propertyDefinitions in both array and object format
      let propertyDefinitions: PropertyFormData[] = [];
      if (Array.isArray(parsed.propertyDefinitions)) {
        propertyDefinitions = parsed.propertyDefinitions.map((prop: any) => ({
          id: prop.id || '',
          displayName: prop.displayName || '',
          description: prop.description || '',
          propertyType: prop.propertyType || 'string',
          cardinality: prop.cardinality || 'single',
          updatability: prop.updatability || 'readwrite',
          required: prop.required || false,
          queryable: prop.queryable || false,
          openChoice: prop.openChoice || false
        }));
      } else if (parsed.propertyDefinitions && typeof parsed.propertyDefinitions === 'object') {
        propertyDefinitions = Object.entries(parsed.propertyDefinitions).map(([id, prop]: [string, any]) => ({
          id: id,
          displayName: prop.displayName || '',
          description: prop.description || '',
          propertyType: prop.propertyType || 'string',
          cardinality: prop.cardinality || 'single',
          updatability: prop.updatability || 'readwrite',
          required: prop.required || false,
          queryable: prop.queryable || false,
          openChoice: prop.openChoice || false
        }));
      }
      
      // Convert parsed JSON to form data
      const newFormData: TypeFormData = {
        id: parsed.id || '',
        localName: parsed.localName || '',
        localNamespace: parsed.localNamespace || '',
        displayName: parsed.displayName || '',
        description: parsed.description || '',
        baseId: parsed.baseId || 'cmis:document',
        parentId: parsed.parentId || '',
        creatable: parsed.creatable !== false,
        queryable: parsed.queryable !== false,
        fulltextIndexed: parsed.fulltextIndexed !== false,
        includedInSupertypeQuery: parsed.includedInSupertypeQuery !== false,
        controllablePolicy: parsed.controllablePolicy !== false,
        controllableACL: parsed.controllableACL !== false,
        propertyDefinitions: propertyDefinitions,
        // Relationship-specific fields
        allowedSourceTypes: Array.isArray(parsed.allowedSourceTypes) ? parsed.allowedSourceTypes : undefined,
        allowedTargetTypes: Array.isArray(parsed.allowedTargetTypes) ? parsed.allowedTargetTypes : undefined
      };
      
      setFormData(newFormData);
      form.setFieldsValue(newFormData);
      const { errors, warnings } = validateFormData(newFormData);
      setValidationErrors(errors);
      setValidationWarnings(warnings);
    } catch (e) {
      setJsonError('JSONの形式が正しくありません');
    }
  };

  // Add new property with auto-prefix based on type ID
  const addProperty = () => {
    const prefix = extractPrefix(formData.id);
    const newProperty: PropertyFormData = {
      id: prefix,
      displayName: '',
      description: '',
      propertyType: 'string',
      cardinality: 'single',
      updatability: 'readwrite',
      required: false,
      queryable: true,
      openChoice: false
    };
    const newProperties = [...formData.propertyDefinitions, newProperty];
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    form.setFieldsValue(newFormData);
  };

  // Remove property
  const removeProperty = (index: number) => {
    const newProperties = formData.propertyDefinitions.filter((_, i) => i !== index);
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    form.setFieldsValue(newFormData);
  };

  // Update property
  const updateProperty = (index: number, field: keyof PropertyFormData, value: any) => {
    const newProperties = [...formData.propertyDefinitions];
    newProperties[index] = { ...newProperties[index], [field]: value };
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    const { errors, warnings } = validateFormData(newFormData);
    setValidationErrors(errors);
    setValidationWarnings(warnings);
  };

  // Handle save
  const handleSave = () => {
    const { errors, warnings } = validateFormData(formData);
    if (errors.length > 0) {
      setValidationErrors(errors);
      setValidationWarnings(warnings);
      return;
    }

    const jsonData = formDataToJson(formData);
    onSave(jsonData);
  };

  // Render property editor
  const renderPropertyEditor = (property: PropertyFormData, index: number) => (
    <Card
      key={index}
      size="small"
      style={{ marginBottom: 12 }}
      title={
        <Space>
          <Text strong>プロパティ {index + 1}</Text>
          {property.id && <Text type="secondary">({property.id})</Text>}
        </Space>
      }
      extra={
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          onClick={() => removeProperty(index)}
        >
          削除
        </Button>
      }
    >
      <Row gutter={16}>
        <Col span={8}>
          <Form.Item label="プロパティID" required>
            <Input
              value={property.id}
              onChange={(e) => updateProperty(index, 'id', e.target.value)}
              placeholder="例: nemaki:customProperty"
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="表示名">
            <Input
              value={property.displayName}
              onChange={(e) => updateProperty(index, 'displayName', e.target.value)}
              placeholder="プロパティの表示名"
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="データ型" required>
            <Select
              value={property.propertyType}
              onChange={(value) => updateProperty(index, 'propertyType', value)}
              options={PROPERTY_TYPES}
            />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={16}>
        <Col span={8}>
          <Form.Item label="多重度" required>
            <Select
              value={property.cardinality}
              onChange={(value) => updateProperty(index, 'cardinality', value)}
              options={CARDINALITY_OPTIONS}
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="更新可能性">
            <Select
              value={property.updatability}
              onChange={(value) => updateProperty(index, 'updatability', value)}
              options={UPDATABILITY_OPTIONS}
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="説明">
            <Input
              value={property.description}
              onChange={(e) => updateProperty(index, 'description', e.target.value)}
              placeholder="プロパティの説明"
            />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={16}>
        <Col span={8}>
          <Form.Item label="必須">
            <Switch
              checked={property.required}
              onChange={(checked) => updateProperty(index, 'required', checked)}
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="検索可能">
            <Switch
              checked={property.queryable}
              onChange={(checked) => updateProperty(index, 'queryable', checked)}
            />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="オープンチョイス">
            <Switch
              checked={property.openChoice}
              onChange={(checked) => updateProperty(index, 'openChoice', checked)}
            />
          </Form.Item>
        </Col>
      </Row>
    </Card>
  );

  // GUI Editor Tab Content
  const guiEditorContent = (
    <Form
      form={form}
      layout="vertical"
      onValuesChange={handleFormChange}
      initialValues={formData}
    >
      <Collapse defaultActiveKey={['basic', 'properties']} style={{ marginBottom: 16 }}>
        <Panel header="基本情報" key="basic">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label={
                  <Space>
                    タイプID
                    <Tooltip title="タイプの一意識別子。例: nemaki:customDocument">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="id"
                rules={[{ required: true, message: 'タイプIDを入力してください' }]}
              >
                <Input
                  placeholder="例: nemaki:customDocument"
                  disabled={isEditing}
                  onChange={(e) => {
                    const newFormData = { ...formData, id: e.target.value };
                    setFormData(newFormData);
                    const { errors, warnings } = validateFormData(newFormData);
                    setValidationErrors(errors);
                    setValidationWarnings(warnings);
                  }}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="表示名"
                name="displayName"
              >
                <Input
                  placeholder="タイプの表示名"
                  onChange={(e) => setFormData({ ...formData, displayName: e.target.value })}
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label={
                  <Space>
                    ベースタイプ
                    <Tooltip title="CMISの基本タイプ。ドキュメント、フォルダなどから選択">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="baseId"
                rules={[{ required: true, message: 'ベースタイプを選択してください' }]}
              >
                <Select
                  options={BASE_TYPES}
                  onChange={(value) => setFormData({ ...formData, baseId: value })}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label={
                  <Space>
                    親タイプ
                    <Tooltip title="継承元のタイプ。空の場合はベースタイプが使用されます">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="parentId"
              >
                <Select
                  allowClear
                  placeholder="親タイプを選択（オプション）"
                  onChange={(value) => setFormData({ ...formData, parentId: value || '' })}
                >
                  {existingTypes.map(type => (
                    <Select.Option key={type.id} value={type.id}>
                      {type.displayName || type.id}
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            label="説明"
            name="description"
          >
            <TextArea
              rows={2}
              placeholder="タイプの説明"
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            />
          </Form.Item>
        </Panel>

        <Panel header="タイプオプション" key="options">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    作成可能
                    <Tooltip title="このタイプのオブジェクトを作成できるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="creatable"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, creatable: checked })}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    検索可能
                    <Tooltip title="このタイプのオブジェクトをクエリで検索できるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="queryable"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, queryable: checked })}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    全文検索対象
                    <Tooltip title="全文検索インデックスに含めるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="fulltextIndexed"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, fulltextIndexed: checked })}
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    スーパータイプクエリに含める
                    <Tooltip title="親タイプのクエリ結果に含めるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="includedInSupertypeQuery"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, includedInSupertypeQuery: checked })}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    ポリシー制御可能
                    <Tooltip title="ポリシーを適用できるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="controllablePolicy"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, controllablePolicy: checked })}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    ACL制御可能
                    <Tooltip title="アクセス制御リストを適用できるかどうか">
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="controllableACL"
                valuePropName="checked"
              >
                <Switch
                  onChange={(checked) => setFormData({ ...formData, controllableACL: checked })}
                />
              </Form.Item>
            </Col>
          </Row>
        </Panel>

        {formData.baseId === 'cmis:relationship' && (
          <Panel header="リレーションシップ設定" key="relationship">
            <Alert
              message="リレーションシップタイプの設定"
              description="ソースタイプとターゲットタイプを指定して、どのタイプ間のリレーションシップを許可するかを定義します。"
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  label={
                    <Space>
                      許可されるソースタイプ
                      <Tooltip title="このリレーションシップのソースとして許可されるタイプを選択します">
                        <QuestionCircleOutlined />
                      </Tooltip>
                    </Space>
                  }
                >
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder="ソースタイプを選択"
                    value={formData.allowedSourceTypes}
                    onChange={(values) => {
                      const newFormData = { ...formData, allowedSourceTypes: values };
                      setFormData(newFormData);
                      const { errors, warnings } = validateFormData(newFormData);
                      setValidationErrors(errors);
                      setValidationWarnings(warnings);
                    }}
                    style={{ width: '100%' }}
                  >
                    {existingTypes
                      .filter(t => t.baseTypeId === 'cmis:document' || t.baseTypeId === 'cmis:folder')
                      .map(type => (
                        <Select.Option key={type.id} value={type.id}>
                          {type.displayName || type.id}
                        </Select.Option>
                      ))}
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label={
                    <Space>
                      許可されるターゲットタイプ
                      <Tooltip title="このリレーションシップのターゲットとして許可されるタイプを選択します">
                        <QuestionCircleOutlined />
                      </Tooltip>
                    </Space>
                  }
                >
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder="ターゲットタイプを選択"
                    value={formData.allowedTargetTypes}
                    onChange={(values) => {
                      const newFormData = { ...formData, allowedTargetTypes: values };
                      setFormData(newFormData);
                      const { errors, warnings } = validateFormData(newFormData);
                      setValidationErrors(errors);
                      setValidationWarnings(warnings);
                    }}
                    style={{ width: '100%' }}
                  >
                    {existingTypes
                      .filter(t => t.baseTypeId === 'cmis:document' || t.baseTypeId === 'cmis:folder')
                      .map(type => (
                        <Select.Option key={type.id} value={type.id}>
                          {type.displayName || type.id}
                        </Select.Option>
                      ))}
                  </Select>
                </Form.Item>
              </Col>
            </Row>
          </Panel>
        )}

        <Panel header="プロパティ定義" key="properties">
          {formData.propertyDefinitions.length === 0 ? (
            <Alert
              message="プロパティが定義されていません"
              description="「プロパティを追加」ボタンをクリックしてプロパティを追加してください"
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
          ) : (
            formData.propertyDefinitions.map((prop, index) => renderPropertyEditor(prop, index))
          )}
          <Button
            type="dashed"
            onClick={addProperty}
            block
            icon={<PlusOutlined />}
          >
            プロパティを追加
          </Button>
        </Panel>
      </Collapse>
    </Form>
  );

  // JSON Editor Tab Content
  const jsonEditorContent = (
    <div>
      <Alert
        message="JSON形式で直接編集"
        description="型定義をJSON形式で直接編集できます。変更はGUIエディタと同期されます。"
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />
      {jsonError && (
        <Alert
          message={jsonError}
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}
      <TextArea
        value={jsonText}
        onChange={(e) => handleJsonChange(e.target.value)}
        rows={25}
        style={{ fontFamily: 'monospace', fontSize: 12 }}
      />
    </div>
  );

  const tabItems = [
    {
      key: 'gui',
      label: (
        <Space>
          <FormOutlined />
          GUIエディタ
        </Space>
      ),
      children: guiEditorContent
    },
    {
      key: 'json',
      label: (
        <Space>
          <CodeOutlined />
          JSONエディタ
        </Space>
      ),
      children: jsonEditorContent
    }
  ];

  return (
    <div>
      {isEditing && (
        <Alert
          message="CMIS非準拠の操作"
          description={
            <div>
              <p style={{ margin: '0 0 8px 0' }}>
                <strong>注意:</strong> タイプ定義の編集はCMIS標準に準拠していないNemakiWare独自の操作です。
              </p>
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                <li>既存のドキュメントに影響を与える可能性があります</li>
                <li>プロパティ定義の変更は既存データとの整合性に注意が必要です</li>
                <li>他のCMISクライアントとの互換性が保証されません</li>
              </ul>
            </div>
          }
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {validationErrors.length > 0 && (
        <Alert
          message="入力エラー"
          description={
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              {validationErrors.map((error, index) => (
                <li key={index}>{error}</li>
              ))}
            </ul>
          }
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {validationWarnings.length > 0 && (
        <Alert
          message="警告"
          description={
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              {validationWarnings.map((warning, index) => (
                <li key={index}>{warning}</li>
              ))}
            </ul>
          }
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />

      <Divider />

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <Button onClick={onCancel}>
          キャンセル
        </Button>
        <Button
          type="primary"
          onClick={handleSave}
          disabled={validationErrors.length > 0 || (activeTab === 'json' && !!jsonError)}
        >
          {isEditing ? '更新' : '作成'}
        </Button>
      </div>
    </div>
  );
};

export default TypeGUIEditor;
