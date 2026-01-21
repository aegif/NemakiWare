import React, { useState, useEffect, useMemo } from 'react';
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
  Col,
  Checkbox,
  Badge
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  QuestionCircleOutlined,
  CodeOutlined,
  FormOutlined,
  HolderOutlined,
  SearchOutlined,
  ExpandAltOutlined,
  ShrinkOutlined
} from '@ant-design/icons';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { TypeDefinition } from '../../types/cmis';
import { useTranslation } from 'react-i18next';

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
  // Internal unique key for drag-and-drop
  _key?: string;
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

// Generate unique key for property
let propertyKeyCounter = 0;
const generatePropertyKey = () => `prop_${++propertyKeyCounter}_${Date.now()}`;

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
        openChoice: false,
        _key: generatePropertyKey()
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

// Sortable Property Card Component
interface SortablePropertyCardProps {
  property: PropertyFormData;
  index: number;
  isSelected: boolean;
  isExpanded: boolean;
  onToggleSelect: (index: number) => void;
  onToggleExpand: (key: string) => void;
  onUpdate: (index: number, field: keyof PropertyFormData, value: any) => void;
  onRemove: (index: number) => void;
}

const SortablePropertyCard: React.FC<SortablePropertyCardProps> = ({
  property,
  index,
  isSelected,
  isExpanded,
  onToggleSelect,
  onToggleExpand,
  onUpdate,
  onRemove
}) => {
  const { t } = useTranslation();
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging
  } = useSortable({ id: property._key || property.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    marginBottom: 12
  };

  return (
    <div ref={setNodeRef} style={style}>
      <Card
        size="small"
        style={{
          border: isSelected ? '2px solid #1890ff' : undefined,
          backgroundColor: isDragging ? '#fafafa' : undefined
        }}
        title={
          <Space>
            <div {...attributes} {...listeners} style={{ cursor: 'grab' }}>
              <HolderOutlined style={{ color: '#999' }} />
            </div>
            <Checkbox
              checked={isSelected}
              onChange={() => onToggleSelect(index)}
            />
            <Text strong>#{index + 1}</Text>
            {property.id && <Text type="secondary">({property.id})</Text>}
            {property.displayName && (
              <Text type="secondary">- {property.displayName}</Text>
            )}
          </Space>
        }
        extra={
          <Space>
            <Button
              type="text"
              size="small"
              icon={isExpanded ? <ShrinkOutlined /> : <ExpandAltOutlined />}
              onClick={() => onToggleExpand(property._key || property.id)}
            />
            <Button
              type="text"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => onRemove(index)}
            />
          </Space>
        }
      >
        {isExpanded ? (
          <>
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyIdLabel')} required>
                  <Input
                    value={property.id}
                    onChange={(e) => onUpdate(index, 'id', e.target.value)}
                    placeholder={t('typeManagement.guiEditor.propertyIdPlaceholder')}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyDisplayNameLabel')}>
                  <Input
                    value={property.displayName}
                    onChange={(e) => onUpdate(index, 'displayName', e.target.value)}
                    placeholder={t('typeManagement.guiEditor.propertyDisplayNamePlaceholder')}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyDataTypeLabel')} required>
                  <Select
                    value={property.propertyType}
                    onChange={(value) => onUpdate(index, 'propertyType', value)}
                    options={PROPERTY_TYPES.map(pt => ({ value: pt.value, label: t(`typeManagement.propertyTypes.${pt.value}`) }))}
                  />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyCardinalityLabel')} required>
                  <Select
                    value={property.cardinality}
                    onChange={(value) => onUpdate(index, 'cardinality', value)}
                    options={CARDINALITY_OPTIONS.map(co => ({ value: co.value, label: t(`typeManagement.cardinalityOptions.${co.value}`) }))}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyUpdatabilityLabel')}>
                  <Select
                    value={property.updatability}
                    onChange={(value) => onUpdate(index, 'updatability', value)}
                    options={UPDATABILITY_OPTIONS.map(uo => ({ value: uo.value, label: t(`typeManagement.updatabilityOptions.${uo.value}`) }))}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyDescriptionLabel')}>
                  <Input
                    value={property.description}
                    onChange={(e) => onUpdate(index, 'description', e.target.value)}
                    placeholder={t('typeManagement.guiEditor.propertyDescriptionPlaceholder')}
                  />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyRequiredLabel')}>
                  <Switch
                    checked={property.required}
                    onChange={(checked) => onUpdate(index, 'required', checked)}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyQueryableLabel')}>
                  <Switch
                    checked={property.queryable}
                    onChange={(checked) => onUpdate(index, 'queryable', checked)}
                  />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item label={t('typeManagement.guiEditor.propertyOpenChoiceLabel')}>
                  <Switch
                    checked={property.openChoice}
                    onChange={(checked) => onUpdate(index, 'openChoice', checked)}
                  />
                </Form.Item>
              </Col>
            </Row>
          </>
        ) : (
          <Row gutter={16}>
            <Col span={6}>
              <Text type="secondary">{t('typeManagement.guiEditor.collapsedIdLabel')}</Text> {property.id || '-'}
            </Col>
            <Col span={6}>
              <Text type="secondary">{t('typeManagement.guiEditor.collapsedTypeLabel')}</Text> {t(`typeManagement.propertyTypes.${property.propertyType}`)}
            </Col>
            <Col span={6}>
              <Text type="secondary">{t('typeManagement.guiEditor.collapsedCardinalityLabel')}</Text> {property.cardinality === 'multi' ? t('typeManagement.guiEditor.collapsedCardinalityMulti') : t('typeManagement.guiEditor.collapsedCardinalitySingle')}
            </Col>
            <Col span={6}>
              <Text type="secondary">{t('typeManagement.guiEditor.collapsedRequiredLabel')}</Text> {property.required ? t('typeManagement.guiEditor.collapsedRequiredYes') : t('typeManagement.guiEditor.collapsedRequiredNo')}
            </Col>
          </Row>
        )}
      </Card>
    </div>
  );
};

