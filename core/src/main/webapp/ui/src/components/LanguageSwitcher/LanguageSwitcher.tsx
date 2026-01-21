import React from 'react';
import { Select } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { languages, LanguageCode } from '../../i18n';

interface LanguageSwitcherProps {
  style?: React.CSSProperties;
  size?: 'small' | 'middle' | 'large';
  showIcon?: boolean;
}

const LanguageSwitcher: React.FC<LanguageSwitcherProps> = ({
  style,
  size = 'middle',
  showIcon = true
}) => {
  const { i18n } = useTranslation();

  const handleLanguageChange = (value: LanguageCode) => {
    i18n.changeLanguage(value);
  };

  const currentLanguage = (i18n.language?.split('-')[0] || 'ja') as LanguageCode;

  const options = Object.entries(languages).map(([code, lang]) => ({
    value: code,
    label: lang.nativeName
  }));

  return (
    <Select
      value={currentLanguage}
      onChange={handleLanguageChange}
      options={options}
      size={size}
      style={{ minWidth: 100, ...style }}
      suffixIcon={showIcon ? <GlobalOutlined /> : undefined}
    />
  );
};

export default LanguageSwitcher;
