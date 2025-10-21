import React, { useState, useEffect } from 'react';
import { Tree, Spin, message } from 'antd';
import { FolderOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';

interface FolderTreeProps {
  repositoryId: string;
  onSelect: (folderId: string, folderPath: string) => void;
  selectedFolderId?: string;
}

interface TreeNode {
  key: string;
  title: string;
  icon: React.ReactNode;
  isLeaf?: boolean;
  children?: TreeNode[];
}

import { useAuth } from '../../contexts/AuthContext';
export const FolderTree: React.FC<FolderTreeProps> = ({
  repositoryId,
  onSelect,
  selectedFolderId
}) => {
  const [treeData, setTreeData] = useState<TreeNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  useEffect(() => {
    loadRootFolder();
  }, [repositoryId]);

  useEffect(() => {
    if (selectedFolderId) {
      setSelectedKeys([selectedFolderId]);
    }
  }, [selectedFolderId]);

  const loadRootFolder = async () => {
    try {
      setLoading(true);
      const rootFolder = await cmisService.getRootFolder(repositoryId);
      const rootNode: TreeNode = {
        key: rootFolder.id,
        title: rootFolder.name || 'Root',
        icon: <FolderOutlined />,
        isLeaf: false,
      };
      
      setTreeData([rootNode]);
      setExpandedKeys([rootFolder.id]);
      setSelectedKeys([rootFolder.id]);
      onSelect(rootFolder.id, rootFolder.path || '/');
    } catch (error) {
      message.error('ルートフォルダの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
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

  const handleSelect = async (selectedKeys: React.Key[]) => {
    if (selectedKeys.length > 0) {
      const folderId = selectedKeys[0] as string;
      setSelectedKeys([folderId]);
      
      try {
        const folder = await cmisService.getObject(repositoryId, folderId);
        onSelect(folderId, folder.path || folder.name);
      } catch (error) {
        message.error('フォルダ情報の取得に失敗しました');
      }
    }
  };

  const handleExpand = (expandedKeys: React.Key[]) => {
    setExpandedKeys(expandedKeys as string[]);
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '20px' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <Tree
      treeData={treeData}
      loadData={onLoadData}
      onSelect={handleSelect}
      onExpand={handleExpand}
      selectedKeys={selectedKeys}
      expandedKeys={expandedKeys}
      showIcon
      style={{ background: '#fafafa', padding: '8px', borderRadius: '4px' }}
    />
  );
};