export const TypeGUIEditor: React.FC<TypeGUIEditorProps> = ({
  initialValue,
  existingTypes,
  onSave,
  onCancel,
  isEditing = false
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [formData, setFormData] = useState<TypeFormData>(typeDefinitionToFormData(initialValue));
  const [activeTab, setActiveTab] = useState<string>('gui');
  const [jsonText, setJsonText] = useState<string>('');
  const [jsonError, setJsonError] = useState<string>('');
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [validationWarnings, setValidationWarnings] = useState<string[]>([]);

  // New states for enhanced UI
  const [searchText, setSearchText] = useState<string>('');
  const [selectedProperties, setSelectedProperties] = useState<Set<number>>(new Set());
  const [expandedProperties, setExpandedProperties] = useState<Set<string>>(new Set());
  const [allExpanded, setAllExpanded] = useState<boolean>(true);

  // DnD sensors
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  // Initialize form with initial value
  useEffect(() => {
    const data = typeDefinitionToFormData(initialValue);
    setFormData(data);
    form.setFieldsValue(data);
    setJsonText(JSON.stringify(formDataToJson(data), null, 2));
    // Expand all properties by default
    setExpandedProperties(new Set(data.propertyDefinitions.map(p => p._key || p.id)));
  }, [initialValue, form]);

  // Update JSON preview when form data changes
  useEffect(() => {
    if (activeTab === 'gui') {
      setJsonText(JSON.stringify(formDataToJson(formData), null, 2));
    }
  }, [formData, activeTab]);

  // Filter properties based on search text
  const filteredProperties = useMemo(() => {
    if (!searchText.trim()) {
      return formData.propertyDefinitions;
    }
    const search = searchText.toLowerCase();
    return formData.propertyDefinitions.filter(prop =>
      prop.id.toLowerCase().includes(search) ||
      prop.displayName.toLowerCase().includes(search) ||
      prop.description.toLowerCase().includes(search)
    );
  }, [formData.propertyDefinitions, searchText]);

  // Validate form data - returns { errors, warnings }
  const validateFormData = (data: TypeFormData): { errors: string[], warnings: string[] } => {
    const errors: string[] = [];
    const warnings: string[] = [];

    if (!data.id || data.id.trim() === '') {
      errors.push(t('typeManagement.validation.typeIdRequired'));
    } else if (!/^[a-zA-Z][a-zA-Z0-9_:]*$/.test(data.id)) {
      errors.push(t('typeManagement.validation.typeIdInvalidFormat'));
    }

    if (!data.baseId) {
      errors.push(t('typeManagement.validation.baseTypeRequired'));
    }

    // Check for duplicate type ID (only for new types)
    if (!isEditing && existingTypes.some(t => t.id === data.id)) {
      errors.push(t('typeManagement.validation.typeIdExists'));
    }

    // Check for duplicate display name (warning level)
    if (data.displayName && existingTypes.some(t => t.displayName === data.displayName && t.id !== data.id)) {
      warnings.push(t('typeManagement.validation.displayNameDuplicate', { name: data.displayName }));
    }

    // Validate property definitions
    const propertyIds = new Set<string>();
    data.propertyDefinitions.forEach((prop, index) => {
      if (!prop.id || prop.id.trim() === '') {
        errors.push(t('typeManagement.validation.propertyIdRequired', { index: index + 1 }));
      } else {
        // Validate property ID format
        if (!/^[a-zA-Z][a-zA-Z0-9_:]*$/.test(prop.id)) {
          errors.push(t('typeManagement.validation.propertyIdInvalidFormat', { index: index + 1 }));
        }
        if (propertyIds.has(prop.id)) {
          errors.push(t('typeManagement.validation.propertyIdDuplicate', { index: index + 1, id: prop.id }));
        } else {
          propertyIds.add(prop.id);
        }
      }

      if (!prop.propertyType) {
        errors.push(t('typeManagement.validation.propertyDataTypeRequired', { index: index + 1 }));
      }

      // Check for duplicate property display name within the type (warning)
      const duplicateDisplayName = data.propertyDefinitions.filter(
        (p, i) => i !== index && p.displayName && p.displayName === prop.displayName
      );
      if (prop.displayName && duplicateDisplayName.length > 0) {
        warnings.push(t('typeManagement.validation.propertyDisplayNameDuplicate', { index: index + 1, name: prop.displayName }));
      }
    });

    // Validate relationship-specific fields
    if (data.baseId === 'cmis:relationship') {
      // Check if allowedSourceTypes reference existing types (warning)
      if (data.allowedSourceTypes) {
        data.allowedSourceTypes.forEach(typeId => {
          if (!existingTypes.some(t => t.id === typeId)) {
            warnings.push(t('typeManagement.validation.sourceTypeUndefined', { typeId }));
          }
        });
      }
      // Check if allowedTargetTypes reference existing types (warning)
      if (data.allowedTargetTypes) {
        data.allowedTargetTypes.forEach(typeId => {
          if (!existingTypes.some(t => t.id === typeId)) {
            warnings.push(t('typeManagement.validation.targetTypeUndefined', { typeId }));
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
          openChoice: prop.openChoice || false,
          _key: generatePropertyKey()
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
          openChoice: prop.openChoice || false,
          _key: generatePropertyKey()
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
      setExpandedProperties(new Set(propertyDefinitions.map(p => p._key || p.id)));
      const { errors, warnings } = validateFormData(newFormData);
      setValidationErrors(errors);
      setValidationWarnings(warnings);
    } catch (e) {
      setJsonError(t('typeManagement.validation.jsonInvalid'));
    }
  };

  // Add new property with auto-prefix based on type ID
  const addProperty = () => {
    const prefix = extractPrefix(formData.id);
    const newKey = generatePropertyKey();
    const newProperty: PropertyFormData = {
      id: prefix,
      displayName: '',
      description: '',
      propertyType: 'string',
      cardinality: 'single',
      updatability: 'readwrite',
      required: false,
      queryable: true,
      openChoice: false,
      _key: newKey
    };
    const newProperties = [...formData.propertyDefinitions, newProperty];
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    form.setFieldsValue(newFormData);
    // Expand the new property
    setExpandedProperties(prev => new Set([...prev, newKey]));
  };

  // Remove property
  const removeProperty = (index: number) => {
    const newProperties = formData.propertyDefinitions.filter((_, i) => i !== index);
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    form.setFieldsValue(newFormData);
    // Remove from selection
    setSelectedProperties(prev => {
      const next = new Set(prev);
      next.delete(index);
      return next;
    });
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

  // Handle drag end for reordering
  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;

    if (over && active.id !== over.id) {
      const oldIndex = formData.propertyDefinitions.findIndex(p => (p._key || p.id) === active.id);
      const newIndex = formData.propertyDefinitions.findIndex(p => (p._key || p.id) === over.id);

      const newProperties = arrayMove(formData.propertyDefinitions, oldIndex, newIndex);
      const newFormData = { ...formData, propertyDefinitions: newProperties };
      setFormData(newFormData);
      form.setFieldsValue(newFormData);
    }
  };

  // Toggle property selection
  const togglePropertySelect = (index: number) => {
    setSelectedProperties(prev => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  };

  // Toggle property expansion
  const togglePropertyExpand = (key: string) => {
    setExpandedProperties(prev => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  // Toggle all properties expansion
  const toggleAllExpanded = () => {
    if (allExpanded) {
      setExpandedProperties(new Set());
    } else {
      setExpandedProperties(new Set(formData.propertyDefinitions.map(p => p._key || p.id)));
    }
    setAllExpanded(!allExpanded);
  };

  // Select all visible properties
  const selectAllVisible = () => {
    const visibleIndices = formData.propertyDefinitions
      .map((prop, index) => ({ prop, index }))
      .filter(({ prop }) => {
        if (!searchText.trim()) return true;
        const search = searchText.toLowerCase();
        return prop.id.toLowerCase().includes(search) ||
          prop.displayName.toLowerCase().includes(search) ||
          prop.description.toLowerCase().includes(search);
      })
      .map(({ index }) => index);

    setSelectedProperties(new Set(visibleIndices));
  };

  // Delete selected properties
  const deleteSelectedProperties = () => {
    if (selectedProperties.size === 0) return;

    const newProperties = formData.propertyDefinitions.filter((_, index) => !selectedProperties.has(index));
    const newFormData = { ...formData, propertyDefinitions: newProperties };
    setFormData(newFormData);
    form.setFieldsValue(newFormData);
    setSelectedProperties(new Set());
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

  // GUI Editor Tab Content
  const guiEditorContent = (
    <Form
      form={form}
      layout="vertical"
      onValuesChange={handleFormChange}
      initialValues={formData}
    >
      <Collapse defaultActiveKey={['basic', 'properties']} style={{ marginBottom: 16 }}>
        <Panel header={t('typeManagement.guiEditor.basicInfo')} key="basic">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label={
                  <Space>
                    {t('typeManagement.guiEditor.typeIdLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.typeIdTooltip')}>
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="id"
                rules={[{ required: true, message: t('typeManagement.guiEditor.typeIdRequired') }]}
              >
                <Input
                  placeholder={t('typeManagement.guiEditor.typeIdPlaceholder')}
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
                label={t('typeManagement.guiEditor.displayNameLabel')}
                name="displayName"
              >
                <Input
                  placeholder={t('typeManagement.guiEditor.displayNamePlaceholder')}
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
                    {t('typeManagement.guiEditor.baseTypeLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.baseTypeTooltip')}>
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="baseId"
                rules={[{ required: true, message: t('typeManagement.guiEditor.baseTypeRequired') }]}
              >
                <Select
                  options={BASE_TYPES.map(bt => ({ value: bt.value, label: t(`typeManagement.baseTypes.${bt.value.split(':')[1]}`) + ` (${bt.value})` }))}
                  onChange={(value) => setFormData({ ...formData, baseId: value })}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label={
                  <Space>
                    {t('typeManagement.guiEditor.parentTypeLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.parentTypeTooltip')}>
                      <QuestionCircleOutlined />
                    </Tooltip>
                  </Space>
                }
                name="parentId"
              >
                <Select
                  allowClear
                  placeholder={t('typeManagement.guiEditor.parentTypePlaceholder')}
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
            label={t('typeManagement.guiEditor.descriptionLabel')}
            name="description"
          >
            <TextArea
              rows={2}
              placeholder={t('typeManagement.guiEditor.descriptionPlaceholder')}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            />
          </Form.Item>
        </Panel>

        <Panel header={t('typeManagement.guiEditor.typeOptions')} key="options">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                label={
                  <Space>
                    {t('typeManagement.guiEditor.creatableLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.creatableTooltip')}>
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
                    {t('typeManagement.guiEditor.queryableLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.queryableTooltip')}>
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
                    {t('typeManagement.guiEditor.fulltextIndexedLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.fulltextIndexedTooltip')}>
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
                    {t('typeManagement.guiEditor.includedInSupertypeQueryLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.includedInSupertypeQueryTooltip')}>
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
                    {t('typeManagement.guiEditor.controllablePolicyLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.controllablePolicyTooltip')}>
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
                    {t('typeManagement.guiEditor.controllableACLLabel')}
                    <Tooltip title={t('typeManagement.guiEditor.controllableACLTooltip')}>
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
          <Panel header={t('typeManagement.guiEditor.relationshipSettings')} key="relationship">
            <Alert
              message={t('typeManagement.guiEditor.relationshipSettingsTitle')}
              description={t('typeManagement.guiEditor.relationshipSettingsDescription')}
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  label={
                    <Space>
                      {t('typeManagement.guiEditor.allowedSourceTypesLabel')}
                      <Tooltip title={t('typeManagement.guiEditor.allowedSourceTypesTooltip')}>
                        <QuestionCircleOutlined />
                      </Tooltip>
                    </Space>
                  }
                >
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder={t('typeManagement.guiEditor.allowedSourceTypesPlaceholder')}
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
                      {t('typeManagement.guiEditor.allowedTargetTypesLabel')}
                      <Tooltip title={t('typeManagement.guiEditor.allowedTargetTypesTooltip')}>
                        <QuestionCircleOutlined />
                      </Tooltip>
                    </Space>
                  }
                >
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder={t('typeManagement.guiEditor.allowedTargetTypesPlaceholder')}
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

        <Panel
          header={
            <Space>
              {t('typeManagement.guiEditor.propertyDefinitions')}
              <Badge count={formData.propertyDefinitions.length} style={{ backgroundColor: '#1890ff' }} />
            </Space>
          }
          key="properties"
        >
          {/* Property toolbar */}
          <div style={{ marginBottom: 16 }}>
            <Row gutter={16} align="middle">
              <Col flex="auto">
                <Input
                  prefix={<SearchOutlined />}
                  placeholder={t('typeManagement.guiEditor.searchProperties')}
                  value={searchText}
                  onChange={(e) => setSearchText(e.target.value)}
                  allowClear
                />
              </Col>
              <Col>
                <Space>
                  <Button
                    icon={allExpanded ? <ShrinkOutlined /> : <ExpandAltOutlined />}
                    onClick={toggleAllExpanded}
                  >
                    {allExpanded ? t('typeManagement.guiEditor.collapseAll') : t('typeManagement.guiEditor.expandAll')}
                  </Button>
                  <Button onClick={selectAllVisible}>
                    {t('typeManagement.guiEditor.selectAllVisible')}
                  </Button>
                  {selectedProperties.size > 0 && (
                    <Button
                      danger
                      icon={<DeleteOutlined />}
                      onClick={deleteSelectedProperties}
                    >
                      {t('typeManagement.guiEditor.deleteSelected', { count: selectedProperties.size })}
                    </Button>
                  )}
                </Space>
              </Col>
            </Row>
          </div>

          {formData.propertyDefinitions.length === 0 ? (
            <Alert
              message={t('typeManagement.guiEditor.noPropertiesTitle')}
              description={t('typeManagement.guiEditor.noPropertiesDescription')}
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
          ) : (
            <>
              {searchText.trim() && filteredProperties.length === 0 ? (
                <Alert
                  message={t('typeManagement.guiEditor.noSearchResults')}
                  description={t('typeManagement.guiEditor.noSearchResultsDescription', { searchText })}
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
              ) : (
                <DndContext
                  sensors={sensors}
                  collisionDetection={closestCenter}
                  onDragEnd={handleDragEnd}
                >
                  <SortableContext
                    items={formData.propertyDefinitions.map(p => p._key || p.id)}
                    strategy={verticalListSortingStrategy}
                  >
                    {formData.propertyDefinitions.map((prop, index) => {
                      // Hide if not matching search
                      if (searchText.trim()) {
                        const search = searchText.toLowerCase();
                        const matches = prop.id.toLowerCase().includes(search) ||
                          prop.displayName.toLowerCase().includes(search) ||
                          prop.description.toLowerCase().includes(search);
                        if (!matches) return null;
                      }

                      return (
                        <SortablePropertyCard
                          key={prop._key || prop.id}
                          property={prop}
                          index={index}
                          isSelected={selectedProperties.has(index)}
                          isExpanded={expandedProperties.has(prop._key || prop.id)}
                          onToggleSelect={togglePropertySelect}
                          onToggleExpand={togglePropertyExpand}
                          onUpdate={updateProperty}
                          onRemove={removeProperty}
                        />
                      );
                    })}
                  </SortableContext>
                </DndContext>
              )}
            </>
          )}
          <Button
            type="dashed"
            onClick={addProperty}
            block
            icon={<PlusOutlined />}
          >
            {t('typeManagement.guiEditor.addProperty')}
          </Button>
        </Panel>
      </Collapse>
    </Form>
  );

  // JSON Editor Tab Content
  const jsonEditorContent = (
    <div>
      <Alert
        message={t('typeManagement.guiEditor.jsonEditorTitle')}
        description={t('typeManagement.guiEditor.jsonEditorDescription')}
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
          {t('typeManagement.guiEditor.guiEditorTab')}
        </Space>
      ),
      children: guiEditorContent
    },
    {
      key: 'json',
      label: (
        <Space>
          <CodeOutlined />
          {t('typeManagement.guiEditor.jsonEditorTab')}
        </Space>
      ),
      children: jsonEditorContent
    }
  ];

  return (
    <div>
      {isEditing && (
        <Alert
          message={t('typeManagement.guiEditor.nonCmisWarningTitle')}
          description={
            <div>
              <p style={{ margin: '0 0 8px 0' }}>
                <strong>{t('typeManagement.guiEditor.nonCmisWarningNote')}</strong> {t('typeManagement.guiEditor.nonCmisWarningDescription')}
              </p>
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                <li>{t('typeManagement.guiEditor.nonCmisWarningItem1')}</li>
                <li>{t('typeManagement.guiEditor.nonCmisWarningItem2')}</li>
                <li>{t('typeManagement.guiEditor.nonCmisWarningItem3')}</li>
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
          message={t('typeManagement.guiEditor.inputError')}
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
          message={t('typeManagement.guiEditor.warning')}
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
          {t('typeManagement.guiEditor.cancel')}
        </Button>
        <Button
          type="primary"
          onClick={handleSave}
          disabled={validationErrors.length > 0 || (activeTab === 'json' && !!jsonError)}
        >
          {isEditing ? t('typeManagement.guiEditor.update') : t('typeManagement.guiEditor.create')}
        </Button>
      </div>
    </div>
  );
};

export default TypeGUIEditor;
