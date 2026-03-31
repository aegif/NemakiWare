import { useState, useEffect, useCallback } from 'react';
import type { IntegrationSettingsResponse, SettingSource, UpdateResult, ConnectionTestResult } from '../../services/integrationSettings';

interface UseSettingsTabOptions {
  fetchSettings: () => Promise<IntegrationSettingsResponse>;
  saveSettings: (settings: Record<string, string>) => Promise<UpdateResult>;
  testConnection?: (formValues?: Record<string, string>) => Promise<ConnectionTestResult>;
}

export function useSettingsTab({ fetchSettings, saveSettings, testConnection }: UseSettingsTabOptions) {
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [sources, setSources] = useState<Record<string, SettingSource>>({});
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);
  const [saveWarning, setSaveWarning] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchSettings();
      setSettings(data.settings);
      setSources(data.sources);
      setFormValues({ ...data.settings });
      // Show/clear dual-backend conflict warning from GET response
      setSaveWarning(data.warning ?? null);
    } catch (err) {
      console.error('Failed to load settings:', err);
    } finally {
      setLoading(false);
    }
  }, [fetchSettings]);

  useEffect(() => {
    load();
  }, [load]);

  const hasChanges = Object.keys(formValues).some(key => formValues[key] !== settings[key]);

  const handleSave = async () => {
    setSaving(true);
    setSaveWarning(null);
    try {
      // Only send fields that actually changed to avoid writing
      // runtime defaults (blank → CouchDB) for untouched keys.
      const changedFields: Record<string, string> = {};
      for (const key of Object.keys(formValues)) {
        if (formValues[key] !== settings[key]) {
          changedFields[key] = formValues[key];
        }
      }
      await saveSettings(changedFields);
      // load() will fetch the latest state including any dual-backend warning
      await load();
      return true;
    } catch (err) {
      console.error('Failed to save settings:', err);
      return false;
    } finally {
      setSaving(false);
    }
  };

  const handleTestConnection = async () => {
    if (!testConnection) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = await testConnection(formValues);
      setTestResult(result);
    } catch (err) {
      setTestResult({
        status: 'failure',
        message: err instanceof Error ? err.message : 'Connection test failed',
      });
    } finally {
      setTesting(false);
    }
  };

  const updateField = (key: string, value: string) => {
    setFormValues(prev => ({ ...prev, [key]: value }));
  };

  return {
    settings,
    sources,
    formValues,
    loading,
    saving,
    testing,
    testResult,
    saveWarning,
    hasChanges,
    handleSave,
    handleTestConnection,
    updateField,
    reload: load,
  };
}
