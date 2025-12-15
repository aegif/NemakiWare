/**
 * Application Configuration
 *
 * Centralized configuration for NemakiWare UI application settings.
 * Environment variables can be set via Vite's import.meta.env mechanism.
 *
 * Environment Variables (set in .env files):
 * - VITE_DEFAULT_REPOSITORY_ID: Default repository ID fallback (default: 'bedroom')
 *
 * Usage:
 * ```typescript
 * import { appConfig, DEFAULT_REPOSITORY_ID } from '@/config/app';
 *
 * // Get default repository when discovery fails
 * const repo = appConfig.defaultRepositoryId;
 * // Or use the exported constant directly
 * const repo = DEFAULT_REPOSITORY_ID;
 * ```
 */

/**
 * Default repository ID used when:
 * - Repository discovery fails (network error, server unavailable)
 * - SAML/OIDC callback lacks repository context (RelayState missing)
 * - User hasn't selected a repository yet
 *
 * Can be overridden via VITE_DEFAULT_REPOSITORY_ID environment variable.
 */
export const DEFAULT_REPOSITORY_ID = import.meta.env.VITE_DEFAULT_REPOSITORY_ID || 'bedroom';

/**
 * Application configuration object
 */
export const appConfig = {
  /**
   * Default repository ID fallback
   * Used when repository cannot be determined from context
   */
  defaultRepositoryId: DEFAULT_REPOSITORY_ID,

  /**
   * Whether the application is running in development mode
   */
  isDevelopment: import.meta.env.DEV,

  /**
   * Whether the application is running in production mode
   */
  isProduction: import.meta.env.PROD,
} as const;

export default appConfig;
