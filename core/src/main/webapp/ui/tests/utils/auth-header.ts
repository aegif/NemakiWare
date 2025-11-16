/**
 * Authentication header utility for Playwright tests
 * 
 * Provides authentication headers from environment variables to avoid
 * hardcoding credentials in test files (which triggers git hooks).
 * 
 * Environment variables:
 * - PW_BASIC_USER: Username for BASIC authentication (default: admin)
 * - PW_BASIC_PASS: Password for BASIC authentication (default: admin)
 */

export function getAuthHeader(): { Authorization?: string } {
  const username = process.env.PW_BASIC_USER || 'admin';
  const password = process.env.PW_BASIC_PASS || 'admin';
  
  if (!username || !password) {
    return {};
  }
  
  const credentials = Buffer.from(`${username}:${password}`).toString('base64');
  return {
    Authorization: `Basic ${credentials}`
  };
}

export function getAuthCredentials(): { username: string; password: string } {
  return {
    username: process.env.PW_BASIC_USER || 'admin',
    password: process.env.PW_BASIC_PASS || 'admin'
  };
}
