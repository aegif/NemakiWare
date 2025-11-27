/**
 * Folder Tree Configuration for NemakiWare React UI
 *
 * Controls the behavior of the folder tree navigation component.
 *
 * @property ancestorGenerations - Number of ancestor generations to display from current folder
 *   - -1: Display all ancestors up to ROOT folder
 *   - 0: Display only current folder and its children (no ancestors)
 *   - 1: Display parent and current folder with children
 *   - 2: Display grandparent, parent, current folder with children
 *   - etc.
 */

export interface FolderTreeConfig {
  /**
   * Number of ancestor generations to display in the tree view.
   * Set to -1 to show all ancestors up to root.
   * Default: 2 (grandparent, parent, current)
   */
  ancestorGenerations: number;

  /**
   * Whether to auto-expand ancestor nodes in the tree.
   * Default: true
   */
  autoExpandAncestors: boolean;

  /**
   * Whether to show folder icons in the tree.
   * Default: true
   */
  showFolderIcons: boolean;

  /**
   * Delay in milliseconds before treating click as single click.
   * Used to differentiate single click (select) from double click (navigate).
   * Default: 250
   */
  clickDelay: number;
}

export const defaultFolderTreeConfig: FolderTreeConfig = {
  ancestorGenerations: 2,
  autoExpandAncestors: true,
  showFolderIcons: true,
  clickDelay: 250,
};

/**
 * Get the folder tree configuration.
 * In future, this could read from server-side config or user preferences.
 */
export const getFolderTreeConfig = (): FolderTreeConfig => {
  return defaultFolderTreeConfig;
};

/**
 * Update folder tree configuration at runtime.
 * Changes will persist in memory until page refresh.
 */
let runtimeConfig: FolderTreeConfig = { ...defaultFolderTreeConfig };

export const setFolderTreeConfig = (config: Partial<FolderTreeConfig>): void => {
  runtimeConfig = { ...runtimeConfig, ...config };
};

export const getCurrentFolderTreeConfig = (): FolderTreeConfig => {
  return runtimeConfig;
};
