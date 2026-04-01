import React, { useEffect, useState, useCallback } from 'react';
import { Card, Table, Switch, Input, Button, Select, Space, Typography, message, Spin, Empty, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { AuthService } from '../../services/auth';
import {
  getPropertyMappings,
  updatePropertyMappings,
  PropertyMappingEntry,
} from '../../services/integrationSettings';

const { Text } = Typography;

interface TypeInfo {
  typeId: string;
  displayName: string;
  baseTypeId: string;
  properties: PropertyInfo[];
}

interface PropertyInfo {
  propertyId: string;
  displayName: string;
  propertyType: string;
  cardinality: string;
}

interface MappingRow {
  key: string;
  typeId: string;
  propertyId: string;
  displayName: string;
  propertyType: string;
  cardinality: string;
  enabled: boolean;
  catalogName: string;
}

interface Props {
  repositoryId: string;
}

/**
 * Property Mapping configuration section for catalog backends.
 * Allows admins to select which CMIS custom properties are synced to
 * Purview / Atlas / Dataplex as entity attributes.
 */
const PropertyMappingSection: React.FC<Props> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [customTypes, setCustomTypes] = useState<TypeInfo[]>([]);
  const [selectedTypeId, setSelectedTypeId] = useState<string | undefined>();
  const [mappings, setMappings] = useState<Record<string, Record<string, PropertyMappingEntry>>>({});
  const [rows, setRows] = useState<MappingRow[]>([]);

  // Fetch custom types from CMIS REST API
  const loadCustomTypes = useCallback(async () => {
    try {
      const authService = AuthService.getInstance();
      const headers = authService.getAuthHeaders();
      const res = await fetch(`/core/rest/repo/${repositoryId}/types?includePropertyDefinitions=true`, {
        headers: { Accept: 'application/json', ...headers },
      });
      if (!res.ok) return;
      const data = await res.json();
      const types: TypeInfo[] = [];
      if (Array.isArray(data)) {
        for (const td of data) {
          const typeId = td.typeId || td.id;
          const baseTypeId = td.baseId || td.baseTypeId || '';
          // Only show custom document/folder subtypes (not cmis: built-in types)
          if (typeId && !typeId.startsWith('cmis:') && (baseTypeId === 'cmis:document' || baseTypeId === 'cmis:folder')) {
            const props: PropertyInfo[] = [];
            const propDefs = td.propertyDefinitions || td.properties || {};
            if (typeof propDefs === 'object') {
              for (const [propId, propDef] of Object.entries(propDefs)) {
                const pd = propDef as Record<string, unknown>;
                // Only show non-inherited, non-cmis properties
                if (!propId.startsWith('cmis:') && pd.inherited !== true) {
                  props.push({
                    propertyId: propId,
                    displayName: (pd.displayName as string) || propId,
                    propertyType: (pd.propertyType as string) || 'STRING',
                    cardinality: (pd.cardinality as string) || 'SINGLE',
                  });
                }
              }
            }
            if (props.length > 0) {
              types.push({
                typeId,
                displayName: td.displayName || typeId,
                baseTypeId,
                properties: props,
              });
            }
          }
        }
      }
      setCustomTypes(types);
      // Always select the first type when types are loaded (handles repo switch)
      if (types.length > 0) {
        setSelectedTypeId(prev => {
          if (prev && types.some(t => t.typeId === prev)) return prev;
          return types[0].typeId;
        });
      } else {
        setSelectedTypeId(undefined);
      }
    } catch (err) {
      console.error('Failed to load custom types:', err);
    }
  }, [repositoryId]);

  // Load existing mappings from backend (repository-scoped)
  const loadMappings = useCallback(async () => {
    try {
      const data = await getPropertyMappings(repositoryId);
      setMappings(data.mappings || {});
    } catch (err) {
      console.error('Failed to load property mappings:', err);
    }
  }, [repositoryId]);

  useEffect(() => {
    setLoading(true);
    Promise.all([loadCustomTypes(), loadMappings()]).finally(() => setLoading(false));
  }, [loadCustomTypes, loadMappings]);

  // Build table rows when selectedTypeId or mappings change
  useEffect(() => {
    if (!selectedTypeId) {
      setRows([]);
      return;
    }
    const typeInfo = customTypes.find(t => t.typeId === selectedTypeId);
    if (!typeInfo) {
      setRows([]);
      return;
    }
    const typeMappings = mappings[selectedTypeId] || {};
    setRows(
      typeInfo.properties.map(prop => {
        const existing = typeMappings[prop.propertyId];
        return {
          key: `${selectedTypeId}:${prop.propertyId}`,
          typeId: selectedTypeId,
          propertyId: prop.propertyId,
          displayName: prop.displayName,
          propertyType: prop.propertyType,
          cardinality: prop.cardinality,
          enabled: existing?.enabled ?? false,
          catalogName: existing?.catalogName ?? prop.propertyId.replace(/:/g, '_'),
        };
      })
    );
  }, [selectedTypeId, customTypes, mappings]);

  const handleToggle = (propertyId: string, checked: boolean) => {
    setRows(prev => prev.map(r => r.propertyId === propertyId ? { ...r, enabled: checked } : r));
  };

  const handleCatalogNameChange = (propertyId: string, value: string) => {
    setRows(prev => prev.map(r => r.propertyId === propertyId ? { ...r, catalogName: value } : r));
  };

  const handleSave = async () => {
    if (!selectedTypeId) return;
    // Client-side validation: reject blank catalogName on enabled mappings
    const blankNames = rows.filter(r => r.enabled && (!r.catalogName || !r.catalogName.trim()));
    if (blankNames.length > 0) {
      message.error(t('integrationSettings.blankCatalogNameError',
        { properties: blankNames.map(r => r.propertyId).join(', ') }));
      return;
    }
    setSaving(true);
    try {
      const updated = { ...mappings };
      const typeMappings: Record<string, PropertyMappingEntry> = {};
      for (const row of rows) {
        typeMappings[row.propertyId] = {
          enabled: row.enabled,
          catalogName: row.catalogName,
        };
      }
      updated[selectedTypeId] = typeMappings;
      const result = await updatePropertyMappings(repositoryId, updated);
      setMappings(updated);
      message.success(t('integrationSettings.propertyMappingSaved'));
      // Show cross-repo conflict warnings if any
      const warnings = result.warnings;
      if (warnings && warnings.length > 0) {
        message.warning(warnings.join('\n'), 8);
      }
    } catch (err) {
      const detail = err instanceof Error ? err.message : '';
      message.error(detail || t('integrationSettings.propertyMappingSaveFailed'));
      console.error('Failed to save property mappings:', err);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      title: t('integrationSettings.propertyId'),
      dataIndex: 'propertyId',
      key: 'propertyId',
      render: (text: string, record: MappingRow) => (
        <Space direction="vertical" size={0}>
          <Text strong>{text}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {record.displayName !== text ? record.displayName : ''}
          </Text>
        </Space>
      ),
    },
    {
      title: t('integrationSettings.propertyType'),
      dataIndex: 'propertyType',
      key: 'propertyType',
      width: 120,
      render: (text: string, record: MappingRow) => (
        <Space>
          <Tag>{text}</Tag>
          {record.cardinality === 'MULTI' && <Tag color="blue">MULTI</Tag>}
        </Space>
      ),
    },
    {
      title: t('integrationSettings.syncEnabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (_: boolean, record: MappingRow) => (
        <Switch
          checked={record.enabled}
          size="small"
          onChange={(checked) => handleToggle(record.propertyId, checked)}
        />
      ),
    },
    {
      title: t('integrationSettings.catalogAttributeName'),
      dataIndex: 'catalogName',
      key: 'catalogName',
      width: 200,
      render: (_: string, record: MappingRow) => (
        <Input
          size="small"
          value={record.catalogName}
          disabled={!record.enabled}
          onChange={(e) => handleCatalogNameChange(record.propertyId, e.target.value)}
        />
      ),
    },
  ];

  if (loading) {
    return <Spin style={{ display: 'block', margin: '24px auto' }} />;
  }

  if (customTypes.length === 0) {
    return (
      <Card title={t('integrationSettings.propertyMapping')} style={{ marginTop: 16 }}>
        <Empty description={t('integrationSettings.noCustomTypes')} />
      </Card>
    );
  }

  return (
    <Card title={t('integrationSettings.propertyMapping')} style={{ marginTop: 16 }}>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Space>
          <Text>{t('integrationSettings.selectType')}:</Text>
          <Select
            value={selectedTypeId}
            onChange={setSelectedTypeId}
            style={{ minWidth: 240 }}
            options={customTypes.map(t => ({
              value: t.typeId,
              label: `${t.displayName} (${t.typeId})`,
            }))}
          />
        </Space>

        <Table
          dataSource={rows}
          columns={columns}
          pagination={false}
          size="small"
          bordered
        />

        <Button
          type="primary"
          onClick={handleSave}
          loading={saving}
          disabled={rows.length === 0}
        >
          {t('integrationSettings.savePropertyMappings')}
        </Button>
      </Space>
    </Card>
  );
};

export default PropertyMappingSection;
