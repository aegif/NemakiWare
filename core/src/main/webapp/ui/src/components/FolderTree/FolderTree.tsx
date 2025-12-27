/**
 * FolderTree Component for NemakiWare React UI - Ancestor-Aware Navigation Model
 *
 * Enhanced folder navigation component with configurable ancestor display:
 * - Shows ancestors up to N generations based on config (or all up to ROOT if -1)
 * - Current folder is the "pivot" point for tree construction
 * - Selected folder (highlighted in tree) can differ from current folder
 * - Click behavior (simple, no double-click needed):
 *   - Click non-selected folder: Select it → Main pane shows its contents
 *   - Click already-selected folder: Make it current → Tree redraws around it
 * - Main pane subfolder clicks: Updates selected folder
 * - Supports drill-down into descendants within tree view
 *
 * Key Concepts:
 * - Current Folder: The pivot point for tree construction. Ancestors are loaded relative to this.
 * - Selected Folder: The folder whose contents are displayed in main pane. Highlighted in tree.
 * - Clicking an already-selected folder promotes it to current folder and redraws tree.
 *
 * Tree Structure (with ancestorGenerations=2):
 * [
 *   GrandparentFolder (expandable)
 *     └─ ParentFolder (expandable)
 *         └─ CurrentFolder (highlighted as current, children visible)
 *             ├─ ChildFolder1 (expandable/clickable)
 *             └─ ChildFolder2 (expandable/clickable)
 * ]
 *
 * Navigation Flows:
 * 1. Click non-selected folder → Folder becomes selected → Main pane shows folder's contents
 * 2. Click already-selected folder → Selected folder becomes current → Tree redraws around it
 *
 * Props:
 * - repositoryId: CMIS repository identifier
 * - onSelect: Callback when a folder is selected (for main pane content update)
 * - onCurrentFolderChange: Callback when current folder changes (optional, for tree redraw coordination)
 * - selectedFolderId: Externally controlled selected folder (from main pane clicks)
 * - currentFolderId: Externally controlled current folder (optional, for initial state)
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Tree, Spin, message } from 'antd';
import type { TreeDataNode, TreeProps } from 'antd';
import { FolderOutlined, FolderOpenOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { getCurrentFolderTreeConfig } from '../../config/folderTree';

interface FolderTreeProps {
  repositoryId: string;
  onSelect: (folderId: string, folderPath: string) => void;
  onCurrentFolderChange?: (folderId: string) => void;
  selectedFolderId?: string;
  currentFolderId?: string;
}

/*
 * Future use: Represents a folder node with hierarchical structure
 * interface FolderNode {
 *   id: string;
 *   name: string;
 *   path: string;
 *   parentId?: string;
 *   children?: FolderNode[];
 *   isLoaded?: boolean;
 * }
 */

