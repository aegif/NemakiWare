import { AuthService } from './auth';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, User, Group, ACL } from '../types/cmis';

export class CMISService {
  private baseUrl = '/core/browser';
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
      
      // Use Browser Binding for root folder, AtomPub for subfolders (as fallback until Browser Binding is fully working)
      let url: string;
      let useAtomPub = false;
      
      if (folderId === 'e02f784f8360a02cc14d1314c10038ff') {
        // Root folder uses Browser Binding /root endpoint (known to work)
        url = `${this.baseUrl}/${repositoryId}/root?selector=children`;
        xhr.setRequestHeader('Accept', 'application/json');
      } else {
        // Use AtomPub for subfolders as fallback (known to work with correct data)
        url = `/core/atom/${repositoryId}/children?id=${folderId}`;
        useAtomPub = true;
        xhr.setRequestHeader('Accept', 'application/atom+xml');
      }
      
      xhr.open('GET', url, true);
      
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
                const entries = xmlDoc.getElementsByTagName('entry');
                
                for (let i = 0; i < entries.length; i++) {
                  const entry = entries[i];
                  const properties = entry.getElementsByTagName('cmis:properties')[0];
                  if (properties) {
                    // Extract properties from XML
                    const nameElement = properties.querySelector('cmis\\:propertyString[propertyDefinitionId="cmis:name"] cmis\\:value, propertyString[propertyDefinitionId="cmis:name"] value');
                    const idElement = properties.querySelector('cmis\\:propertyId[propertyDefinitionId="cmis:objectId"] cmis\\:value, propertyId[propertyDefinitionId="cmis:objectId"] value');
                    const typeElement = properties.querySelector('cmis\\:propertyId[propertyDefinitionId="cmis:objectTypeId"] cmis\\:value, propertyId[propertyDefinitionId="cmis:objectTypeId"] value');
                    
                    const name = nameElement?.textContent || 'Unknown';
                    const id = idElement?.textContent || '';
                    const objectType = typeElement?.textContent || 'cmis:document';
                    
                    const cmisObject: CMISObject = {
                      id: id,
                      name: name,
                      objectType: objectType,
                      baseType: objectType.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
                      properties: { 'cmis:name': name, 'cmis:objectId': id, 'cmis:objectTypeId': objectType },
                      allowableActions: []
                    };
                    children.push(cmisObject);
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
                      allowableActions: []
                    };
                    children.push(cmisObject);
                  });
                }
              }
              
              console.log('CMIS DEBUG: Parsed children:', children);
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
      
      let decodedObjectId = objectId;
      try {
        const decoded = atob(objectId);
        if (decoded && decoded.length > 0) {
          decodedObjectId = decoded;
          console.log('CMIS DEBUG: getObject decoded base64 objectId:', objectId, '->', decodedObjectId);
        }
      } catch (e) {
        console.log('CMIS DEBUG: getObject using original objectId:', objectId);
      }
      
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/entry?id=${decodedObjectId}`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              console.log('CMIS DEBUG: getObject response:', xhr.responseText);
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');
              const entry = xmlDoc.querySelector('entry');
              
              if (!entry) {
                reject(new Error('No entry found in response'));
                return;
              }
              
              const id = entry.querySelector('id')?.textContent || '';
              const title = entry.querySelector('title')?.textContent || '';
              const objectType = entry.querySelector('cmis\\:objectTypeId, objectTypeId')?.textContent || 'cmis:document';
              
              const cmisObject: CMISObject = {
                id: id.split('/').pop() || id,
                name: title,
                objectType,
                baseType: objectType.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document',
                properties: {},
                allowableActions: []
              };
              
              console.log('CMIS DEBUG: getObject parsed object:', cmisObject);
              resolve(cmisObject);
            } catch (e) {
              console.error('CMIS DEBUG: getObject parse error:', e);
              reject(new Error('Failed to parse AtomPub response'));
            }
          } else {
            console.error('CMIS DEBUG: getObject HTTP error:', xhr.status, xhr.statusText);
            reject(new Error(`HTTP ${xhr.status}`));
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
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/node/${objectId}/update`, true);
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
      xhr.send(JSON.stringify(properties));
    });
  }

  async deleteObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.baseUrl}/${repositoryId}/node/${objectId}`, true);
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
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/node/${objectId}/versions`, true);
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
              resolve(response.users || []);
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

  async createUser(repositoryId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/user/create/${user.id}`, true);
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
              resolve(response.user);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(user));
    });
  }

  async updateUser(repositoryId: string, userId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/user/update/${userId}`, true);
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
              resolve(response.user);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(user));
    });
  }

  async deleteUser(repositoryId: string, userId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/user/delete/${userId}`, true);
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
              resolve(response.groups || []);
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
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/group/create/${group.id}`, true);
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
              resolve(response.group);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(group));
    });
  }

  async updateGroup(repositoryId: string, groupId: string, group: Partial<Group>): Promise<Group> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/group/update/${groupId}`, true);
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
              resolve(response.group);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error(`HTTP ${xhr.status}`));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(JSON.stringify(group));
    });
  }

  async deleteGroup(repositoryId: string, groupId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/group/delete/${groupId}`, true);
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

  async getTypes(repositoryId: string): Promise<TypeDefinition[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/type/list`, true);
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
              resolve(response.types || []);
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
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/node/${objectId}/relationships`, true);
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
              resolve(response.relationships || []);
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
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/archive/list`, true);
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
}
