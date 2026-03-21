import { useEffect, useState } from 'react';
import { Form, Input, Alert, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getPasswordPolicy } from '../../../services/passwordPolicy';

const { Paragraph } = Typography;

export interface AdminConfig {
  newPassword: string;
  confirmPassword: string;
}

interface AdminStepProps {
  value: AdminConfig;
  onChange: (config: AdminConfig) => void;
  onValidChange: (valid: boolean) => void;
  repositoryId?: string;
}

export function AdminStep({ value, onChange, onValidChange, repositoryId }: AdminStepProps) {
  const { t } = useTranslation();
  const [minLength, setMinLength] = useState(0);

  useEffect(() => {
    if (repositoryId) {
      getPasswordPolicy(repositoryId)
        .then(policy => setMinLength(policy.minLength))
        .catch(() => setMinLength(0));
    }
  }, [repositoryId]);

  // Report initial validity on mount (both empty → skip → valid)
  useEffect(() => {
    onValidChange(true);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleChange = (field: keyof AdminConfig, val: string) => {
    const updated = { ...value, [field]: val };
    onChange(updated);
    // Valid if both empty (skip) or both filled and match with min length
    if (!updated.newPassword && !updated.confirmPassword) {
      onValidChange(true); // Skip password change
    } else if (
      (minLength <= 0 || updated.newPassword.length >= minLength) &&
      updated.newPassword.length > 0 &&
      updated.newPassword === updated.confirmPassword
    ) {
      onValidChange(true);
    } else {
      onValidChange(false);
    }
  };

  const passwordMismatch = value.newPassword && value.confirmPassword && value.newPassword !== value.confirmPassword;
  const tooShort = minLength > 0 && value.newPassword && value.newPassword.length > 0 && value.newPassword.length < minLength;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message={t('setup.admin.info')}
      />

      <Form layout="vertical">
        <Form.Item
          label={t('setup.admin.newPassword')}
          validateStatus={tooShort ? 'error' : undefined}
          help={tooShort ? t('setup.admin.passwordTooShort', { min: minLength }) : undefined}
        >
          <Input.Password
            value={value.newPassword}
            onChange={(e) => handleChange('newPassword', e.target.value)}
            placeholder={t('setup.admin.passwordPlaceholder')}
          />
        </Form.Item>
        <Form.Item
          label={t('setup.admin.confirmPassword')}
          validateStatus={passwordMismatch ? 'error' : undefined}
          help={passwordMismatch ? t('setup.admin.passwordMismatch') : undefined}
        >
          <Input.Password
            value={value.confirmPassword}
            onChange={(e) => handleChange('confirmPassword', e.target.value)}
            placeholder={t('setup.admin.confirmPlaceholder')}
          />
        </Form.Item>
      </Form>

      <Paragraph type="secondary" style={{ fontSize: 12 }}>
        {t('setup.admin.passkeyNote')}
      </Paragraph>
    </Space>
  );
}
