/**
 * ObjectPicker Component for NemakiWare React UI
 *
 * A modal dialog for selecting CMIS objects through tree navigation or search.
 * Used for relationship target selection and other object picking scenarios.
 *
 * Features:
 * - Tree tab: Navigate folder hierarchy to select objects
 * - Search tab: Search objects by keyword
 * - Filter support: all, folder, or document types
 * - Selected object preview with name and ID
 *
 * Usage:
 * ```tsx
 * <ObjectPicker
 *   repositoryId="bedroom"
 *   visible={showPicker}
 *   onSelect={(obj) => setTarget(obj)}
 *   onCancel={() => setShowPicker(false)}
 *   title="Select Target Object"
 *   filterType="document"
 * />
 * ```
 */

import React, { useState, useEffect } from 'react';
import { Modal, Tabs, Tree, Input, Table, Button, Space, Spin, Alert, message } from 'antd';
import { FolderOutlined, FileOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { CMISService } from '../../services/cmis';
import { SearchCutShortError } from '../../services/searchCutShort';
import { CMISObject } from '../../types/cmis';
import { formatServerDate } from '../../utils/dateUtils';

interface ObjectPickerProps {
  repositoryId: string;
  visible: boolean;
  onSelect: (object: CMISObject) => void;
  onCancel: () => void;
  title?: string;
  filterType?: 'all' | 'folder' | 'document';
}

interface TreeNode {
  key: string;
  title: string;
  icon: React.ReactNode;
  isLeaf?: boolean;
  children?: TreeNode[];
  data?: CMISObject;
}

export const ObjectPicker: React.FC<ObjectPickerProps> = ({
  repositoryId,
  visible,
  onSelect,
  onCancel,
  title,
  filterType = 'all'
}) => {
  const { t } = useTranslation();
  const defaultTitle = t('objectPicker.title');
  const [activeTab, setActiveTab] = useState<string>('tree');
  const [treeData, setTreeData] = useState<TreeNode[]>([]);
  const [treeLoading, setTreeLoading] = useState(true);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selectedObject, setSelectedObject] = useState<CMISObject | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<CMISObject[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [currentFolderChildren, setCurrentFolderChildren] = useState<CMISObject[]>([]);
  // The last search was answered cut short by the ACL scan limit (R344, R349): the guidance stands in the search tab.
  const [searchCutShort, setSearchCutShort] = useState(false);
  // Only the last search started may write the picker (R349); reopening the dialog starts afresh.
  const searchSeqRef = React.useRef(0);
  // Only the last root load started may write the tree (R349): one from before the dialog was reopened writes nothing.
  const rootLoadSeqRef = React.useRef(0);
  // Only the folder chosen last in the tree may write its children (R349); reopening the dialog starts afresh.
  const childrenSeqRef = React.useRef(0);

  const cmisService = new CMISService();

  useEffect(() => {
    if (visible) {
      loadRootFolder();
      // A search still in flight from the last time the dialog was open no longer writes it, nor holds its spinner.
      searchSeqRef.current++;
      // Nor a folder chosen in the tree then, and the rows of the folder shown then go (R349).
      childrenSeqRef.current++;
      setCurrentFolderChildren([]);
      setSearchLoading(false);
      setSelectedObject(null);
      setSearchQuery('');
      setSearchResults([]);
      setSearchCutShort(false);
    }
  }, [visible, repositoryId]);

  const loadRootFolder = async () => {
    const mySeq = ++rootLoadSeqRef.current;
    const isStale = () => rootLoadSeqRef.current !== mySeq;
    // The tree starts afresh (R349): if this root load fails, no tree from before is left to expand or choose from.
    setTreeData([]);
    setExpandedKeys([]);
    try {
      setTreeLoading(true);
      const rootFolder = await cmisService.getRootFolder(repositoryId);
      if (isStale()) return;
      const rootNode: TreeNode = {
        key: rootFolder.id,
        title: rootFolder.name || t('objectPicker.root'),
        icon: <FolderOutlined />,
        isLeaf: false,
        data: rootFolder,
      };

      setTreeData([rootNode]);
      setExpandedKeys([rootFolder.id]);

      const children = await cmisService.getChildren(repositoryId, rootFolder.id);
      if (isStale()) return;
      setCurrentFolderChildren(filterChildren(children));
    } catch (error) {
      if (isStale()) return;
      message.error(t('objectPicker.messages.rootLoadError'));
    } finally {
      if (!isStale()) setTreeLoading(false);
    }
  };

  const filterChildren = (children: CMISObject[]): CMISObject[] => {
    if (filterType === 'all') return children;
    if (filterType === 'folder') return children.filter(c => c.baseType === 'cmis:folder');
    if (filterType === 'document') return children.filter(c => c.baseType === 'cmis:document');
    return children;
  };

  const loadChildren = async (parentId: string): Promise<TreeNode[]> => {
    try {
      const children = await cmisService.getChildren(repositoryId, parentId);
      const folders = children.filter(child => child.baseType === 'cmis:folder');

      return folders.map(folder => ({
        key: folder.id,
        title: folder.name,
        icon: <FolderOutlined />,
        isLeaf: false,
        data: folder,
      }));
    } catch (error) {
      message.error(t('objectPicker.messages.folderLoadError'));
      return [];
    }
  };

  const onLoadData = async ({ key }: { key: string }): Promise<void> => {
    // An expansion asked for before the dialog was reopened writes nothing into the tree loaded since (R349).
    const since = rootLoadSeqRef.current;
    const children = await loadChildren(key);
    if (rootLoadSeqRef.current !== since) return;

    setTreeData(prevTreeData => {
      const updateNode = (nodes: TreeNode[]): TreeNode[] => {
        return nodes.map(node => {
          if (node.key === key) {
            return { ...node, children };
          }
          if (node.children) {
            return { ...node, children: updateNode(node.children) };
          }
          return node;
        });
      };
      return updateNode(prevTreeData);
    });
  };

  const handleTreeSelect = async (selectedKeys: React.Key[]) => {
    if (selectedKeys.length > 0) {
      const folderId = selectedKeys[0] as string;
      const mySeq = ++childrenSeqRef.current;
      const isStale = () => childrenSeqRef.current !== mySeq;
      // Another folder is another dataset (R349): the rows and the choice of the folder before go while it loads, and a
      // choice made while it loads goes when it answers.
      setCurrentFolderChildren([]);
      setSelectedObject(null);
      try {
        const children = await cmisService.getChildren(repositoryId, folderId);
        if (isStale()) return;
        setSelectedObject(null);
        setCurrentFolderChildren(filterChildren(children));
      } catch (error) {
        if (isStale()) return;
        message.error(t('objectPicker.messages.contentsLoadError'));
      }
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      message.warning(t('objectPicker.messages.searchKeywordRequired'));
      return;
    }

    const mySeq = ++searchSeqRef.current;
    const isStale = () => searchSeqRef.current !== mySeq;
    setSearchCutShort(false);
    // A search is a new dataset (R349): an object chosen before it is not confirmed while it runs, nor as its answer.
    setSelectedObject(null);
    try {
      setSearchLoading(true);
      const result = await cmisService.search(repositoryId, searchQuery);
      if (isStale()) return;
      // Answered (R349): an object chosen while the search ran — in the tree, too — is not confirmed over its rows.
      setSelectedObject(null);
      const filtered = filterType === 'all'
        ? result.objects
        : result.objects.filter(obj =>
            filterType === 'folder' ? obj.baseType === 'cmis:folder' : obj.baseType === 'cmis:document'
          );
      setSearchResults(filtered);
    } catch (error) {
      if (isStale()) return;
      // A failed search has no rows, and an object chosen from the previous rows is not confirmed as its answer (R349).
      setSearchResults([]);
      setSelectedObject(null);
      if (error instanceof SearchCutShortError) {
        // Cut short by the ACL scan limit (R344): the guidance in the search tab, not an error toast.
        setSearchCutShort(true);
        return;
      }
      message.error(t('objectPicker.messages.searchError'));
    } finally {
      if (!isStale()) setSearchLoading(false);
    }
  };

  const handleObjectSelect = (object: CMISObject) => {
    setSelectedObject(object);
  };

  const handleConfirm = () => {
    if (selectedObject) {
      onSelect(selectedObject);
      setSelectedObject(null);
    }
  };

  const objectColumns = [
    {
      title: t('objectPicker.columns.name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: CMISObject) => (
        <Space>
          {record.baseType === 'cmis:folder' ? <FolderOutlined /> : <FileOutlined />}
          {name}
        </Space>
      ),
    },
    {
      title: t('objectPicker.columns.type'),
      dataIndex: 'objectType',
      key: 'objectType',
    },
    {
      title: t('objectPicker.columns.updatedAt'),
      dataIndex: 'lastModificationDate',
      key: 'lastModificationDate',
      render: (date: string) => formatServerDate(date),
    },
  ];

  const tabItems = [
    {
      key: 'tree',
      label: t('objectPicker.tabs.tree'),
      children: (
        <div style={{ display: 'flex', height: '400px' }}>
          <div style={{ width: '250px', borderRight: '1px solid #d9d9d9', overflow: 'auto' }}>
            {treeLoading ? (
              <div style={{ textAlign: 'center', padding: '20px' }}>
                <Spin />
              </div>
            ) : (
              <Tree
                treeData={treeData}
                loadData={onLoadData}
                onSelect={handleTreeSelect}
                onExpand={(keys) => setExpandedKeys(keys as string[])}
                expandedKeys={expandedKeys}
                showIcon
                style={{ padding: '8px' }}
              />
            )}
          </div>
          <div style={{ flex: 1, overflow: 'auto', padding: '8px' }}>
            <Table
              columns={objectColumns}
              dataSource={currentFolderChildren}
              rowKey="id"
              size="small"
              pagination={false}
              onRow={(record) => ({
                onClick: () => handleObjectSelect(record),
                style: {
                  cursor: 'pointer',
                  backgroundColor: selectedObject?.id === record.id ? '#e6f7ff' : undefined
                }
              })}
              locale={{ emptyText: t('objectPicker.noObjects') }}
            />
          </div>
        </div>
      ),
    },
    {
      key: 'search',
      label: t('objectPicker.tabs.search'),
      children: (
        <div style={{ height: '400px', display: 'flex', flexDirection: 'column' }}>
          <div style={{ marginBottom: '16px' }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                placeholder={t('objectPicker.searchPlaceholder')}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onPressEnter={handleSearch}
                prefix={<SearchOutlined />}
              />
              <Button type="primary" onClick={handleSearch} loading={searchLoading}>
                {t('common.search')}
              </Button>
            </Space.Compact>
          </div>
          <div style={{ flex: 1, overflow: 'auto' }}>
            {searchCutShort ? (
              <Alert type="warning" showIcon title={t('searchResults.messages.incompleteResults')} />
            ) : (
            <Table
              columns={objectColumns}
              dataSource={searchResults}
              rowKey="id"
              size="small"
              pagination={{ pageSize: 10 }}
              loading={searchLoading}
              onRow={(record) => ({
                onClick: () => handleObjectSelect(record),
                style: {
                  cursor: 'pointer',
                  backgroundColor: selectedObject?.id === record.id ? '#e6f7ff' : undefined
                }
              })}
              locale={{ emptyText: searchQuery ? t('objectPicker.noSearchResults') : t('objectPicker.enterSearchKeyword') }}
            />
            )}
          </div>
        </div>
      ),
    },
  ];

  return (
    <Modal
      title={title || defaultTitle}
      open={visible}
      onCancel={onCancel}
      width={800}
      footer={
        <Space>
          <Button onClick={onCancel}>{t('common.cancel')}</Button>
          <Button
            type="primary"
            onClick={handleConfirm}
            disabled={!selectedObject}
          >
            {t('common.select')} {selectedObject && `(${selectedObject.name})`}
          </Button>
        </Space>
      }
    >
      {selectedObject && (
        <div style={{ marginBottom: '16px', padding: '8px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}>
          <strong>{t('objectPicker.selectedObject')}: </strong>
          {selectedObject.baseType === 'cmis:folder' ? <FolderOutlined /> : <FileOutlined />}
          {' '}{selectedObject.name} {t('common.idWithValue', { id: selectedObject.id })}
        </div>
      )}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />
    </Modal>
  );
};
