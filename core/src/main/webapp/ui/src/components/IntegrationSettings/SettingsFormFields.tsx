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
  /**
   * Per-key flag: when true, this key is admin-managed (the value stored from
   * the admin UI in nemaki_conf takes precedence over any deploy-time -D/env),
   * so the field stays editable even when the current effective source is a
   * system property / env variable — saving overrides the deploy default.
   */
  overridable?: Record<string, boolean>;
  onFieldChange: (key: string, value: string) => void;
}

const SOURCE_COLORS: Record<SettingSource, string> = {
  system_property: 'red',
  environment: 'orange',
  couchdb: 'blue',
  properties_file: 'default',
  default: 'cyan',
  none: 'default',
};

export function SettingsFormFields({ fields, formValues, sources, overridable, onFieldChange }: SettingsFormFieldsProps) {
  const { t } = useTranslation();

  const sourceLabel = (source: SettingSource): string => {
    return t(`integrationSettings.source.${source}`);
  };

  const isDeployBootstrap = (source: SettingSource): boolean => {
    return source === 'system_property' || source === 'environment';
  };

  return (
    <>
      {fields.map(field => {
        const source = sources[field.key];
        const bootstrap = isDeployBootstrap(source);
        const canOverride = overridable?.[field.key] === true;
        // Locked only when a deploy-time source wins AND the key is not
        // admin-managed. Admin-managed keys stay editable so the operator can
        // supersede the deploy default from this screen.
        const locked = bootstrap && !canOverride;

        return (
          <div key={field.key}>
            {locked && (
              <Alert
                message={t('integrationSettings.overriddenWarning', {
                  source: sourceLabel(source),
                })}
                type="warning"
                showIcon
                style={{ marginBottom: 8 }}
              />
            )}
            {bootstrap && canOverride && (
              <Alert
                message={t('integrationSettings.overridableNotice', {
                  source: sourceLabel(source),
                })}
                type="info"
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
                  disabled={locked}
                />
              ) : field.type === 'select' && field.options ? (
                <Select
                  value={formValues[field.key] || field.options[0]?.value || ''}
                  onChange={value => onFieldChange(field.key, value)}
                  disabled={locked}
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
                  disabled={locked}
                />
              ) : field.type === 'textarea' ? (
                <Input.TextArea
                  value={formValues[field.key] || ''}
                  onChange={e => onFieldChange(field.key, e.target.value)}
                  rows={4}
                  disabled={locked}
                />
              ) : (
                <Input
                  value={formValues[field.key] || ''}
                  onChange={e => onFieldChange(field.key, e.target.value)}
                  placeholder={field.placeholder}
                  disabled={locked}
                />
              )}
            </Form.Item>
          </div>
        );
      })}
    </>
  );
}
