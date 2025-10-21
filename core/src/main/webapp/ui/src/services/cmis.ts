import { AuthService } from './auth';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, User, Group, ACL } from '../types/cmis';

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

  // REMOVED: getSafeBooleanProperty - unused helper method (2025-10-22)

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
      formData.append(`propertyValue[${propertyIndex}]`, String(value));
      propertyIndex++;
    });
  }

  private buildCmisObjectFromBrowserData(data: any): CMISObject {
    const props = data.succinctProperties || data.properties || {};
    
    return {
      id: this.getSafeStringProperty(props, 'cmis:objectId', ''),
      name: this.getSafeStringProperty(props, 'cmis:name', 'Unknown'),
      objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
      baseType: this.getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
      properties: props,
      allowableActions: this.extractAllowableActions(data.allowableActions),
      createdBy: this.getSafeStringProperty(props, 'cmis:createdBy'),
      lastModifiedBy: this.getSafeStringProperty(props, 'cmis:lastModifiedBy'),
      creationDate: this.getSafeDateProperty(props, 'cmis:creationDate'),
      lastModificationDate: this.getSafeDateProperty(props, 'cmis:lastModificationDate'),
      contentStreamLength: this.getSafeIntegerProperty(props, 'cmis:contentStreamLength'),
      path: this.getSafeStringProperty(props, 'cmis:path')
    };
  }

  private buildTypeDefinitionFromBrowserData(rawType: any): TypeDefinition {
    return {
      id: rawType?.id || 'unknown',
      displayName: rawType?.displayName || rawType?.id || 'Unknown Type',
      description: rawType?.description || `${rawType?.displayName || rawType?.id || 'Unknown'} type definition`,
      baseTypeId: rawType?.baseTypeId || rawType?.baseId || (rawType?.id?.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document'),
      parentTypeId: rawType?.parentTypeId || rawType?.parentId,
      creatable: rawType?.creatable !== false,
      fileable: rawType?.fileable !== false,
      queryable: rawType?.queryable !== false,
      deletable: rawType?.typeMutability?.delete !== false && rawType?.deletable !== false,
      propertyDefinitions: rawType?.propertyDefinitions || {}
    };
  }

  private getAuthHeaders() {
    try {
      const authData = localStorage.getItem('nemakiware_auth');
      console.log('[AUTH DEBUG] getAuthHeaders called, localStorage data:', authData ? 'EXISTS' : 'NULL');

      if (authData) {
        const auth = JSON.parse(authData);
        console.log('[AUTH DEBUG] Parsed auth:', {
          hasUsername: !!auth.username,
          hasToken: !!auth.token,
          tokenLength: auth.token?.length || 0
        });

        if (auth.username && auth.token) {
          console.log('[AUTH DEBUG] Using token-based authentication for user:', auth.username);
          // Use Basic auth with username to provide username context
          const credentials = btoa(`${auth.username}:dummy`);
          return {
            'Authorization': `Basic ${credentials}`,
            'nemaki_auth_token': auth.token
          };
        } else {
          console.warn('[AUTH DEBUG] Auth data incomplete:', { username: auth.username, hasToken: !!auth.token });
        }
      } else {
        console.warn('[AUTH DEBUG] No auth data in localStorage');
      }
    } catch (e) {
      console.error('[AUTH DEBUG] Failed to get auth from localStorage:', e);
    }

    console.warn('[AUTH DEBUG] No authentication available - returning empty headers');
    return {};
  }

  private handleHttpError(status: number, statusText: string, url: string) {
    const error = {
      status,
      statusText,
      url,
      message: `HTTP ${status}: ${statusText}`
    };

    console.error('[AUTH DEBUG] CMIS HTTP Error:', error);

    // Handle authentication and permission errors - all should redirect to login
    if (status === 401) {
      console.error('[AUTH DEBUG] 401 Unauthorized detected!');
      console.error('[AUTH DEBUG] URL that failed:', url);
      console.error('[AUTH DEBUG] Current localStorage auth:', localStorage.getItem('nemakiware_auth') ? 'EXISTS' : 'NULL');
      console.warn('401 Unauthorized - token may be expired or invalid');
      if (this.onAuthError) {
        console.log('[AUTH DEBUG] Calling onAuthError handler - will redirect to login');
        this.onAuthError(error);
      } else {
        console.warn('[AUTH DEBUG] No onAuthError handler set!');
      }
    } else if (status === 403) {
      console.warn('403 Forbidden - insufficient permissions');
      if (this.onAuthError) {
        this.onAuthError(error);
      }
    } else if (status === 404) {
      console.warn('404 Not Found - resource may have been deleted or URL is invalid');
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

    return new Promise((resolve, _reject) => {
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
            this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
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
      
      // Use AtomPub for all folder children queries due to Browser Binding issues with empty results
      // Always use AtomPub for children queries - more reliable than Browser Binding
      const url = `/core/atom/${repositoryId}/children?id=${folderId}`;
      
      xhr.open('GET', url, true);

      // Set headers AFTER xhr.open() - always use AtomPub
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getChildren response:', xhr.responseText.substring(0, 500));
              
              const children: CMISObject[] = [];

              // Parse AtomPub XML response
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');

              console.log('CMIS DEBUG: AtomPub XML response received, parsing...');

              // Check for parse errors
              const parseError = xmlDoc.querySelector('parsererror');
              if (parseError) {
                console.error('CMIS DEBUG: XML parse error:', parseError.textContent);
                reject(new Error('Invalid XML response'));
                return;
              }

              // Try both atom:entry and entry
              const entries = xmlDoc.getElementsByTagName('atom:entry').length > 0
                ? xmlDoc.getElementsByTagName('atom:entry')
                : xmlDoc.getElementsByTagName('entry');

              console.log('CMIS DEBUG: Found', entries.length, 'entries in AtomPub response');

              for (let i = 0; i < entries.length; i++) {
                const entry = entries[i];

                // Extract simple data from atom:entry first
                const atomTitle = entry.querySelector('title')?.textContent || 'Unknown';
                const atomId = entry.querySelector('id')?.textContent || '';

                // Get cmisra:object for properties
                const cmisObject = entry.getElementsByTagNameNS('http://docs.oasis-open.org/ns/cmis/restatom/200908/', 'object')[0] ||
                                  entry.querySelector('cmisra\\:object, object');

                let properties = null;
                if (cmisObject) {
                  properties = cmisObject.getElementsByTagNameNS('http://docs.oasis-open.org/ns/cmis/core/200908/', 'properties')[0] ||
                             cmisObject.querySelector('cmis\\:properties, properties');
                }

                if (!properties) {
                  // Fallback: look for properties directly under entry
                  properties = entry.getElementsByTagNameNS('http://docs.oasis-open.org/ns/cmis/core/200908/', 'properties')[0] ||
                             entry.querySelector('cmis\\:properties, properties');
                }

                if (properties) {
                  // Helper function to get property value
                  const getPropertyValue = (propName: string, propType: string = 'propertyString') => {
                    const propElements = properties.getElementsByTagName(`cmis:${propType}`);
                    for (let j = 0; j < propElements.length; j++) {
                      const elem = propElements[j];
                      if (elem.getAttribute('propertyDefinitionId') === propName) {
                        const valueElem = elem.querySelector('cmis\\:value, value');
                        return valueElem?.textContent || '';
                      }
                    }
                    return '';
                  };

                  const id = getPropertyValue('cmis:objectId', 'propertyId') || atomId;
                  const name = getPropertyValue('cmis:name', 'propertyString') || atomTitle;
                  const objectType = getPropertyValue('cmis:objectTypeId', 'propertyId') || 'cmis:document';
                  const createdBy = getPropertyValue('cmis:createdBy', 'propertyString');
                  const lastModifiedBy = getPropertyValue('cmis:lastModifiedBy', 'propertyString');
                  const creationDate = getPropertyValue('cmis:creationDate', 'propertyDateTime');
                  const lastModificationDate = getPropertyValue('cmis:lastModificationDate', 'propertyDateTime');
                  const contentStreamLengthStr = getPropertyValue('cmis:contentStreamLength', 'propertyInteger');

                  const cmisObject: CMISObject = {
                    id: id,
                    name: name,
                    objectType: objectType,
                    baseType: objectType.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
                    properties: {
                      'cmis:name': name,
                      'cmis:objectId': id,
                      'cmis:objectTypeId': objectType,
                      'cmis:createdBy': createdBy,
                      'cmis:lastModifiedBy': lastModifiedBy,
                      'cmis:creationDate': creationDate,
                      'cmis:lastModificationDate': lastModificationDate,
                      'cmis:contentStreamLength': contentStreamLengthStr ? parseInt(contentStreamLengthStr) : undefined
                    },
                    allowableActions: [],
                    createdBy: createdBy,
                    lastModifiedBy: lastModifiedBy,
                    creationDate: creationDate,
                    lastModificationDate: lastModificationDate,
                    contentStreamLength: contentStreamLengthStr ? parseInt(contentStreamLengthStr) : undefined
                  };
                  children.push(cmisObject);
                } else {
                  // プロパティが見つからない場合の簡易処理
                  console.log('CMIS DEBUG: No properties found for entry', i, ', using fallback');
                  const fallbackObject: CMISObject = {
                    id: atomId.split('/').pop() || `entry-${i}`,
                    name: atomTitle,
                    objectType: 'cmis:document',
                    baseType: 'cmis:document',
                    properties: {
                      'cmis:name': atomTitle,
                      'cmis:objectId': atomId.split('/').pop() || `entry-${i}`
                    },
                    allowableActions: []
                  };
                  children.push(fallbackObject);
                }
              }
              
              console.log(`CMIS DEBUG: Parsed ${children.length} children:`, children);
              
              // 詳細ログ出力
              children.forEach((child, index) => {
                console.log(`Child ${index + 1}:`, {
                  id: child.id,
                  name: child.name,
                  baseType: child.baseType,
                  createdBy: child.createdBy,
                  lastModifiedBy: child.lastModifiedBy,
                  lastModificationDate: child.lastModificationDate,
                  contentStreamLength: child.contentStreamLength
                });
              });
              
              resolve(children);
            } catch (e) {
              console.error('CMIS DEBUG: getChildren parse error:', e);
              reject(new Error('Failed to parse AtomPub response'));
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
      
      // Use AtomPub binding for getObject as Browser Binding doesn't have proper object endpoint
      console.log('CMIS DEBUG: getObject using AtomPub for objectId:', objectId);
      
      xhr.open('GET', `/core/atom/${repositoryId}/id?id=${objectId}`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getObject AtomPub response:', xhr.responseText.substring(0, 1000));
              
              // Parse AtomPub XML response
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
              console.log('CMIS DEBUG: getObject parsed XML:', xmlDoc);
              
              const entry = xmlDoc.querySelector('entry, atom\\:entry');
              console.log('CMIS DEBUG: getObject entry element:', entry);
              
              if (!entry) {
                console.error('CMIS DEBUG: No entry found in AtomPub response');
                reject(new Error('No entry found in AtomPub response'));
                return;
              }
              
              const title = entry.querySelector('title, atom\\:title')?.textContent || 'Unknown';
              console.log('CMIS DEBUG: getObject title:', title);
              const properties = entry.querySelector('cmis\\:properties, properties');
              
              let objectType = 'cmis:document';
              let baseType = 'cmis:document';
              let path = '';
              
              if (properties) {
                const objectTypeElement = properties.querySelector('cmis\\:propertyId[propertyDefinitionId="cmis:objectTypeId"], propertyId[propertyDefinitionId="cmis:objectTypeId"]');
                const baseTypeElement = properties.querySelector('cmis\\:propertyId[propertyDefinitionId="cmis:baseTypeId"], propertyId[propertyDefinitionId="cmis:baseTypeId"]');
                const pathElement = properties.querySelector('cmis\\:propertyString[propertyDefinitionId="cmis:path"], propertyString[propertyDefinitionId="cmis:path"]');
                
                if (objectTypeElement) {
                  objectType = objectTypeElement.querySelector('cmis\\:value, value')?.textContent || objectType;
                }
                if (baseTypeElement) {
                  baseType = baseTypeElement.querySelector('cmis\\:value, value')?.textContent || baseType;
                }
                if (pathElement) {
                  path = pathElement.querySelector('cmis\\:value, value')?.textContent || '';
                }
              }
              
              const cmisObject: CMISObject = {
                id: objectId,
                name: title,
                objectType: objectType,
                baseType: baseType,
                properties: {}, // Simplified - would need full parsing for complete properties
                allowableActions: [],
                path: path
              };
              
              console.log('CMIS DEBUG: getObject parsed object:', cmisObject);
              resolve(cmisObject);
            } catch (e) {
              console.error('CMIS DEBUG: getObject parse error:', e);
              reject(new Error('Failed to parse AtomPub response'));
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
      const xhr = new XMLHttpRequest();
      // Browser Binding createDocument uses repository base URL, not object-specific URL
      // Parent folder is specified via folderId parameter in form data
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
      // Use CMIS AtomPub binding for version history (Browser Binding doesn't support versions endpoint)
      xhr.open('GET', `/core/atom/${repositoryId}/versions?id=${objectId}`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              // Parse AtomPub XML response for version history
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
              const entries = xmlDoc.getElementsByTagName('entry');
              
              const versions = [];
              for (let i = 0; i < entries.length; i++) {
                const entry = entries[i];
                const id = entry.querySelector('id')?.textContent || '';
                const title = entry.querySelector('title')?.textContent || '';
                
                versions.push({
                  id: id.split('/').pop() || id,
                  name: title,
                  versionLabel: title // Simplified version parsing
                });
              }
              
              const versionHistory: VersionHistory = {
                versions: versions,
                hasMoreItems: false,
                numItems: versions.length
              };
              
              resolve(versionHistory);
            } catch (e) {
              reject(new Error('Failed to parse version history XML'));
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
              // Preserve firstName and lastName as separate fields for table display
              const transformedUsers = rawUsers.map((user: any) => ({
                id: user.userId || user.id,
                name: user.userName || user.userId || user.id,
                firstName: user.firstName,
                lastName: user.lastName,
                email: user.email,
                groups: user.groups || []
              }));
              
              console.log('CMIS DEBUG: getUsers raw data:', rawUsers);
              console.log('CMIS DEBUG: getUsers transformed data:', transformedUsers);
              
              // 詳細ログ出力
              transformedUsers.forEach((user: any, index: number) => {
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
      // Add groups parameter - serialize as JSON
      if (user.groups && user.groups.length > 0) {
        formData.append('groups', JSON.stringify(user.groups));
      }

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
      // Add groups parameter - serialize as JSON
      if (user.groups !== undefined) {
        formData.append('groups', JSON.stringify(user.groups));
      }

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
    // CRITICAL FIX (2025-10-21): Fetch both base types AND child types
    // Previous implementation only returned base types (6), missing custom types (nemaki:*)
    // This caused Type Management UI to show 6 rows instead of 10

    try {
      // Step 1: Fetch base types
      const baseTypes = await this.fetchTypeChildren(repositoryId, null);
      console.log('CMIS DEBUG: getTypes - base types count:', baseTypes.length);

      // Step 2: Fetch child types for each base type
      const childTypePromises = baseTypes.map(baseType =>
        this.fetchTypeChildren(repositoryId, baseType.id)
      );

      const childTypeArrays = await Promise.all(childTypePromises);
      const childTypes = childTypeArrays.flat();
      console.log('CMIS DEBUG: getTypes - child types count:', childTypes.length);

      // Step 3: Combine base types and child types
      const allTypes = [...baseTypes, ...childTypes];
      console.log('CMIS DEBUG: getTypes - total types count:', allTypes.length);
      console.log('CMIS DEBUG: getTypes - all type IDs:', allTypes.map(t => t.id));

      return allTypes;
    } catch (error) {
      console.error('CMIS DEBUG: getTypes error:', error);
      throw error;
    }
  }

  private async fetchTypeChildren(repositoryId: string, typeId: string | null): Promise<TypeDefinition[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      // Build URL with optional typeId parameter
      const url = typeId
        ? `/core/browser/${repositoryId}?cmisselector=typeChildren&typeId=${encodeURIComponent(typeId)}`
        : `/core/browser/${repositoryId}?cmisselector=typeChildren`;

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
              const jsonResponse = JSON.parse(xhr.responseText);

              if (!jsonResponse.types || !Array.isArray(jsonResponse.types)) {
                // Empty child types array is OK (not all base types have children)
                resolve([]);
                return;
              }

              const types: TypeDefinition[] = jsonResponse.types.map((type: any) => {
                // Determine if type is deletable (standard CMIS types should not be deletable)
                const isStandardType = type.id.startsWith('cmis:');
                const deletable = !isStandardType && (type.typeMutability?.delete !== false);

                return {
                  id: type.id || 'unknown',
                  displayName: type.displayName || type.id || 'Unknown Type',
                  description: type.description || (type.displayName || type.id) + ' type definition',
                  baseTypeId: type.baseId || type.id,
                  parentTypeId: type.parentTypeId || type.parentId,
                  creatable: type.creatable !== false,
                  fileable: type.fileable !== false,
                  queryable: type.queryable !== false,
                  deletable: deletable,
                  propertyDefinitions: {}
                };
              });

              resolve(types);
            } catch (e) {
              console.error('CMIS DEBUG: fetchTypeChildren parse error:', e);
              reject(new Error('Failed to parse type definitions JSON'));
            }
          } else {
            console.error('CMIS DEBUG: fetchTypeChildren HTTP error:', xhr.status, xhr.statusText);
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        console.error('CMIS DEBUG: fetchTypeChildren network error');
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }

  async getType(repositoryId: string, typeId: string): Promise<TypeDefinition> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}?cmisselector=typeDefinition&typeId=${encodeURIComponent(typeId)}`, true);
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
              const typeDefinition = this.buildTypeDefinitionFromBrowserData(response);
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
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/type/create`, true);
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
      xhr.open('DELETE', `${this.baseUrl}/${repositoryId}/type/${typeId}`, true);
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

  async getRelationships(repositoryId: string, objectId: string): Promise<Relationship[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use CMIS AtomPub binding for relationships (Browser Binding doesn't support relationships endpoint)
      xhr.open('GET', `/core/atom/${repositoryId}/relationships?id=${objectId}`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              // Parse AtomPub XML response for relationships
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
              const entries = xmlDoc.getElementsByTagName('entry');
              
              const relationships: Relationship[] = [];
              for (let i = 0; i < entries.length; i++) {
                const entry = entries[i];
                const id = entry.querySelector('id')?.textContent || '';

                relationships.push({
                  id: id.split('/').pop() || id,
                  sourceId: '', // Would need to parse from CMIS properties
                  targetId: '', // Would need to parse from CMIS properties
                  type: 'cmis:relationship'
                });
              }
              
              resolve(relationships);
            } catch (e) {
              reject(new Error('Failed to parse relationships XML'));
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
      
      // Use AtomPub binding for content stream download
      xhr.open('GET', `/core/atom/${repositoryId}/content?id=${objectId}`, true);
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
