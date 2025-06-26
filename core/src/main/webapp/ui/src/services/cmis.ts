import axios from 'axios';
import { AuthService } from './auth';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, User, Group, ACL } from '../types/cmis';

export class CMISService {
  private baseUrl = '/core/rest/repo';
  private authService = AuthService.getInstance();

  private getAuthHeaders() {
    return this.authService.getAuthHeaders();
  }

  async getRepositories(): Promise<string[]> {
    const response = await axios.get('/core/rest/repositories');
    return response.data.repositories || [];
  }

  async getRootFolder(repositoryId: string): Promise<CMISObject> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/root`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.object;
  }

  async getChildren(repositoryId: string, folderId: string): Promise<CMISObject[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/${folderId}/children`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.children || [];
  }

  async getObject(repositoryId: string, objectId: string): Promise<CMISObject> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/${objectId}`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.object;
  }

  async createDocument(repositoryId: string, parentId: string, file: File, properties: Record<string, any>): Promise<CMISObject> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('parentId', parentId);
    Object.entries(properties).forEach(([key, value]) => {
      formData.append(key, value);
    });

    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/node/create`,
      formData,
      { 
        headers: { 
          ...this.getAuthHeaders(),
          'Content-Type': 'multipart/form-data'
        }
      }
    );
    return response.data.object;
  }

  async createFolder(repositoryId: string, parentId: string, name: string, properties: Record<string, any> = {}): Promise<CMISObject> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/folder/create`,
      {
        parentId,
        name,
        ...properties
      },
      { headers: this.getAuthHeaders() }
    );
    return response.data.object;
  }

  async updateProperties(repositoryId: string, objectId: string, properties: Record<string, any>): Promise<CMISObject> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/update`,
      properties,
      { headers: this.getAuthHeaders() }
    );
    return response.data.object;
  }

  async deleteObject(repositoryId: string, objectId: string): Promise<void> {
    await axios.delete(
      `${this.baseUrl}/${repositoryId}/node/${objectId}`,
      { headers: this.getAuthHeaders() }
    );
  }

  async search(repositoryId: string, query: string): Promise<SearchResult> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/search`,
      { 
        params: { query },
        headers: this.getAuthHeaders()
      }
    );
    return {
      objects: response.data.results || [],
      hasMoreItems: response.data.hasMoreItems || false,
      numItems: response.data.numItems || 0
    };
  }

  async getVersionHistory(repositoryId: string, objectId: string): Promise<VersionHistory> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/versions`,
      { headers: this.getAuthHeaders() }
    );
    return response.data;
  }

  async checkOut(repositoryId: string, objectId: string): Promise<CMISObject> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/checkout`,
      {},
      { headers: this.getAuthHeaders() }
    );
    return response.data.object;
  }

  async checkIn(repositoryId: string, objectId: string, file?: File, properties?: Record<string, any>): Promise<CMISObject> {
    const formData = new FormData();
    if (file) {
      formData.append('file', file);
    }
    if (properties) {
      Object.entries(properties).forEach(([key, value]) => {
        formData.append(key, value);
      });
    }

    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/checkin`,
      formData,
      { 
        headers: { 
          ...this.getAuthHeaders(),
          'Content-Type': 'multipart/form-data'
        }
      }
    );
    return response.data.object;
  }

  async cancelCheckOut(repositoryId: string, objectId: string): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/cancelcheckout`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  async getACL(repositoryId: string, objectId: string): Promise<ACL> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/acl`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.acl;
  }

  async setACL(repositoryId: string, objectId: string, acl: ACL): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/acl`,
      acl,
      { headers: this.getAuthHeaders() }
    );
  }

  async getUsers(repositoryId: string): Promise<User[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/user/list`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.users || [];
  }

  async createUser(repositoryId: string, user: Partial<User>): Promise<User> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/user/create/${user.id}`,
      user,
      { headers: this.getAuthHeaders() }
    );
    return response.data.user;
  }

  async updateUser(repositoryId: string, userId: string, user: Partial<User>): Promise<User> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/user/update/${userId}`,
      user,
      { headers: this.getAuthHeaders() }
    );
    return response.data.user;
  }

  async deleteUser(repositoryId: string, userId: string): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/user/delete/${userId}`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  async getGroups(repositoryId: string): Promise<Group[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/group/list`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.groups || [];
  }

  async createGroup(repositoryId: string, group: Partial<Group>): Promise<Group> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/group/create/${group.id}`,
      group,
      { headers: this.getAuthHeaders() }
    );
    return response.data.group;
  }

  async updateGroup(repositoryId: string, groupId: string, group: Partial<Group>): Promise<Group> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/group/update/${groupId}`,
      group,
      { headers: this.getAuthHeaders() }
    );
    return response.data.group;
  }

  async deleteGroup(repositoryId: string, groupId: string): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/group/delete/${groupId}`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  async getTypes(repositoryId: string): Promise<TypeDefinition[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/type/list`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.types || [];
  }

  async getType(repositoryId: string, typeId: string): Promise<TypeDefinition> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/type/${typeId}`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.type;
  }

  async createType(repositoryId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/type/create`,
      type,
      { headers: this.getAuthHeaders() }
    );
    return response.data.type;
  }

  async updateType(repositoryId: string, typeId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/type/${typeId}/update`,
      type,
      { headers: this.getAuthHeaders() }
    );
    return response.data.type;
  }

  async deleteType(repositoryId: string, typeId: string): Promise<void> {
    await axios.delete(
      `${this.baseUrl}/${repositoryId}/type/${typeId}`,
      { headers: this.getAuthHeaders() }
    );
  }

  async getRelationships(repositoryId: string, objectId: string): Promise<Relationship[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/node/${objectId}/relationships`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.relationships || [];
  }

  async createRelationship(repositoryId: string, relationship: Partial<Relationship>): Promise<Relationship> {
    const response = await axios.post(
      `${this.baseUrl}/${repositoryId}/relationship/create`,
      relationship,
      { headers: this.getAuthHeaders() }
    );
    return response.data.relationship;
  }

  async deleteRelationship(repositoryId: string, relationshipId: string): Promise<void> {
    await axios.delete(
      `${this.baseUrl}/${repositoryId}/relationship/${relationshipId}`,
      { headers: this.getAuthHeaders() }
    );
  }

  async initSearchEngine(repositoryId: string): Promise<void> {
    await axios.get(
      `${this.baseUrl}/${repositoryId}/search-engine/init`,
      { headers: this.getAuthHeaders() }
    );
  }

  async reindexSearchEngine(repositoryId: string): Promise<void> {
    await axios.get(
      `${this.baseUrl}/${repositoryId}/search-engine/reindex`,
      { headers: this.getAuthHeaders() }
    );
  }

  async getArchives(repositoryId: string): Promise<CMISObject[]> {
    const response = await axios.get(
      `${this.baseUrl}/${repositoryId}/archive/list`,
      { headers: this.getAuthHeaders() }
    );
    return response.data.archives || [];
  }

  async archiveObject(repositoryId: string, objectId: string): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/archive/${objectId}`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  async restoreObject(repositoryId: string, objectId: string): Promise<void> {
    await axios.post(
      `${this.baseUrl}/${repositoryId}/archive/${objectId}/restore`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  getDownloadUrl(repositoryId: string, objectId: string): string {
    const token = this.authService.getAuthToken();
    return `${this.baseUrl}/${repositoryId}/node/${objectId}/content?token=${token}`;
  }
}
