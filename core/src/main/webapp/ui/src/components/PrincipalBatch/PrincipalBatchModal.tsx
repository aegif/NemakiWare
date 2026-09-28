/**
 * The "bulk" dialog of user and group management (design docs/design/principal-batch.md §8):
 * choose → upload → preview → confirm.
 *
 * Preview writes nothing. "Confirm and run" confirms the preview's plan with onUnexpected=abort,
 * so a plan with an unexpected or forbidden row writes nothing and says why. Skipping those rows
 * is a SEPARATE button behind a confirmation dialog, never the default. Forbidden rows (the
 * built-in accounts, the actor itself) are not written even then.
 */
import React, { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Modal,
  Radio,
  Space,
  Steps,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd';
import { DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  BATCH_COLUMNS,
  BatchExecution,
  BatchKind,
  BatchOperation,
  BatchPreview,
  BatchRequestRefused,
  BatchRowOutcome,
  BatchRowVerdict,
  executePlan,
  previewBatch,
  templateCsv,
} from '../../services/principalBatch';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  repositoryId: string;
  /** The kinds this screen offers: users for user management, groups and memberships for groups. */
  kinds: BatchKind[];
  onClose: () => void;
  /** Called after an execution that may have written, so the list behind can be reloaded. */
  onApplied: () => void;
}

const VERDICT_COLOR: Record<string, string> = { expected: 'green', unexpected: 'orange', forbidden: 'red' };
const OUTCOME_COLOR: Record<string, string> = {
  applied: 'green', skipped: 'orange', forbidden: 'red', not_applied: 'default', failed: 'red',
};

