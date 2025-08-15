import { AuthService } from './auth';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, User, Group, ACL } from '../types/cmis';
import { BROWSER_BASE, REST_BASE } from '../config';

export class CMISService {
  private baseUrl = BROWSER_BASE;
  private restBaseUrl = `${REST_BASE}/repo`;
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
        xhr.open('GET', `${REST_BASE}/all/repositories`, true);
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
      
      // Use Browser Binding for root folder, AtomPub for subfolders (Browser Binding doesn't support subfolders properly)
      let url: string;
      let useAtomPub = false;
      
      if (folderId === 'e02f784f8360a02cc14d1314c10038ff') {
        // Root folder uses Browser Binding /root endpoint (known to work)
        url = `${this.baseUrl}/${repositoryId}/root?cmisselector=children`;
      } else {
        // Use AtomPub for subfolders as Browser Binding doesn't support object ID-based queries
        useAtomPub = true;
        url = `/core/atom/${repositoryId}/children?id=${folderId}`;
      }
      
      xhr.open('GET', url, true);
      
      // Set headers AFTER xhr.open()
      if (useAtomPub) {
        xhr.setRequestHeader('Accept', 'application/atom+xml');
      } else {
        xhr.setRequestHeader('Accept', 'application/json');
      }
      
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
              
              if (useAtomPub) {
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
              } else {
                // Parse Browser Binding JSON response
                const response = JSON.parse(xhr.responseText);
                console.log('CMIS DEBUG: getChildren parsed response:', response);
                
                if (response.objects && Array.isArray(response.objects)) {
                  response.objects.forEach((obj: any) => {
                    const props = obj.object?.succinctProperties || obj.object?.properties || {};
                    
                    const objectTypeId = this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document');
                    const cmisObject: CMISObject = {
                      id: this.getSafeStringProperty(props, 'cmis:objectId'),
                      name: this.getSafeStringProperty(props, 'cmis:name'),
                      objectType: objectTypeId,
                      baseType: objectTypeId.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
                      properties: props,
                      allowableActions: [],
                      createdBy: this.getSafeStringProperty(props, 'cmis:createdBy'),
                      lastModifiedBy: this.getSafeStringProperty(props, 'cmis:lastModifiedBy'),
                      creationDate: this.getSafeDateProperty(props, 'cmis:creationDate'),
                      lastModificationDate: this.getSafeDateProperty(props, 'cmis:lastModificationDate'),
                      contentStreamLength: this.getSafeIntegerProperty(props, 'cmis:contentStreamLength')
                    };
                    children.push(cmisObject);
                  });
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
    return new Promise(async (resolve, reject) => {
      const xhr = new XMLHttpRequest();
      
      const browserUrl = `/core/browser/${repositoryId}/root`;
      console.log('CMIS DEBUG: createDocument using Browser binding URL:', browserUrl);
      xhr.open('POST', browserUrl, true);
      
      const documentName = properties.name || file.name;
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      console.log('CMIS DEBUG: createDocument set auth headers');
      
      console.log('CMIS DEBUG: createDocument file:', file.name, file.size, file.type);

      try {
        const formData = new FormData();
        formData.append('cmisaction', 'createDocument');
        formData.append('propertyId[0]', 'cmis:objectTypeId');
        formData.append('propertyValue[0]', 'cmis:document');
        formData.append('propertyId[1]', 'cmis:name');
        formData.append('propertyValue[1]', documentName + '_' + Date.now());
        formData.append('filename', file, documentName);
        formData.append('_charset_', 'UTF-8');
        
        console.log('CMIS DEBUG: createDocument using Browser binding with FormData');
        
        xhr.onreadystatechange = () => {
          console.log('CMIS DEBUG: createDocument readyState:', xhr.readyState, 'status:', xhr.status);
          if (xhr.readyState === 1) {
            console.log('CMIS DEBUG: XMLHttpRequest opened');
          } else if (xhr.readyState === 2) {
            console.log('CMIS DEBUG: XMLHttpRequest headers received');
          } else if (xhr.readyState === 3) {
            console.log('CMIS DEBUG: XMLHttpRequest loading');
          }
          if (xhr.readyState === 4) {
            if (xhr.status === 200 || xhr.status === 201) {
              try {
                console.log('CMIS DEBUG: createDocument success, response:', xhr.responseText);
                
                try {
                  const jsonResponse = JSON.parse(xhr.responseText);
                  console.log('CMIS DEBUG: Parsed JSON response:', jsonResponse);
                  
                  const objectId = jsonResponse.succinctProperties?.['cmis:objectId']?.value || 
                                 jsonResponse.properties?.['cmis:objectId']?.value || '';
                  const objectName = jsonResponse.succinctProperties?.['cmis:name']?.value || 
                                   jsonResponse.properties?.['cmis:name']?.value || documentName;
                  
                  const createdObject: CMISObject = {
                    id: objectId,
                    name: objectName,
                    objectType: 'cmis:document',
                    baseType: 'cmis:document',
                    properties: jsonResponse.properties || {},
                    allowableActions: []
                  };
                  
                  console.log('CMIS DEBUG: Created object from JSON:', createdObject);
                  resolve(createdObject);
                  return;
                } catch (jsonError) {
                  console.log('CMIS DEBUG: Not JSON, trying XML parsing...');
                }
                
                const parser = new DOMParser();
                const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
                const entry = xmlDoc.querySelector('entry');
                
                if (!entry) {
                  reject(new Error('No entry found in response and not valid JSON'));
                  return;
                }
                
                const id = entry.querySelector('id')?.textContent || '';
                const title = entry.querySelector('title')?.textContent || '';
                
                const createdObject: CMISObject = {
                  id: id.split('/').pop() || id,
                  name: title || documentName,
                  objectType: 'cmis:document',
                  baseType: 'cmis:document',
                  properties: {},
                  allowableActions: []
                };
                resolve(createdObject);
              } catch (e) {
                console.error('CMIS DEBUG: createDocument parse error:', e);
                reject(new Error('Invalid response format'));
              }
            } else {
              console.error('CMIS DEBUG: createDocument HTTP error:', xhr.status, xhr.statusText);
              console.error('CMIS DEBUG: createDocument response text:', xhr.responseText);
              console.error('CMIS DEBUG: createDocument response headers:', xhr.getAllResponseHeaders());
              reject(new Error(`HTTP ${xhr.status}: ${xhr.statusText} - ${xhr.responseText}`));
            }
          }
        };
        
        xhr.onerror = (event) => {
          console.error('CMIS DEBUG: createDocument network error:', event);
          console.error('CMIS DEBUG: createDocument xhr status:', xhr.status);
          console.error('CMIS DEBUG: createDocument xhr statusText:', xhr.statusText);
          reject(new Error('Network error'));
        };
        
        xhr.onload = () => {
          console.log('CMIS DEBUG: createDocument onload triggered, status:', xhr.status);
        };
        
        xhr.ontimeout = () => {
          console.error('CMIS DEBUG: createDocument timeout');
          reject(new Error('Request timeout'));
        };
        
        console.log('CMIS DEBUG: About to send XMLHttpRequest');
        xhr.send(formData);
      } catch (e) {
        console.error('CMIS DEBUG: createDocument file processing error:', e);
        reject(new Error('Failed to process file'));
      }
    });
  }

  async createFolder(repositoryId: string, parentId: string, name: string, properties: Record<string, any> = {}): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/folder/create`, true);
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
      xhr.send(JSON.stringify({ parentId, name, ...properties }));
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
      // Use AtomPub binding for type definitions
      xhr.open('GET', `/core/atom/${repositoryId}/types`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              // Parse AtomPub XML response for type definitions
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
              const entries = xmlDoc.getElementsByTagName('entry');
              
              const types: TypeDefinition[] = [];
              for (let i = 0; i < entries.length; i++) {
                const entry = entries[i];
                const title = entry.querySelector('title')?.textContent || 'Unknown Type';
                const id = entry.querySelector('id')?.textContent || '';
                
                // Extract type ID from the entry id or title
                const typeId = title; // Use title directly as it contains the correct type ID
                
                // Extract additional type information from CMIS type element
                const typeElement = entry.querySelector('cmisra\\:type, type');
                const baseTypeId = typeElement?.querySelector('cmis\\:baseId, baseId')?.textContent || 
                                 (typeId.startsWith('cmis:folder') ? 'cmis:folder' : 
                                  typeId.startsWith('cmis:document') ? 'cmis:document' : 
                                  typeId.startsWith('cmis:relationship') ? 'cmis:relationship' :
                                  typeId.startsWith('cmis:policy') ? 'cmis:policy' :
                                  typeId.startsWith('cmis:secondary') ? 'cmis:secondary' : 'cmis:item');
                
                const displayName = typeElement?.querySelector('cmis\\:displayName, displayName')?.textContent || title;
                const description = typeElement?.querySelector('cmis\\:description, description')?.textContent || (title + ' type definition');
                const creatable = typeElement?.querySelector('cmis\\:creatable, creatable')?.textContent === 'true';
                const fileable = typeElement?.querySelector('cmis\\:fileable, fileable')?.textContent === 'true';
                const queryable = typeElement?.querySelector('cmis\\:queryable, queryable')?.textContent === 'true';
                
                types.push({
                  id: typeId,
                  displayName: displayName,
                  description: description,
                  baseTypeId: baseTypeId,
                  creatable: creatable,
                  fileable: fileable,
                  queryable: queryable,
                  propertyDefinitions: {}
                });
              }
              
              console.log('CMIS DEBUG: getTypes parsed types:', types);
              resolve(types);
            } catch (e) {
              console.error('CMIS DEBUG: getTypes parse error:', e);
              reject(new Error('Failed to parse type definitions XML'));
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

  async getType(repositoryId: string, typeId: string): Promise<TypeDefinition> {
    console.log('CMIS DEBUG: getType called with repositoryId:', repositoryId, 'typeId:', typeId);
    
    const mockTypeDefinition: TypeDefinition = {
      id: typeId,
      displayName: typeId,
      description: `Mock type definition for ${typeId}`,
      baseTypeId: typeId.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
      parentTypeId: typeId.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
      creatable: true,
      fileable: true,
      queryable: true,
      propertyDefinitions: {
        'cmis:name': {
          id: 'cmis:name',
          displayName: 'Name',
          description: 'Name of the object',
          propertyType: 'string',
          cardinality: 'single',
          updatable: true,
          required: true,
          queryable: true
        },
        'cmis:objectId': {
          id: 'cmis:objectId',
          displayName: 'Object ID',
          description: 'Unique identifier of the object',
          propertyType: 'string',
          cardinality: 'single',
          updatable: false,
          required: true,
          queryable: true
        },
        'cmis:objectTypeId': {
          id: 'cmis:objectTypeId',
          displayName: 'Object Type ID',
          description: 'Type of the object',
          propertyType: 'string',
          cardinality: 'single',
          updatable: false,
          required: true,
          queryable: true
        }
      }
    };
    
    console.log('CMIS DEBUG: getType returning mock type definition:', mockTypeDefinition);
    return Promise.resolve(mockTypeDefinition);
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
                const title = entry.querySelector('title')?.textContent || '';
                
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
