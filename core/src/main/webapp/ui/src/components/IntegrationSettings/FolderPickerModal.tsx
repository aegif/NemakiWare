import { useCallback, useEffect, useState } from 'react';
import { Alert, Modal, Spin, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { FolderOutlined, FolderOpenOutlined, CheckCircleTwoTone, CloseCircleTwoTone } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { CMISService } from '../../services/cmis';
import { listConnectorSummary } from '../../services/externalIngest';

const { Text } = Typography;

interface Props {
  open: boolean;
  repositoryId: string;
  /** Currently-bound folder id, if any — used to pre-select in the tree. */
  currentFolderId?: string;
  onSelect: (folderId: string, folderName?: string) => void;
  onCancel: () => void;
}

/**
 * Pickable folder tree for the delegated profile editor.
 *
 * <p><b>Why this exists.</b> Non-admins previously had to copy-paste a
 * folder ID into the form by hand. The /summary endpoint gave instant
 * ✓/✗ feedback once they had a candidate ID, but they still needed to
 * find that ID somehow — which usually meant breaking out of the
 * Integration Settings screen, navigating the Documents tree, and
 * copying the ID from the URL. This component eliminates that loop.
 *
 * <p><b>How permission filtering works.</b> The tree is populated from
 * the standard CMIS Browser Binding via {@link CMISService#getChildren}
 * — so it shows folders the user can <i>read</i>. {@code cmis:all} is a
 * stricter check (read + write + admin-ish) and isn't exposed by the
 * browser binding's allowable-actions in a single field. We therefore
 * probe it lazily on selection by calling
 * {@link listConnectorSummary} for the chosen folder — the endpoint
 * fails with 403 for callers without {@code cmis:all}, which is
 * exactly the gate we need. The Confirm button stays disabled until
 * the probe returns OK.
 *
 * <p>This is deliberately a one-folder-at-a-time probe: bulk-probing
 * every visible folder up front would multiply the load on the
 * authorization service and slow tree expansion noticeably.
 */
export function FolderPickerModal({
  open,
  repositoryId,
  currentFolderId,
  onSelect,
  onCancel,
}: Props) {
  const { t } = useTranslation();
  const [cmisService] = useState(() => new CMISService(() => { /* auth errors bubble via Error */ }));

  const [treeData, setTreeData] = useState<DataNode[]>([]);
  const [rootLoading, setRootLoading] = useState(false);
  const [rootLoadError, setRootLoadError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [accessProbe, setAccessProbe] = useState<'idle' | 'checking' | 'ok' | 'denied'>('idle');
  const [probeError, setProbeError] = useState<string | null>(null);

  /** Convert a CMIS folder into an Antd tree node. */
  const folderToNode = (id: string, name: string): DataNode => ({
    key: id,
    title: name || id,
    icon: <FolderOutlined />,
    isLeaf: false,   // tree always treats folder nodes as expandable; loadData decides
  });

  /** Load + filter children of the given folder to folder-only nodes. */
  const loadFolderChildren = useCallback(async (folderId: string): Promise<DataNode[]> => {
    const children = await cmisService.getChildren(repositoryId, folderId);
    return children
      .filter(c => c.baseType === 'cmis:folder')
      .map(c => folderToNode(c.id, c.name));
  }, [cmisService, repositoryId]);

  /** Initial mount: fetch the repository root and its immediate children. */
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    (async () => {
      setRootLoading(true);
      setRootLoadError(null);
      setSelectedId(null);
      setSelectedName(null);
      setAccessProbe('idle');
      setProbeError(null);
      try {
        const root = await cmisService.getRootFolder(repositoryId);
        const firstLevel = await loadFolderChildren(root.id);
        if (cancelled) return;
        // Wrap everything under a root node so the user can also pick the
        // repository root itself (some operators store profiles at /).
        setTreeData([{
          ...folderToNode(root.id, root.name || '/'),
          children: firstLevel,
        }]);
      } catch (err) {
        if (cancelled) return;
        setRootLoadError(err instanceof Error ? err.message : 'load failed');
      } finally {
        if (!cancelled) setRootLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, repositoryId, cmisService, loadFolderChildren]);

  /**
   * Antd Tree's lazy-load callback. Fires the first time a folder node
   * is expanded. We mutate the tree in place because Antd compares the
   * `children` reference for re-rendering — the helper below does the
   * minimal targeted update.
   */
  const onLoadData = async (node: DataNode): Promise<void> => {
    if (node.children) return;       // already loaded
    const children = await loadFolderChildren(node.key as string);
    setTreeData(prev => mergeChildrenInto(prev, node.key as string, children));
  };

  /** Probe cmis:all via /summary the moment a folder is selected. */
  const onSelectNode = async (keys: React.Key[], info: { node: DataNode }) => {
    const id = keys[0] as string | undefined;
    if (!id) {
      setSelectedId(null); setSelectedName(null);
      setAccessProbe('idle'); setProbeError(null);
      return;
    }
    setSelectedId(id);
    setSelectedName(info.node.title as string);
    setAccessProbe('checking');
    setProbeError(null);
    try {
      await listConnectorSummary(repositoryId, id);
      setAccessProbe('ok');
    } catch (err) {
      setAccessProbe('denied');
      // Summary helper wraps the HTTP status into the message. We surface
      // a friendly Japanese/English line; the underlying status is
      // available in the dev console for support.
      setProbeError(err instanceof Error
        ? t('folderPicker.noPermission', { defaultValue: 'このフォルダに cmis:all 権限がありません。' })
        : null);
    }
  };

  const canConfirm = selectedId !== null && accessProbe === 'ok';

  return (
    <Modal
      title={t('folderPicker.title', { defaultValue: '対象フォルダを選択' })}
      open={open}
      onCancel={onCancel}
      onOk={() => selectedId && onSelect(selectedId, selectedName ?? undefined)}
      okButtonProps={{ disabled: !canConfirm }}
      okText={t('common.select', { defaultValue: '選択' })}
      cancelText={t('common.cancel')}
      width={600}
      destroyOnClose
    >
      {rootLoadError && (
        <Alert type="error" showIcon message={rootLoadError} style={{ marginBottom: 12 }} />
      )}
      {rootLoading ? (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin />
        </div>
      ) : (
        <>
          <Tree
            showIcon
            switcherIcon={<FolderOpenOutlined />}
            treeData={treeData}
            loadData={onLoadData}
            onSelect={onSelectNode}
            defaultExpandedKeys={treeData[0]?.key ? [treeData[0].key as string] : []}
            defaultSelectedKeys={currentFolderId ? [currentFolderId] : []}
            style={{ maxHeight: 360, overflow: 'auto', border: '1px solid #f0f0f0', padding: 8 }}
          />
          {selectedId && (
            <div style={{ marginTop: 12 }}>
              <Text strong>{t('folderPicker.selected', { defaultValue: '選択中' })}:</Text>{' '}
              <Text code>{selectedName}</Text> <Text type="secondary">({selectedId})</Text>
              <div style={{ marginTop: 8 }}>
                {accessProbe === 'checking' && <Spin size="small" />}
                {accessProbe === 'ok' && (
                  <span style={{ color: '#52c41a' }}>
                    <CheckCircleTwoTone twoToneColor="#52c41a" />{' '}
                    {t('folderPicker.permissionOk', { defaultValue: 'このフォルダの管理権限があります' })}
                  </span>
                )}
                {accessProbe === 'denied' && (
                  <span style={{ color: '#ff4d4f' }}>
                    <CloseCircleTwoTone twoToneColor="#ff4d4f" />{' '}
                    {probeError ?? t('folderPicker.noPermission', { defaultValue: 'このフォルダに cmis:all 権限がありません。' })}
                  </span>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </Modal>
  );
}

/**
 * Returns a new tree with {@code children} attached to the first node
 * whose key matches {@code parentKey}. Returns the original tree
 * reference when no match is found (no-op render).
 */
function mergeChildrenInto(tree: DataNode[], parentKey: string, children: DataNode[]): DataNode[] {
  let mutated = false;
  const next = tree.map(node => {
    if (node.key === parentKey) {
      mutated = true;
      return { ...node, children };
    }
    if (node.children) {
      const merged = mergeChildrenInto(node.children, parentKey, children);
      if (merged !== node.children) {
        mutated = true;
        return { ...node, children: merged };
      }
    }
    return node;
  });
  return mutated ? next : tree;
}
