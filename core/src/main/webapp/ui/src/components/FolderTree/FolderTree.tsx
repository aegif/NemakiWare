/**
 * FolderTree Component for NemakiWare React UI - Ancestor-Aware Navigation Model
 *
 * Enhanced folder navigation component with configurable ancestor display:
 * - Shows ancestors up to N generations based on config (or all up to ROOT if -1)
 * - Current folder is the "pivot" point for tree construction
 * - Selected folder (highlighted in tree) can differ from current folder
 * - Single click: Select folder (shows its contents in main pane)
 * - Double click on selected folder: Makes it the current folder (redraws tree)
 * - Main pane subfolder clicks: Updates selected folder
 * - Supports drill-down into descendants within tree view
 *
 * Key Concepts:
 * - Current Folder: The pivot point for tree construction. Ancestors are loaded relative to this.
 * - Selected Folder: The folder whose contents are displayed in main pane. Highlighted in tree.
 * - When selected folder is clicked again, it becomes the current folder and tree redraws.
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
 * 1. Click child folder → Child becomes selected → Main pane shows child's contents
 * 2. Click selected folder again → Selected folder becomes current → Tree redraws around it
 * 3. Click ancestor folder → Ancestor becomes selected → Main pane shows ancestor's contents
 * 4. Click selected ancestor again → Ancestor becomes current → Tree redraws around it
 *
 * Props:
 * - repositoryId: CMIS repository identifier
 * - onSelect: Callback when a folder is selected (for main pane content update)
 * - onCurrentFolderChange: Callback when current folder changes (optional, for tree redraw coordination)
 * - selectedFolderId: Externally controlled selected folder (from main pane clicks)
 * - currentFolderId: Externally controlled current folder (optional, for initial state)
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
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

  // Refs for click handling
  const clickTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastClickedIdRef = useRef<string>('');

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
   */
  const buildTreeStructure = (
    ancestors: CMISObject[],
    currentFolder: CMISObject,
    children: CMISObject[]
  ): TreeDataNode[] => {
    // Build current folder node with children
    const currentNode: TreeDataNode = {
      key: currentFolder.id,
      title: (
        <span style={{ fontWeight: currentFolder.id === currentFolderId ? 'bold' : 'normal' }}>
          {currentFolder.name || 'Repository Root'}
        </span>
      ),
      icon: <FolderOpenOutlined style={{ color: '#1890ff' }} />,
      children: children.map(child => ({
        key: child.id,
        title: child.name,
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
        title: ancestor.name,
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
   * Handle folder click - single click selects, double click makes current
   */
  const handleFolderClick = useCallback(async (folderId: string) => {
    // Clear any pending timer
    if (clickTimerRef.current) {
      clearTimeout(clickTimerRef.current);
      clickTimerRef.current = null;
    }

    // Check if this is a double click (same folder clicked twice quickly)
    if (lastClickedIdRef.current === folderId && selectedFolderId === folderId) {
      // Double click detected - make this folder the current folder
      setCurrentFolderId(folderId);
      if (onCurrentFolderChange) {
        onCurrentFolderChange(folderId);
      }
      await loadTreeFromFolder(folderId);
      lastClickedIdRef.current = '';
      return;
    }

    // First click - start timer for single click
    lastClickedIdRef.current = folderId;
    clickTimerRef.current = setTimeout(async () => {
      // Single click - select this folder
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
      clickTimerRef.current = null;
    }, config.clickDelay);
  }, [selectedFolderId, folderCache, repositoryId, onSelect, onCurrentFolderChange, config.clickDelay]);

  /**
   * Handle tree node selection
   */
  const handleSelect: TreeProps['onSelect'] = (selectedKeys, _info) => {
    if (selectedKeys.length > 0) {
      const folderId = selectedKeys[0] as string;
      handleFolderClick(folderId);
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
        const updateTreeData = (nodes: TreeDataNode[]): TreeDataNode[] => {
          return nodes.map(n => {
            if (n.key === folderId) {
              return {
                ...n,
                children: children.map(child => ({
                  key: child.id,
                  title: child.name,
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
        シングルクリック: フォルダを選択 | ダブルクリック: ツリーを再描画
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
        titleRender={(nodeData) => (
          <span
            style={{
              padding: '2px 8px',
              borderRadius: '4px',
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
            {nodeData.title as React.ReactNode}
          </span>
        )}
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
