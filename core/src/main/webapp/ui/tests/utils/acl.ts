import { Page } from '@playwright/test';

/**
 * Get ACL inheritance status via REST API
 * 
 * This function uses the REST API endpoint to check if an object's ACL is inherited
 * from its parent or if inheritance has been broken.
 * 
 * @param page Playwright page object
 * @param repositoryId Repository ID (e.g., 'bedroom')
 * @param objectId Object ID
 * @returns ACL inheritance status (true = inherited, false = broken)
 * 
 * @example
 * ```typescript
 * const aclInherited = await getAclInheritedViaRest(page, 'bedroom', folderId);
 * expect(aclInherited).toBe(false); // Inheritance is broken
 * ```
 */
export async function getAclInheritedViaRest(
  page: Page,
  repositoryId: string,
  objectId: string
): Promise<boolean> {
  const aclInherited = await page.evaluate(
    async ({ repoId, objId }) => {
      const res = await fetch(`/core/rest/repo/${repoId}/node/${objId}/acl`, {
        credentials: 'include'
      });
      
      if (!res.ok) {
        throw new Error(`Failed to fetch ACL: ${res.status} ${res.statusText}`);
      }
      
      const json = await res.json();
      return Boolean(json?.acl?.aclInherited);
    },
    { repoId: repositoryId, objId: objectId }
  );
  
  return aclInherited;
}