export const PrincipalBatchModal: React.FC<Props> = ({ open, repositoryId, kinds, onClose, onApplied }) => {
  const { t } = useTranslation();
  const [step, setStep] = useState(0);
  const [kind, setKind] = useState<BatchKind>(kinds[0]);
  const operations = useMemo(() => Object.keys(BATCH_COLUMNS[kind]) as BatchOperation[], [kind]);
  const [operation, setOperation] = useState<BatchOperation>(operations[0]);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<BatchPreview | null>(null);
  const [execution, setExecution] = useState<BatchExecution | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setStep(0);
    setFile(null);
    setPreview(null);
    setExecution(null);
    setError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const downloadTemplate = () => {
    const blob = new Blob([templateCsv(kind, operation)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${kind}-${operation}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const runPreview = async () => {
    if (!file) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setPreview(await previewBatch(repositoryId, file, kind, operation));
      setStep(2);
    } catch (e) {
      setError(e instanceof BatchRequestRefused ? `${e.httpStatus} ${e.reason ?? ''} ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const execute = async (onUnexpected: 'abort' | 'skip') => {
    if (!preview) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await executePlan(repositoryId, preview, onUnexpected, file);
      setExecution(result);
      setStep(3);
      if (result.httpStatus === 200 || result.status === 'partial') {
        onApplied();
      }
    } catch (e) {
      setError(e instanceof BatchRequestRefused ? `${e.httpStatus} ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const confirmSkip = () => {
    Modal.confirm({
      title: t('principalBatch.skipConfirmTitle'),
      content: t('principalBatch.skipConfirmBody', {
        unexpected: preview?.counts.unexpected ?? 0,
        forbidden: preview?.counts.forbidden ?? 0,
      }),
      okText: t('principalBatch.skipConfirmOk'),
      okButtonProps: { danger: true, 'data-testid': 'principal-batch-skip-confirm-ok' } as never,
      cancelText: t('common.cancel'),
      onOk: () => execute('skip'),
    });
  };

  const notExpected = preview ? preview.counts.unexpected + preview.counts.forbidden : 0;

  return (
    <Modal
      open={open}
      title={t('principalBatch.title')}
      onCancel={close}
      footer={null}
      width={960}
      destroyOnHidden
      data-testid="principal-batch-modal"
    >
      <Steps
        current={step}
        size="small"
        style={{ marginBottom: 24 }}
        items={[
          { title: t('principalBatch.stepChoose') },
          { title: t('principalBatch.stepUpload') },
          { title: t('principalBatch.stepPreview') },
          { title: t('principalBatch.stepResult') },
        ]}
      />
      {error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error} data-testid="principal-batch-error" />}

      {step === 0 && (
        <Space direction="vertical" size="middle">
          {kinds.length > 1 && (
            <Radio.Group
              value={kind}
              onChange={(e) => {
                const k = e.target.value as BatchKind;
                setKind(k);
                setOperation(Object.keys(BATCH_COLUMNS[k])[0] as BatchOperation);
              }}
              data-testid="principal-batch-kind"
            >
              {kinds.map((k) => (
                <Radio.Button key={k} value={k}>{t(`principalBatch.kind.${k}`)}</Radio.Button>
              ))}
            </Radio.Group>
          )}
          <Radio.Group value={operation} onChange={(e) => setOperation(e.target.value)} data-testid="principal-batch-operation">
            {operations.map((op) => (
              <Radio.Button key={op} value={op} data-testid={`principal-batch-op-${op}`}>{t(`principalBatch.operation.${op}`)}</Radio.Button>
            ))}
          </Radio.Group>
          <Paragraph type="secondary">
            {t('principalBatch.columns')}: <Text code>{(BATCH_COLUMNS[kind][operation] || []).join(',')}</Text>
          </Paragraph>
          <Paragraph type="secondary">{t(`principalBatch.blankMeans.${operation === 'replace' ? 'replace' : 'other'}`)}</Paragraph>
          <Button type="primary" onClick={() => setStep(1)} data-testid="principal-batch-next">{t('common.next')}</Button>
        </Space>
      )}

      {step === 1 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Button icon={<DownloadOutlined />} onClick={downloadTemplate} data-testid="principal-batch-template">
            {t('principalBatch.template')}
          </Button>
          <Upload.Dragger
            accept=".csv,text/csv"
            maxCount={1}
            beforeUpload={(f) => {
              setFile(f as File);
              return false;
            }}
            onRemove={() => setFile(null)}
            data-testid="principal-batch-upload"
          >
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p>{t('principalBatch.uploadHint')}</p>
          </Upload.Dragger>
          <Space>
            <Button onClick={() => setStep(0)}>{t('common.back')}</Button>
            <Button type="primary" disabled={!file} loading={busy} onClick={runPreview} data-testid="principal-batch-preview">
              {t('principalBatch.preview')}
            </Button>
          </Space>
        </Space>
      )}

      {step === 2 && preview && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions size="small" bordered column={5}>
            <Descriptions.Item label={t('principalBatch.count.rows')}>{preview.counts.rows}</Descriptions.Item>
            <Descriptions.Item label={t('principalBatch.count.expected')}>
              <span data-testid="principal-batch-count-expected">{preview.counts.expected}</span>
            </Descriptions.Item>
            <Descriptions.Item label={t('principalBatch.count.unexpected')}>
              <span data-testid="principal-batch-count-unexpected">{preview.counts.unexpected}</span>
            </Descriptions.Item>
            <Descriptions.Item label={t('principalBatch.count.forbidden')}>
              <span data-testid="principal-batch-count-forbidden">{preview.counts.forbidden}</span>
            </Descriptions.Item>
            <Descriptions.Item label={t('principalBatch.count.adminGrants')}>{preview.counts.adminGrants}</Descriptions.Item>
          </Descriptions>
          {preview.passwordPresent && <Alert type="info" showIcon message={t('principalBatch.passwordPresent')} />}
          {notExpected > 0 && (
            <Alert type="warning" showIcon message={t('principalBatch.willAbort', { n: notExpected })} data-testid="principal-batch-will-abort" />
          )}
          <Table<BatchRowVerdict>
            size="small"
            rowKey={(r) => `${r.line}-${r.id}`}
            dataSource={preview.rows}
            pagination={{ pageSize: 50 }}
            data-testid="principal-batch-preview-table"
            columns={[
              { title: t('principalBatch.line'), dataIndex: 'line', width: 70 },
              { title: 'ID', dataIndex: 'id' },
              {
                title: t('principalBatch.verdict'),
                dataIndex: 'verdict',
                render: (v: string) => <Tag color={VERDICT_COLOR[v]}>{t(`principalBatch.verdictName.${v}`)}</Tag>,
              },
              { title: t('principalBatch.reason'), dataIndex: 'reason' },
              { title: t('principalBatch.message'), dataIndex: 'message' },
            ]}
          />
          <Paragraph type="secondary">{t('principalBatch.expiresAt', { at: preview.expiresAt })}</Paragraph>
          <Space>
            <Button onClick={() => setStep(1)}>{t('common.back')}</Button>
            <Button type="primary" loading={busy} onClick={() => execute('abort')} data-testid="principal-batch-confirm">
              {t('principalBatch.confirm')}
            </Button>
            <Button danger loading={busy} disabled={notExpected === 0} onClick={confirmSkip} data-testid="principal-batch-skip">
              {t('principalBatch.skip')}
            </Button>
          </Space>
        </Space>
      )}

      {step === 3 && execution && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="principal-batch-result">
          {execution.status === 'partial' && (
            <Alert
              type="error"
              showIcon
              message={t('principalBatch.partial', { line: execution.stoppedAt })}
              description={`${execution.message ?? ''}${execution.incidentId ? ` (incidentId ${execution.incidentId})` : ''}`}
            />
          )}
          {execution.httpStatus !== 200 && execution.status !== 'partial' && (
            <Alert
              type="warning"
              showIcon
              data-testid="principal-batch-refused"
              message={`${execution.httpStatus} ${execution.reason ?? execution.status ?? ''}`}
              description={execution.message}
            />
          )}
          {execution.httpStatus === 200 && (
            <Alert type="success" showIcon message={t('principalBatch.applied')} data-testid="principal-batch-applied" />
          )}
          {execution.counts && (
            <Paragraph>
              {Object.entries(execution.counts).map(([k, v]) => `${k}: ${v}`).join(' / ')}
            </Paragraph>
          )}
          {execution.rows && (
            <Table
              size="small"
              rowKey={(r) => `${r.line}-${r.id}`}
              dataSource={execution.rows as Array<BatchRowOutcome & BatchRowVerdict>}
              pagination={{ pageSize: 50 }}
              columns={[
                { title: t('principalBatch.line'), dataIndex: 'line', width: 70 },
                { title: 'ID', dataIndex: 'id' },
                {
                  title: t('principalBatch.outcome'),
                  render: (_: unknown, r: BatchRowOutcome & BatchRowVerdict) =>
                    r.outcome ? <Tag color={OUTCOME_COLOR[r.outcome]}>{r.outcome}</Tag>
                      : <Tag color={VERDICT_COLOR[r.verdict]}>{r.verdict}</Tag>,
                },
                { title: t('principalBatch.reason'), dataIndex: 'reason' },
              ]}
            />
          )}
          <Space>
            <Button onClick={reset}>{t('principalBatch.again')}</Button>
            <Button type="primary" onClick={close}>{t('common.close')}</Button>
          </Space>
        </Space>
      )}
    </Modal>
  );
};

export default PrincipalBatchModal;
