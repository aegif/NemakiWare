import { AuthService } from './auth';

export interface PasswordPolicy {
  minLength: number;
}

const getBaseUrl = (repositoryId: string) =>
  `/core/api/v1/cmis/repositories/${repositoryId}/config/password-policy`;

export async function getPasswordPolicy(repositoryId: string): Promise<PasswordPolicy> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();

  const response = await fetch(getBaseUrl(repositoryId), {
    method: 'GET',
    headers: {
      'Accept': 'application/json',
      ...headers
    }
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch password policy: ${response.status}`);
  }

  return response.json();
}

export async function updatePasswordPolicy(
  repositoryId: string,
  policy: PasswordPolicy
): Promise<PasswordPolicy> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();

  const response = await fetch(getBaseUrl(repositoryId), {
    method: 'PUT',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      ...headers
    },
    body: JSON.stringify(policy)
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.detail || `Failed to update password policy: ${response.status}`);
  }

  return response.json();
}