export const FolderTree: React.FC<FolderTreeProps> = ({
  repositoryId,
  onSelect,
  onCurrentFolderChange,
  selectedFolderId: externalSelectedFolderId,
  currentFolderId: externalCurrentFolderId
}) => {
  // State
  const [treeData, setTreeData] = useState<TreeDataNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [currentFolderId, setCurrentFolderId] = useState<string>('');
  const [selectedFolderId, setSelectedFolderId] = useState<string>('');
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [folderCache, setFolderCache] = useState<Map<string, CMISObject>>(new Map());

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);
  const config = getCurrentFolderTreeConfig();

  // Sync with external selected folder
  useEffect(() => {
    if (externalSelectedFolderId && externalSelectedFolderId !== selectedFolderId) {
      setSelectedFolderId(externalSelectedFolderId);
    }
  }, [externalSelectedFolderId]);

  // Sync with external current folder
  useEffect(() => {
    if (externalCurrentFolderId && externalCurrentFolderId !== currentFolderId) {
      setCurrentFolderId(externalCurrentFolderId);
      loadTreeFromFolder(externalCurrentFolderId);
    }
  }, [externalCurrentFolderId]);

  // Initial load - get root folder and build tree
  useEffect(() => {
    loadRootFolder();
  }, [repositoryId]);

  /**
   * Load root folder and initialize tree
   */
  const loadRootFolder = async () => {
    const authData = localStorage.getItem('nemakiware_auth');
    if (!authData) {
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      const rootFolder = await cmisService.getRootFolder(repositoryId);

      // Cache the root folder
      const newCache = new Map(folderCache);
      newCache.set(rootFolder.id, rootFolder);
      setFolderCache(newCache);

      setCurrentFolderId(rootFolder.id);
      setSelectedFolderId(rootFolder.id);

      await loadTreeFromFolder(rootFolder.id);
      onSelect(rootFolder.id, rootFolder.path || '/');
    } catch (error) {
      console.error('Failed to load root folder:', error);
      message.error('ルートフォルダの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Build tree data starting from a folder (current folder becomes the pivot)
   */
  const loadTreeFromFolder = async (folderId: string) => {
    try {
      setLoading(true);

      // Get the folder object
      let folder = folderCache.get(folderId);
      if (!folder) {
        folder = await cmisService.getObject(repositoryId, folderId);
        const newCache = new Map(folderCache);
        newCache.set(folderId, folder);
        setFolderCache(newCache);
      }

      // Build ancestor chain
      const ancestors = await buildAncestorChain(folder, config.ancestorGenerations);

      // Get children of current folder
      const children = await loadFolderChildren(folderId);

      // Build tree structure
      const tree = buildTreeStructure(ancestors, folder, children);
      setTreeData(tree);

      // Expand all ancestors and current folder
      const keysToExpand = [...ancestors.map(a => a.id), folderId];
      setExpandedKeys(keysToExpand);

    } catch (error) {
      console.error('Failed to load tree from folder:', error);
      message.error('フォルダツリーの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Build ancestor chain up to specified generations or root
   */
  const buildAncestorChain = async (
    folder: CMISObject,
    generations: number
  ): Promise<CMISObject[]> => {
    const ancestors: CMISObject[] = [];
    let currentFolder = folder;
    let gen = 0;

    while (currentFolder.parentId && (generations === -1 || gen < generations)) {
      try {
        let parent = folderCache.get(currentFolder.parentId);
        if (!parent) {
          parent = await cmisService.getObject(repositoryId, currentFolder.parentId);
          const newCache = new Map(folderCache);
          newCache.set(parent.id, parent);
          setFolderCache(newCache);
        }
        ancestors.unshift(parent); // Add to front to maintain order (oldest ancestor first)
        currentFolder = parent;
        gen++;
      } catch (error) {
        console.error('Failed to load ancestor:', error);
        break;
      }
    }

    return ancestors;
  };

  /**
   * Load children folders of a given folder
   */
  const loadFolderChildren = async (folderId: string): Promise<CMISObject[]> => {
    try {
      const children = await cmisService.getChildren(repositoryId, folderId);
      const folders = children.filter(child => child.baseType === 'cmis:folder');

      // Cache children
      const newCache = new Map(folderCache);
      folders.forEach(f => newCache.set(f.id, f));
      setFolderCache(newCache);

      return folders;
    } catch (error) {
      console.error('Failed to load children:', error);
      return [];
    }
  };

  /**
   * Build tree structure from ancestors, current folder, and children
   *
   * CRITICAL FIX (2025-12-21): Use plain strings for title, not JSX elements
   * All styling is handled by titleRender to prevent React DOM reconciliation errors
   * ("Failed to execute 'insertBefore' on 'Node'" error)
   */
  const buildTreeStructure = (
    ancestors: CMISObject[],
    currentFolder: CMISObject,
    children: CMISObject[]
  ): TreeDataNode[] => {
    // Build current folder node with children
    // Use plain string for title - styling handled by titleRender
    const currentNode: TreeDataNode = {
      key: currentFolder.id,
      title: currentFolder.name || 'Repository Root',
      icon: <FolderOpenOutlined style={{ color: '#1890ff' }} />,
      children: children.map(child => ({
        key: child.id,
        title: child.name || '',
        icon: <FolderOutlined style={{ color: '#faad14' }} />,
        isLeaf: false, // Allow expansion for drill-down
      })),
    };

    // If no ancestors, return just current folder
    if (ancestors.length === 0) {
      return [currentNode];
    }

    // Build nested structure from oldest ancestor down
    let rootNode: TreeDataNode | null = null;
    let parentNode: TreeDataNode | null = null;

    for (const ancestor of ancestors) {
      const node: TreeDataNode = {
        key: ancestor.id,
        title: ancestor.name || '', // Plain string for title
        icon: <FolderOutlined style={{ color: '#faad14' }} />,
        children: [],
      };

      if (!rootNode) {
        rootNode = node;
      }

      if (parentNode && parentNode.children) {
        parentNode.children.push(node);
      }

      parentNode = node;
    }

    // Attach current folder node to last ancestor
    if (parentNode && parentNode.children) {
      parentNode.children.push(currentNode);
    }

    return rootNode ? [rootNode] : [currentNode];
  };

  /**
   * Handle folder click:
   * - Click non-selected folder: Select it and show its contents in main pane
   * - Click already-selected folder: Make it current folder and redraw tree around it
   */
  const handleFolderClick = useCallback(async (folderId: string) => {
    // If clicking already-selected folder, make it the current folder
    if (folderId === selectedFolderId) {
      setCurrentFolderId(folderId);
      if (onCurrentFolderChange) {
        onCurrentFolderChange(folderId);
      }
      await loadTreeFromFolder(folderId);
      return;
    }

    // Clicking non-selected folder: select it and show its contents
    setSelectedFolderId(folderId);

    // Get folder path for callback
    let folder = folderCache.get(folderId);
    if (!folder) {
      try {
        folder = await cmisService.getObject(repositoryId, folderId);
        const newCache = new Map(folderCache);
        newCache.set(folderId, folder);
        setFolderCache(newCache);
      } catch (error) {
        console.error('Failed to get folder:', error);
        return;
      }
    }

    onSelect(folderId, folder.path || folder.name);
  }, [selectedFolderId, folderCache, repositoryId, onSelect, onCurrentFolderChange]);

  /**
   * Handle tree node selection
   * CRITICAL FIX (2025-12-02): Use info.node.key instead of selectedKeys
   *
   * Ant Design Tree behavior:
   * - When clicking an already-selected node, selectedKeys becomes [] (empty)
   * - This caused the previous code to do nothing when clicking already-selected folder
   * - We need to use info.node.key to always get the clicked folder ID
   *
   * Expected behavior:
   * - Click non-selected folder → folder becomes selected (current unchanged)
   * - Click already-selected folder → folder becomes current, tree redraws
   */
  const handleSelect: TreeProps['onSelect'] = (_selectedKeys, info) => {
    // Always use info.node.key to get the clicked folder ID
    // selectedKeys can be empty when clicking already-selected node (Ant Design deselects it)
    const clickedFolderId = info.node.key as string;
    if (clickedFolderId) {
      handleFolderClick(clickedFolderId);
    }
  };

  /**
   * Handle tree expansion - load children on demand
   */
  const handleExpand: TreeProps['onExpand'] = async (keys, { node, expanded }) => {
    setExpandedKeys(keys);

    // If expanding and node doesn't have loaded children, load them
    if (expanded && node && !node.children?.length) {
      const folderId = node.key as string;
      const children = await loadFolderChildren(folderId);

      if (children.length > 0) {
        // Update tree data with new children
        // Use plain strings for title to prevent React DOM reconciliation errors
        const updateTreeData = (nodes: TreeDataNode[]): TreeDataNode[] => {
          return nodes.map(n => {
            if (n.key === folderId) {
              return {
                ...n,
                children: children.map(child => ({
                  key: child.id,
                  title: child.name || '', // Plain string for title
                  icon: <FolderOutlined style={{ color: '#faad14' }} />,
                  isLeaf: false,
                })),
              };
            }
            if (n.children) {
              return {
                ...n,
                children: updateTreeData(n.children as TreeDataNode[]),
              };
            }
            return n;
          });
        };

        setTreeData(prev => updateTreeData(prev));
      }
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '20px' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div style={{ background: '#fafafa', padding: '8px', borderRadius: '4px' }}>
      <div style={{ marginBottom: '8px', fontSize: '12px', color: '#666' }}>
        クリック: フォルダを選択 | 選択中のフォルダをクリック: カレントに設定
      </div>
      <Tree
        treeData={treeData}
        selectedKeys={[selectedFolderId]}
        expandedKeys={expandedKeys}
        onSelect={handleSelect}
        onExpand={handleExpand}
        showIcon
        blockNode
        style={{ background: 'transparent' }}
        titleRender={(nodeData) => {
          // CRITICAL FIX (2025-12-21): Safe title rendering
          // Always treat title as string to prevent React DOM reconciliation errors
          const titleText = typeof nodeData.title === 'string'
            ? nodeData.title
            : String(nodeData.title || '');

          return (
            <span
              style={{
                padding: '2px 8px',
                borderRadius: '4px',
                fontWeight: nodeData.key === currentFolderId ? 'bold' : 'normal',
                backgroundColor:
                  nodeData.key === selectedFolderId
                    ? '#e6f7ff'
                    : nodeData.key === currentFolderId
                    ? '#f0f0f0'
                    : 'transparent',
                border:
                  nodeData.key === currentFolderId
                    ? '1px solid #1890ff'
                    : nodeData.key === selectedFolderId
                    ? '1px solid #91d5ff'
                    : '1px solid transparent',
                display: 'inline-block',
              }}
            >
              {titleText}
            </span>
          );
        }}
      />
      <div style={{ marginTop: '8px', fontSize: '11px', color: '#999' }}>
        <span style={{
          display: 'inline-block',
          width: '12px',
          height: '12px',
          border: '1px solid #1890ff',
          borderRadius: '2px',
          marginRight: '4px',
          backgroundColor: '#f0f0f0'
        }} />
        カレントフォルダ
        <span style={{
          display: 'inline-block',
          width: '12px',
          height: '12px',
          border: '1px solid #91d5ff',
          borderRadius: '2px',
          marginLeft: '12px',
          marginRight: '4px',
          backgroundColor: '#e6f7ff'
        }} />
        選択中
      </div>
    </div>
  );
};
