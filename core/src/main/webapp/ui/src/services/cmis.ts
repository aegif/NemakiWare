import { AuthService } from './auth';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, PropertyDefinition, User, Group, ACL } from '../types/cmis';

export class CMISService {
  private baseUrl = '/core/browser';
  private restBaseUrl = '/core/rest/repo';
  private authService: AuthService;
  private onAuthError?: (error: any) => void;

  constructor(onAuthError?: (error: any) => void) {
    this.authService = AuthService.getInstance();
    this.onAuthError = onAuthError;
  }

  setAuthErrorHandler(handler: (error: any) => void) {
    this.onAuthError = handler;
  }

  // Helper method to safely extract string values from CMIS properties
  // CMIS properties can be either strings, arrays, or objects with 'value' property (Browser Binding format)
  private getSafeStringProperty(props: Record<string, any>, key: string, defaultValue: string = ''): string {
    const property = props[key];
    
    // Handle Browser Binding format: {id: "cmis:name", value: "actual_value"}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'string') {
        return value;
      } else if (Array.isArray(value) && value.length > 0) {
        return String(value[0]);
      } else if (Array.isArray(value) && value.length === 0) {
        return defaultValue;
      }
      return String(value);
    }
    
    // Handle legacy format: direct string or array values
    if (typeof property === 'string') {
      return property;
    } else if (Array.isArray(property) && property.length > 0) {
      return String(property[0]); // Take first element if array
    } else if (Array.isArray(property) && property.length === 0) {
      return defaultValue; // Empty array returns default
    }
    
    return defaultValue;
  }

  private getSafeDateProperty(props: Record<string, any>, key: string): string {
    const property = props[key];
    
    // Handle Browser Binding format: {id: "cmis:creationDate", value: timestamp_or_date_string}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'number') {
        // Convert timestamp to ISO string
        return new Date(value).toISOString();
      } else if (typeof value === 'string') {
        return value;
      }
    }
    
    // Handle direct value
    if (typeof property === 'number') {
      return new Date(property).toISOString();
    } else if (typeof property === 'string') {
      return property;
    }
    
    return '';
  }

  private getSafeIntegerProperty(props: Record<string, any>, key: string): number | undefined {
    const property = props[key];

    // Handle Browser Binding format: {id: "cmis:contentStreamLength", value: number}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'number') {
        return value;
      } else if (typeof value === 'string') {
        const parsed = parseInt(value);
        return isNaN(parsed) ? undefined : parsed;
      }
    }
    
    // Handle direct value
    if (typeof property === 'number') {
      return property;
    } else if (typeof property === 'string') {
      const parsed = parseInt(property);
      return isNaN(parsed) ? undefined : parsed;
    }

    return undefined;
  }

  private getSafeBooleanProperty(props: Record<string, any>, key: string): boolean | undefined {
    const property = props[key];

    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'boolean') {
        return value;
      } else if (typeof value === 'string') {
        return value.toLowerCase() === 'true';
      } else if (Array.isArray(value) && value.length > 0) {
        const first = value[0];
        if (typeof first === 'boolean') {
          return first;
        }
        if (typeof first === 'string') {
          return first.toLowerCase() === 'true';
        }
      }
    }

    if (typeof property === 'boolean') {
      return property;
    }

    if (typeof property === 'string') {
      return property.toLowerCase() === 'true';
    }

    return undefined;
  }

  private extractAllowableActions(allowableActionsData: any): string[] {
    if (!allowableActionsData) {
      return [];
    }

    const data = allowableActionsData.allowableActions ?? allowableActionsData;

    if (Array.isArray(data)) {
      return data.map(action => String(action));
    }

    if (typeof data === 'object') {
      return Object.entries(data)
        .filter(([, value]) => value === true || value === 'true')
        .map(([key]) => key);
    }

    return [];
  }

  private encodeObjectIdSegment(objectId?: string): string {
    if (!objectId || objectId === 'root') {
      return 'root';
    }

    return encodeURIComponent(objectId);
  }

  private appendPropertiesToFormData(formData: FormData, properties: Record<string, any>, defaults: Record<string, any> = {}) {
    const merged = { ...defaults, ...properties };
    let propertyIndex = 0;

    Object.entries(merged).forEach(([key, value]) => {
      if (value === undefined || value === null) {
        return;
      }

      formData.append(`propertyId[${propertyIndex}]`, key);

      if (Array.isArray(value)) {
        if (value.length === 0) {
          formData.append(`propertyValue[${propertyIndex}]`, '');
        } else {
          value.forEach(item => {
            formData.append(`propertyValue[${propertyIndex}]`, String(item));
          });
        }
      } else {
        formData.append(`propertyValue[${propertyIndex}]`, String(value));
      }

      propertyIndex++;
    });
  }

  private buildCmisObjectFromBrowserData(data: any, fallbackId?: string): CMISObject {
    const objectData = data?.object ?? data ?? {};
    const props = objectData.succinctProperties || objectData.properties || {};

    const id = this.getSafeStringProperty(props, 'cmis:objectId', fallbackId || '');
    const name = this.getSafeStringProperty(props, 'cmis:name', '');
    const objectType = this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document');
    const baseType = this.getSafeStringProperty(props, 'cmis:baseTypeId', objectType);
    const parentId = this.getSafeStringProperty(props, 'cmis:parentId', '');
    const path = this.getSafeStringProperty(props, 'cmis:path', '');
    const contentStreamMimeType = this.getSafeStringProperty(props, 'cmis:contentStreamMimeType', '');
    const versionLabel = this.getSafeStringProperty(props, 'cmis:versionLabel', '');
    const createdBy = this.getSafeStringProperty(props, 'cmis:createdBy', '');
    const lastModifiedBy = this.getSafeStringProperty(props, 'cmis:lastModifiedBy', '');
    const creationDate = this.getSafeDateProperty(props, 'cmis:creationDate');
    const lastModificationDate = this.getSafeDateProperty(props, 'cmis:lastModificationDate');
    const contentStreamLength = this.getSafeIntegerProperty(props, 'cmis:contentStreamLength');
    const isLatestVersion = this.getSafeBooleanProperty(props, 'cmis:isLatestVersion');
    const isLatestMajorVersion = this.getSafeBooleanProperty(props, 'cmis:isLatestMajorVersion');

    const cmisObject: CMISObject = {
      id: id || fallbackId || '',
      name: name || id || fallbackId || 'Object',
      objectType,
      baseType: baseType || objectType,
      properties: props,
      allowableActions: this.extractAllowableActions(objectData.allowableActions)
    };

    if (parentId) {
      cmisObject.parentId = parentId;
    }

    if (path) {
      cmisObject.path = path;
    }

    if (contentStreamLength !== undefined) {
      cmisObject.contentStreamLength = contentStreamLength;
    }

    if (contentStreamMimeType) {
      cmisObject.contentStreamMimeType = contentStreamMimeType;
    }

    if (versionLabel) {
      cmisObject.versionLabel = versionLabel;
    }

    if (isLatestVersion !== undefined) {
      cmisObject.isLatestVersion = isLatestVersion;
    }

    if (isLatestMajorVersion !== undefined) {
      cmisObject.isLatestMajorVersion = isLatestMajorVersion;
    }

    if (createdBy) {
      cmisObject.createdBy = createdBy;
    }

    if (creationDate) {
      cmisObject.creationDate = creationDate;
    }

    if (lastModifiedBy) {
      cmisObject.lastModifiedBy = lastModifiedBy;
    }

    if (lastModificationDate) {
      cmisObject.lastModificationDate = lastModificationDate;
    }

    return cmisObject;
  }

  private getAuthHeaders() {
    try {
      const authData = localStorage.getItem('nemakiware_auth');
      if (authData) {
        const auth = JSON.parse(authData);
        if (auth.username && auth.token) {
          console.log('CMISService getAuthHeaders: Using token-based authentication with Basic auth header and nemaki_auth_token');
          // Use Basic auth with username to provide username context
          const credentials = btoa(`${auth.username}:dummy`);
          return { 
            'Authorization': `Basic ${credentials}`,
            'nemaki_auth_token': auth.token 
          };
        }
      }
    } catch (e) {
      console.error('CMISService: Failed to get auth from localStorage:', e);
    }
    
    console.warn('CMISService: No authentication available');
    return {};
  }

  private handleHttpError(status: number, statusText: string, url: string) {
    const error = {
      status,
      statusText,
      url,
      message: `HTTP ${status}: ${statusText}`
    };

    console.error('CMIS HTTP Error:', error);

    // Handle authentication errors
    if (status === 401) {
      console.warn('Authentication error detected - token may be expired');
      if (this.onAuthError) {
        this.onAuthError(error);
      }
    }

    return error;
  }

  async getRepositories(): Promise<string[]> {
    try {
      return new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        
        // Use unauthenticated endpoint for getting repository list
        // This is needed for the login screen where user hasn't authenticated yet
        xhr.open('GET', '/core/rest/all/repositories', true);
        xhr.setRequestHeader('Accept', 'application/json');
        
        xhr.onreadystatechange = () => {
          if (xhr.readyState === 4) {
            if (xhr.status === 200) {
              try {
                const response = JSON.parse(xhr.responseText);
                // Extract repository IDs from the response
                if (Array.isArray(response)) {
                  const repositoryIds = response.map(repo => repo.id).filter(id => id);
                  resolve(repositoryIds);
                } else if (response.repositories) {
                  resolve(response.repositories);
                } else {
                  resolve([]);
                }
              } catch (e) {
                console.error('Failed to parse repositories response:', e);
                resolve([]);
              }
            } else {
              console.error('Failed to fetch repositories:', xhr.status);
              if (xhr.status === 401) {
                this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
              }
              resolve([]);
            }
          }
        };
        
        xhr.onerror = () => {
          console.error('Network error fetching repositories');
          resolve([]);
        };
        
        xhr.send();
      });
    } catch (error) {
      console.error('Failed to fetch repositories:', error);
      return [];
    }
  }


  async getRootFolder(repositoryId: string): Promise<CMISObject> {
    console.log('CMIS DEBUG: getRootFolder called with repositoryId:', repositoryId);
    
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/root`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getRootFolder response:', xhr.responseText);
              const response = JSON.parse(xhr.responseText);
              console.log('CMIS DEBUG: getRootFolder parsed response:', response);
              
              const props = response.succinctProperties || response.properties || {};
              const rootFolder = {
                id: this.getSafeStringProperty(props, 'cmis:objectId', 'e02f784f8360a02cc14d1314c10038ff'),
                name: this.getSafeStringProperty(props, 'cmis:name', 'Root Folder'),
                objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:folder'),
                baseType: 'cmis:folder',
                properties: props,
                allowableActions: ['canGetChildren'],
                path: this.getSafeStringProperty(props, 'cmis:path', '/')
              };
              
              console.log('CMIS DEBUG: Parsed root folder:', rootFolder);
              resolve(rootFolder);
            } catch (error) {
              console.error('CMIS DEBUG: Error parsing getRootFolder response:', error);
              const fallbackFolder = {
                id: 'e02f784f8360a02cc14d1314c10038ff',
                name: 'Root Folder',
                objectType: 'cmis:folder',
                baseType: 'cmis:folder',
                properties: {},
                allowableActions: ['canGetChildren'],
                path: '/'
              };
              resolve(fallbackFolder);
            }
          } else {
            console.error('CMIS DEBUG: getRootFolder failed with status:', xhr.status);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            // For now, still provide fallback but notify about auth error
            const fallbackFolder = {
              id: 'e02f784f8360a02cc14d1314c10038ff',
              name: 'Root Folder',
              objectType: 'cmis:folder',
              baseType: 'cmis:folder',
              properties: {},
              allowableActions: ['canGetChildren'],
              path: '/'
            };
            resolve(fallbackFolder);
          }
        }
      };
      
      xhr.onerror = () => {
        console.error('CMIS DEBUG: Network error in getRootFolder');
        const fallbackFolder = {
          id: 'e02f784f8360a02cc14d1314c10038ff',
          name: 'Root Folder',
          objectType: 'cmis:folder',
          baseType: 'cmis:folder',
          properties: {},
          allowableActions: ['canGetChildren'],
          path: '/'
        };
        resolve(fallbackFolder);
      };
      
      xhr.send();
    });
  }

  async getChildren(repositoryId: string, folderId: string): Promise<CMISObject[]> {
    console.log('CMIS DEBUG: getChildren called with repositoryId:', repositoryId, 'folderId:', folderId);

    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      const url = `${this.baseUrl}/${repositoryId}/${folderId}?cmisselector=children`;

      xhr.open('GET', url, true);

      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getChildren response:', xhr.responseText.substring(0, 500));

              const response = JSON.parse(xhr.responseText);
              const entries = Array.isArray(response?.objects) ? response.objects : [];

              const children: CMISObject[] = entries.map((entry: any) => this.buildCmisObjectFromBrowserData(entry));

              resolve(children);
            } catch (e) {
              console.error('CMIS DEBUG: Error parsing Browser Binding response:', e);
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            console.error('CMIS DEBUG: getChildren HTTP error:', xhr.status, xhr.statusText);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        console.error('CMIS DEBUG: getChildren network error');
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }

  async getObject(repositoryId: string, objectId: string): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      xhr.open('GET', `${this.baseUrl}/${repositoryId}/${objectId}?cmisselector=object`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getObject Browser Binding response:', xhr.responseText.substring(0, 1000));

              const response = JSON.parse(xhr.responseText);
              const cmisObject = this.buildCmisObjectFromBrowserData(response, objectId);

              console.log('CMIS DEBUG: getObject parsed object:', cmisObject);
              resolve(cmisObject);
            } catch (e) {
              console.error('CMIS DEBUG: getObject parse error:', e);
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            console.error('CMIS DEBUG: getObject HTTP error:', xhr.status, xhr.statusText);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        console.error('CMIS DEBUG: getObject network error');
        reject(new Error('Network error'));
      };
      xhr.send();
    });
  }

  async createDocument(repositoryId: string, parentId: string, file: File, properties: Record<string, any>): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const parentSegment = this.encodeObjectIdSegment(parentId);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/${parentSegment}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              const created = this.buildCmisObjectFromBrowserData(response);
              resolve(created);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const documentName = properties?.['cmis:name'] || properties?.name || file.name;
      const defaults = {
        'cmis:objectTypeId': properties?.['cmis:objectTypeId'] || 'cmis:document',
        'cmis:name': documentName
      };

      const formData = new FormData();
      formData.append('cmisaction', 'createDocument');
      formData.append('succinct', 'true');
      formData.append('_charset_', 'UTF-8');
      if (parentId) {
        formData.append('folderId', parentId);
        formData.append('objectId', parentId);
      } else {
        formData.append('folderId', 'root');
        formData.append('objectId', 'root');
      }

      this.appendPropertiesToFormData(formData, properties || {}, defaults);
      formData.append('content', file, documentName);
      if (file.type) {
        formData.append('contentType', file.type);
      }

      xhr.send(formData);
    });
  }

  async createFolder(repositoryId: string, parentId: string, name: string, properties: Record<string, any> = {}): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const parentSegment = this.encodeObjectIdSegment(parentId);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/${parentSegment}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              const folder = this.buildCmisObjectFromBrowserData(response);
              resolve(folder);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const defaults = {
        'cmis:objectTypeId': properties?.['cmis:objectTypeId'] || 'cmis:folder',
        'cmis:name': properties?.['cmis:name'] || name
      };

      const formData = new FormData();
      formData.append('cmisaction', 'createFolder');
      formData.append('succinct', 'true');
      if (parentId) {
        formData.append('folderId', parentId);
        formData.append('objectId', parentId);
      } else {
        formData.append('folderId', 'root');
        formData.append('objectId', 'root');
      }

      this.appendPropertiesToFormData(formData, properties || {}, defaults);
      xhr.send(formData);
    });
  }

  async updateProperties(repositoryId: string, objectId: string, properties: Record<string, any>): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use Browser Binding updateProperties action
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const props = response.succinctProperties || response.properties || {};
              const updatedObject: CMISObject = {
                id: this.getSafeStringProperty(props, 'cmis:objectId', objectId),
                name: this.getSafeStringProperty(props, 'cmis:name', 'Unknown'),
                objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
                baseType: this.getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
                properties: props,
                allowableActions: response.allowableActions || []
              };
              resolve(updatedObject);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      // Use FormData for Browser Binding updateProperties
      const formData = new FormData();
      formData.append('cmisaction', 'updateProperties');
      formData.append('objectId', objectId);
      
      let propertyIndex = 0;
      Object.entries(properties).forEach(([key, value]) => {
        formData.append(`propertyId[${propertyIndex}]`, key);
        formData.append(`propertyValue[${propertyIndex}]`, String(value));
        propertyIndex++;
      });
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData);
    });
  }

  async deleteObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use Browser Binding delete action
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      // Use FormData for Browser Binding delete
      const formData = new FormData();
      formData.append('cmisaction', 'deleteObject');
      formData.append('objectId', objectId);
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData);
    });
  }

  async search(repositoryId: string, query: string): Promise<SearchResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/search?query=${encodeURIComponent(query)}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve({
                objects: response.results || [],
                hasMoreItems: response.hasMoreItems || false,
                numItems: response.numItems || 0
              });
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async getVersionHistory(repositoryId: string, objectId: string): Promise<VersionHistory> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/${objectId}?cmisselector=versions`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const entries = Array.isArray(response?.objects) ? response.objects : [];
              const versions = entries.map((entry: any) => this.buildCmisObjectFromBrowserData(entry, objectId));

              const latestVersion = versions.find(version => version.isLatestVersion) || versions[0];

              if (!latestVersion) {
                reject(new Error('No versions returned for object'));
                return;
              }

              const versionHistory: VersionHistory = {
                versions,
                latestVersion
              };

              resolve(versionHistory);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding version history'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async checkOut(repositoryId: string, objectId: string): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/node/${objectId}/checkout`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.object);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify({}));
    });
  }

  async checkIn(repositoryId: string, objectId: string, file?: File, properties?: Record<string, any>): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/node/${objectId}/checkin`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      const formData = new FormData();
      if (file) {
        formData.append('file', file);
      }
      if (properties) {
        Object.entries(properties).forEach(([key, value]) => {
          formData.append(key, value);
        });
      }
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.object);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData);
    });
  }

  async cancelCheckOut(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/node/${objectId}/cancelcheckout`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify({}));
    });
  }

  async getACL(repositoryId: string, objectId: string): Promise<ACL> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/node/${objectId}/acl`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.acl);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async setACL(repositoryId: string, objectId: string, acl: ACL): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/node/${objectId}/acl`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(acl));
    });
  }

  async getUsers(repositoryId: string): Promise<User[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // サーバー側修正に合わせて正しいRESTエンドポイントを使用
      xhr.open('GET', `/core/rest/repo/${repositoryId}/user/list`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const rawUsers = response.users || [];
              
              // Transform user data to match UI expectations
              const transformedUsers = rawUsers.map((user: any) => ({
                id: user.userId || user.id,
                name: user.firstName && user.lastName ? 
                  `${user.firstName} ${user.lastName}` : 
                  (user.userName || user.userId || user.id),
                email: user.email,
                groups: user.groups || []
              }));
              
              console.log('CMIS DEBUG: getUsers raw data:', rawUsers);
              console.log('CMIS DEBUG: getUsers transformed data:', transformedUsers);
              
              // 詳細ログ出力
              transformedUsers.forEach((user, index) => {
                console.log(`User ${index + 1}:`, {
                  id: user.id,
                  name: user.name,
                  email: user.email,
                  groups: user.groups
                });
              });
              
              resolve(transformedUsers);
            } catch (e) {
              console.error('CMIS DEBUG: getUsers parse error:', e);
              reject(new Error('Invalid response format'));
            }
          } else if (xhr.status === 500) {
            // サーバー側エラーの詳細情報を解析
            let errorMessage = 'サーバーエラーが発生しました';
            let errorDetails = '';
            try {
              const errorResponse = JSON.parse(xhr.responseText);
              if (errorResponse.message) {
                errorMessage = errorResponse.message;
              }
              if (errorResponse.error) {
                errorDetails = errorResponse.error;
              }
              if (errorResponse.errorType) {
                errorDetails += ` (${errorResponse.errorType})`;
              }
            } catch (e) {
              // JSONパースできない場合はレスポンステキストをそのまま使用
              errorDetails = xhr.responseText || 'Unknown server error';
            }
            
            const error = new Error(errorMessage);
            (error as any).details = errorDetails;
            (error as any).status = xhr.status;
            reject(error);
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createUser(repositoryId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.restBaseUrl}/${repositoryId}/user/create/${user.id}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      
      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', user.name || '');  // FORM_USERNAME = "name"
      formData.append('firstName', user.firstName || '');  // FORM_FIRSTNAME = "firstName"
      formData.append('lastName', user.lastName || '');  // FORM_LASTNAME = "lastName"
      formData.append('email', user.email || '');  // FORM_EMAIL = "email"  
      formData.append('password', user.password || '');  // FORM_PASSWORD = "password"
      
      xhr.send(formData.toString());
    });
  }

  async updateUser(repositoryId: string, userId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', `${this.restBaseUrl}/${repositoryId}/user/update/${userId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      
      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', user.name || '');  // FORM_USERNAME = "name"
      formData.append('firstName', user.firstName || '');  // FORM_FIRSTNAME = "firstName"
      formData.append('lastName', user.lastName || '');  // FORM_LASTNAME = "lastName"
      formData.append('email', user.email || '');  // FORM_EMAIL = "email"
      
      xhr.send(formData.toString());
    });
  }

  async deleteUser(repositoryId: string, userId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.restBaseUrl}/${repositoryId}/user/delete/${userId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async getGroups(repositoryId: string): Promise<Group[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // サーバー側修正に合わせて正しいRESTエンドポイントを使用
      xhr.open('GET', `/core/rest/repo/${repositoryId}/group/list`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const rawGroups = response.groups || [];
              
              // Transform group data to match UI expectations
              const transformedGroups = rawGroups.map((group: any) => ({
                id: group.groupId || group.id,
                name: group.groupName || group.name || group.groupId || 'Unknown Group',
                members: group.users || []
              }));
              
              console.log('CMIS DEBUG: getGroups raw data:', rawGroups);
              console.log('CMIS DEBUG: getGroups transformed data:', transformedGroups);
              
              resolve(transformedGroups);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else if (xhr.status === 500) {
            // サーバー側エラーの詳細情報を解析
            let errorMessage = 'サーバーエラーが発生しました';
            let errorDetails = '';
            try {
              const errorResponse = JSON.parse(xhr.responseText);
              if (errorResponse.message) {
                errorMessage = errorResponse.message;
              }
              if (errorResponse.error) {
                errorDetails = errorResponse.error;
              }
              if (errorResponse.errorType) {
                errorDetails += ` (${errorResponse.errorType})`;
              }
            } catch (e) {
              // JSONパースできない場合はレスポンステキストをそのまま使用
              errorDetails = xhr.responseText || 'Unknown server error';
            }
            
            const error = new Error(errorMessage);
            (error as any).details = errorDetails;
            (error as any).status = xhr.status;
            reject(error);
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createGroup(repositoryId: string, group: Partial<Group>): Promise<Group> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.restBaseUrl}/${repositoryId}/group/create/${group.id}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      
      // Convert to form data - match server-side FORM_ constants  
      const formData = new URLSearchParams();
      formData.append('name', group.name || '');  // FORM_GROUPNAME = "name"
      formData.append('users', JSON.stringify(group.users || []));  // FORM_MEMBER_USERS = "users"
      formData.append('groups', JSON.stringify(group.groups || []));  // FORM_MEMBER_GROUPS = "groups"
      
      xhr.send(formData.toString());
    });
  }

  async updateGroup(repositoryId: string, groupId: string, group: Partial<Group>): Promise<Group> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', `${this.restBaseUrl}/${repositoryId}/group/update/${groupId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      
      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', group.name || '');  // FORM_GROUPNAME = "name"
      formData.append('users', JSON.stringify(group.users || []));  // FORM_MEMBER_USERS = "users"
      formData.append('groups', JSON.stringify(group.groups || []));  // FORM_MEMBER_GROUPS = "groups"
      
      xhr.send(formData.toString());
    });
  }

  async deleteGroup(repositoryId: string, groupId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.restBaseUrl}/${repositoryId}/group/delete/${groupId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async getTypes(repositoryId: string): Promise<TypeDefinition[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use Browser Binding for type definitions (returns clean JSON)
      xhr.open('GET', `/core/browser/${repositoryId}?cmisselector=typeChildren`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getTypes response:', xhr.responseText.substring(0, 500));

              // Parse Browser Binding JSON response for type definitions
              const jsonResponse = JSON.parse(xhr.responseText);
              console.log('CMIS DEBUG: getTypes JSON response:', jsonResponse);

              if (!jsonResponse.types || !Array.isArray(jsonResponse.types)) {
                console.error('CMIS DEBUG: Invalid response format - no types array');
                reject(new Error('Invalid response format'));
                return;
              }

              const types: TypeDefinition[] = jsonResponse.types.map((type: any) => {
                // Determine if type is deletable (standard CMIS types should not be deletable)
                const isStandardType = type.id.startsWith('cmis:');
                const deletable = !isStandardType && (type.typeMutability?.delete !== false);

                const typeDefinition: TypeDefinition = {
                  id: type.id || 'unknown',
                  displayName: type.displayName || type.id || 'Unknown Type',
                  description: type.description || (type.displayName || type.id) + ' type definition',
                  baseTypeId: type.baseId || type.id,
                  creatable: type.creatable !== false,
                  fileable: type.fileable !== false,
                  queryable: type.queryable !== false,
                  deletable: deletable,
                  propertyDefinitions: {}
                };

                console.log(`CMIS DEBUG: Added type: ${type.id} (deletable: ${deletable})`);
                return typeDefinition;
              });

              console.log('CMIS DEBUG: getTypes parsed types count:', types.length);
              console.log('CMIS DEBUG: getTypes parsed types:', types);
              resolve(types);
            } catch (e) {
              console.error('CMIS DEBUG: getTypes parse error:', e);
              reject(new Error('Failed to parse type definitions JSON'));
            }
          } else {
            console.error('CMIS DEBUG: getTypes HTTP error:', xhr.status, xhr.statusText);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        console.error('CMIS DEBUG: getTypes network error');
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }

  private parseBrowserTypeDefinition(rawType: any, typeIdFallback: string): TypeDefinition {
    const propertyDefinitions: Record<string, PropertyDefinition> = {};
    const rawPropertyDefs = rawType?.propertyDefinitions || {};

    Object.entries(rawPropertyDefs).forEach(([propId, propValue]) => {
      const property = propValue as any;
      const resolvedId = property?.id || propId;
      const propertyType = String(property?.propertyType || 'string').toLowerCase();
      const cardinalityRaw = String(property?.cardinality || 'single').toLowerCase();
      const updatability = String(property?.updatability || property?.updatable || 'readwrite').toLowerCase();
      const defaultValues = Array.isArray(property?.defaultValue)
        ? property.defaultValue
        : (Array.isArray(property?.defaultValue?.values) ? property.defaultValue.values : undefined);

      const choices = Array.isArray(property?.choices)
        ? property.choices.map((choice: any) => ({
            displayName: choice?.displayName || choice?.displayname || '',
            value: Array.isArray(choice?.value) ? choice.value : []
          }))
        : undefined;

      const normalizedType = (['string', 'integer', 'decimal', 'boolean', 'datetime'].includes(propertyType)
        ? propertyType
        : 'string') as 'string' | 'integer' | 'decimal' | 'boolean' | 'datetime';

      propertyDefinitions[resolvedId] = {
        id: resolvedId,
        displayName: property?.displayName || property?.displayname || resolvedId,
        description: property?.description || '',
        propertyType: normalizedType,
        cardinality: cardinalityRaw === 'multi' ? 'multi' : 'single',
        required: property?.required === true,
        queryable: property?.queryable !== false,
        updatable: !(updatability === 'readonly'),
        defaultValue: defaultValues,
        choices,
        maxLength: property?.maxLength ?? property?.maxlen ?? undefined,
        minValue: property?.minValue ?? property?.min ?? undefined,
        maxValue: property?.maxValue ?? property?.max ?? undefined
      };
    });

    const baseTypeId = rawType?.baseId || rawType?.baseTypeId || rawType?.baseType || typeIdFallback;

    return {
      id: rawType?.id || typeIdFallback,
      displayName: rawType?.displayName || rawType?.displayname || rawType?.id || typeIdFallback,
      description: rawType?.description || '',
      baseTypeId,
      parentTypeId: rawType?.parentId || rawType?.parentTypeId || undefined,
      propertyDefinitions,
      creatable: rawType?.creatable !== false,
      fileable: rawType?.fileable !== false,
      queryable: rawType?.queryable !== false,
      deletable: rawType?.typeMutability?.delete !== false && rawType?.deletable !== false
    };
  }

  async getType(repositoryId: string, typeId: string): Promise<TypeDefinition> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const query = `cmisselector=typeDefinition&typeId=${encodeURIComponent(typeId)}&succinct=true`;
      xhr.open('GET', `${this.baseUrl}/${repositoryId}?${query}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const typeData = response?.type || response;
              const typeDefinition = this.parseBrowserTypeDefinition(typeData, typeId);
              resolve(typeDefinition);
            } catch (e) {
              reject(new Error('Failed to parse type definition response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createType(repositoryId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              const typeData = response?.type || response;
              const typeDefinition = this.parseBrowserTypeDefinition(typeData, type?.id || '');
              resolve(typeDefinition);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const formData = new FormData();
      formData.append('cmisaction', 'createType');
      formData.append('succinct', 'true');
      formData.append('type', JSON.stringify(type));

      xhr.send(formData);
    });
  }

  async updateType(repositoryId: string, typeId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/type/${typeId}/update`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.type);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(type));
    });
  }

  async deleteType(repositoryId: string, typeId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const formData = new FormData();
      formData.append('cmisaction', 'deleteType');
      formData.append('typeId', typeId);

      xhr.send(formData);
    });
  }

  async getRelationships(repositoryId: string, objectId: string): Promise<Relationship[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/${objectId}?cmisselector=relationships`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const entries = Array.isArray(response?.objects) ? response.objects : [];

              const relationships: Relationship[] = entries.map((entry: any) => {
                const cmisObject = this.buildCmisObjectFromBrowserData(entry);
                return {
                  id: cmisObject.id,
                  sourceId: this.getSafeStringProperty(cmisObject.properties, 'cmis:sourceId', ''),
                  targetId: this.getSafeStringProperty(cmisObject.properties, 'cmis:targetId', ''),
                  relationshipType: cmisObject.objectType,
                  properties: cmisObject.properties
                } as Relationship;
              });

              resolve(relationships);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding relationships'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createRelationship(repositoryId: string, relationship: Partial<Relationship>): Promise<Relationship> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/relationship/create`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.relationship);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(relationship));
    });
  }

  async deleteRelationship(repositoryId: string, relationshipId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.baseUrl}/${repositoryId}/relationship/${relationshipId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async initSearchEngine(repositoryId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/search-engine/init`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async reindexSearchEngine(repositoryId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/search-engine/reindex`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async getArchives(repositoryId: string): Promise<CMISObject[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use correct REST endpoint for archive index
      xhr.open('GET', `/core/rest/repo/${repositoryId}/archive/index`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.archives || []);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async archiveObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/archive/${objectId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify({}));
    });
  }

  async restoreObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/archive/${objectId}/restore`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify({}));
    });
  }

  getDownloadUrl(repositoryId: string, objectId: string): string {
    const token = this.authService.getAuthToken();
    return `${this.baseUrl}/${repositoryId}/node/${objectId}/content?token=${token}`;
  }

  async getContentStream(repositoryId: string, objectId: string): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      xhr.open('GET', `${this.baseUrl}/${repositoryId}/${objectId}?cmisselector=content`, true);
      xhr.responseType = 'arraybuffer';
      xhr.setRequestHeader('Accept', 'application/octet-stream');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            resolve(xhr.response);
          } else {
            console.error('CMIS DEBUG: getContentStream HTTP error:', xhr.status, xhr.statusText);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        console.error('CMIS DEBUG: getContentStream network error');
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }
}
