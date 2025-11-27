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
import { Modal, Tabs, Tree, Input, Table, Button, Space, Spin, message } from 'antd';
import { FolderOutlined, FileOutlined, SearchOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';

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
  title = 'オブジェクトを選択',
  filterType = 'all'
}) => {
  const [activeTab, setActiveTab] = useState<string>('tree');
  const [treeData, setTreeData] = useState<TreeNode[]>([]);
  const [treeLoading, setTreeLoading] = useState(true);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selectedObject, setSelectedObject] = useState<CMISObject | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<CMISObject[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [currentFolderChildren, setCurrentFolderChildren] = useState<CMISObject[]>([]);

  const cmisService = new CMISService();

  useEffect(() => {
    if (visible) {
      loadRootFolder();
      setSelectedObject(null);
      setSearchQuery('');
      setSearchResults([]);
    }
  }, [visible, repositoryId]);

  const loadRootFolder = async () => {
    try {
      setTreeLoading(true);
      const rootFolder = await cmisService.getRootFolder(repositoryId);
      const rootNode: TreeNode = {
        key: rootFolder.id,
        title: rootFolder.name || 'Root',
        icon: <FolderOutlined />,
        isLeaf: false,
        data: rootFolder,
      };

      setTreeData([rootNode]);
      setExpandedKeys([rootFolder.id]);

      const children = await cmisService.getChildren(repositoryId, rootFolder.id);
      setCurrentFolderChildren(filterChildren(children));
    } catch (error) {
      message.error('ルートフォルダの読み込みに失敗しました');
    } finally {
      setTreeLoading(false);
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
      message.error('フォルダの読み込みに失敗しました');
      return [];
    }
  };

  const onLoadData = async ({ key }: { key: string }): Promise<void> => {
    const children = await loadChildren(key);

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
      try {
        const children = await cmisService.getChildren(repositoryId, folderId);
        setCurrentFolderChildren(filterChildren(children));
      } catch (error) {
        message.error('フォルダ内容の取得に失敗しました');
      }
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      message.warning('検索キーワードを入力してください');
      return;
    }

    try {
      setSearchLoading(true);
      const result = await cmisService.search(repositoryId, searchQuery);
      const filtered = filterType === 'all'
        ? result.objects
        : result.objects.filter(obj =>
            filterType === 'folder' ? obj.baseType === 'cmis:folder' : obj.baseType === 'cmis:document'
          );
      setSearchResults(filtered);
    } catch (error) {
      message.error('検索に失敗しました');
    } finally {
      setSearchLoading(false);
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
      title: '名前',
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
      title: 'タイプ',
      dataIndex: 'objectType',
      key: 'objectType',
    },
    {
      title: '更新日時',
      dataIndex: 'lastModificationDate',
      key: 'lastModificationDate',
      render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
    },
  ];

  const tabItems = [
    {
      key: 'tree',
      label: 'ツリーから選択',
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
              locale={{ emptyText: 'オブジェクトがありません' }}
            />
          </div>
        </div>
      ),
    },
    {
      key: 'search',
      label: '検索',
      children: (
        <div style={{ height: '400px', display: 'flex', flexDirection: 'column' }}>
          <div style={{ marginBottom: '16px' }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                placeholder="検索キーワードを入力"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onPressEnter={handleSearch}
                prefix={<SearchOutlined />}
              />
              <Button type="primary" onClick={handleSearch} loading={searchLoading}>
                検索
              </Button>
            </Space.Compact>
          </div>
          <div style={{ flex: 1, overflow: 'auto' }}>
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
              locale={{ emptyText: searchQuery ? '検索結果がありません' : '検索キーワードを入力してください' }}
            />
          </div>
        </div>
      ),
    },
  ];

  return (
    <Modal
      title={title}
      open={visible}
      onCancel={onCancel}
      width={800}
      footer={
        <Space>
          <Button onClick={onCancel}>キャンセル</Button>
          <Button
            type="primary"
            onClick={handleConfirm}
            disabled={!selectedObject}
          >
            選択 {selectedObject && `(${selectedObject.name})`}
          </Button>
        </Space>
      }
    >
      {selectedObject && (
        <div style={{ marginBottom: '16px', padding: '8px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}>
          <strong>選択中: </strong>
          {selectedObject.baseType === 'cmis:folder' ? <FolderOutlined /> : <FileOutlined />}
          {' '}{selectedObject.name} (ID: {selectedObject.id})
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
