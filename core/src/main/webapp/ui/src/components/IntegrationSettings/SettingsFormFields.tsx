import { Form, Input, Select, Switch, Tag, Alert } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SettingSource } from '../../services/integrationSettings';

interface SelectOption {
  value: string;
  labelKey: string;
}

export interface FieldDef {
  key: string;
  labelKey: string;
  type: 'text' | 'password' | 'boolean' | 'textarea' | 'select';
  sensitive?: boolean;
  options?: SelectOption[];
  helpKey?: string;
  placeholder?: string;
}

interface SettingsFormFieldsProps {
  fields: FieldDef[];
  formValues: Record<string, string>;
  sources: Record<string, SettingSource>;
  onFieldChange: (key: string, value: string) => void;
}

const SOURCE_COLORS: Record<SettingSource, string> = {
  system_property: 'red',
  environment: 'orange',
  couchdb: 'blue',
  properties_file: 'default',
  none: 'default',
};

export function SettingsFormFields({ fields, formValues, sources, onFieldChange }: SettingsFormFieldsProps) {
  const { t } = useTranslation();

  const sourceLabel = (source: SettingSource): string => {
    return t(`integrationSettings.source.${source}`);
  };

  const isOverridden = (source: SettingSource): boolean => {
    return source === 'system_property' || source === 'environment';
  };

  return (
    <>
      {fields.map(field => {
        const source = sources[field.key];
        const overridden = isOverridden(source);

        return (
          <div key={field.key}>
            {overridden && (
              <Alert
                message={t('integrationSettings.overriddenWarning', {
                  source: sourceLabel(source),
                })}
                type="warning"
                showIcon
                style={{ marginBottom: 8 }}
              />
            )}
            <Form.Item
              label={
                <span>
                  {t(field.labelKey)}
                  {source && source !== 'none' && (
                    <Tag
                      color={SOURCE_COLORS[source]}
                      style={{ marginLeft: 8 }}
                    >
                      {sourceLabel(source)}
                    </Tag>
                  )}
                </span>
              }
              help={field.helpKey ? t(field.helpKey) : undefined}
            >
              {field.type === 'boolean' ? (
                <Switch
                  checked={formValues[field.key] === 'true'}
                  onChange={checked => onFieldChange(field.key, String(checked))}
                  disabled={overridden}
                />
              ) : field.type === 'select' && field.options ? (
                <Select
                  value={formValues[field.key] || field.options[0]?.value || ''}
                  onChange={value => onFieldChange(field.key, value)}
                  disabled={overridden}
                >
                  {field.options.map(opt => (
                    <Select.Option key={opt.value} value={opt.value}>
                      {t(opt.labelKey)}
                    </Select.Option>
                  ))}
                </Select>
              ) : field.type === 'password' ? (
                <Input.Password
                  value={formValues[field.key] || ''}
                  onChange={e => onFieldChange(field.key, e.target.value)}
                  placeholder={field.sensitive ? '[configured]' : ''}
                  disabled={overridden}
                />
              ) : field.type === 'textarea' ? (
                <Input.TextArea
                  value={formValues[field.key] || ''}
                  onChange={e => onFieldChange(field.key, e.target.value)}
                  rows={4}
                  disabled={overridden}
                />
              ) : (
                <Input
                  value={formValues[field.key] || ''}
                  onChange={e => onFieldChange(field.key, e.target.value)}
                  placeholder={field.placeholder}
                  disabled={overridden}
                />
              )}
            </Form.Item>
          </div>
        );
      })}
    </>
  );
}
