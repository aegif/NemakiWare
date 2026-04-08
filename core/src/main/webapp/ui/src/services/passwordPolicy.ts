import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

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

  return (await parseJsonResponseBody(response, 'getPasswordPolicy')) as unknown as PasswordPolicy;
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

  const data = await parseJsonResponseBody(response, 'updatePasswordPolicy');
  if (!response.ok) {
    throw new Error((data.detail as string) || `Failed to update password policy: ${response.status}`);
  }

  return data as unknown as PasswordPolicy;
}
