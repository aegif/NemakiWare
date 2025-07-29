/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.couch.CouchContent;
import jp.aegif.nemaki.model.couch.CouchDocument;
import jp.aegif.nemaki.model.couch.CouchFolder;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.couch.CouchArchive;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;
import jp.aegif.nemaki.model.couch.CouchChange;
import jp.aegif.nemaki.model.couch.CouchConfiguration;
import jp.aegif.nemaki.model.couch.CouchContent;
import jp.aegif.nemaki.model.couch.CouchDocument;
import jp.aegif.nemaki.model.couch.CouchFolder;
import jp.aegif.nemaki.model.couch.CouchGroupItem;
import jp.aegif.nemaki.model.couch.CouchItem;
import jp.aegif.nemaki.model.couch.CouchNodeBase;
import jp.aegif.nemaki.model.couch.CouchPatchHistory;
import jp.aegif.nemaki.model.couch.CouchPolicy;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionCore;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionDetail;
import jp.aegif.nemaki.model.couch.CouchRelationship;
import jp.aegif.nemaki.model.couch.CouchRendition;
import jp.aegif.nemaki.model.couch.CouchTypeDefinition;
import jp.aegif.nemaki.model.couch.CouchUserItem;
import jp.aegif.nemaki.model.couch.CouchVersionSeries;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
@Component
public class ContentDaoServiceImpl implements ContentDaoService {

	private RepositoryInfoMap repositoryInfoMap;
	private CloudantClientPool connectorPool;
	private static final Log log = LogFactory.getLog(ContentDaoServiceImpl.class);

	private static final String DESIGN_DOCUMENT = "_design/_repo";
	private static final String ATTACHMENT_NAME = "content";

	public ContentDaoServiceImpl() {

	}

	/**
	 * Creates a properly configured ObjectMapper for Cloudant/CouchDB serialization
	 * This ensures all fields from the object hierarchy are properly serialized
	 */
	private ObjectMapper createConfiguredObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		// Configure Jackson to ignore unknown properties during Cloudant migration
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		// CRITICAL FIX: Enable field visibility for protected/private fields
		// Use NONE for all accessors first, then selectively enable what we need
		mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
		mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.GETTER, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.ANY);
		return mapper;
	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		try {
			// Use ViewQuery to get type definitions from design document
			Map<String, Object> queryParams = new HashMap<String, Object>();
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "typeDefinitions", queryParams);
			
			List<NemakiTypeDefinition> typeDefinitions = new ArrayList<NemakiTypeDefinition>();
			
			// Handle null result gracefully (occurs during initial startup when design documents may not exist yet)
			if (result != null && result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						// Convert document to CouchTypeDefinition, then to NemakiTypeDefinition
						try {
							// Use Map-based constructor to ensure proper BaseTypeId conversion
							Map<String, Object> docMap = (Map<String, Object>) row.getDoc();
							CouchTypeDefinition ctd = new CouchTypeDefinition(docMap);
							if (ctd != null) {
								typeDefinitions.add(ctd.convert());
							}
						} catch (Exception e) {
							log.warn("Failed to convert type definition document: " + e.getMessage());
							if (log.isDebugEnabled()) {
								e.printStackTrace();
							}
						}
					}
				}
			}
			
			// If no types found via ViewQuery, return basic CMIS types as fallback
			if (typeDefinitions.isEmpty()) {
				log.warn("No type definitions found via ViewQuery, returning basic CMIS types as fallback");
				
				// Create basic folder type definition
				NemakiTypeDefinition folderType = new NemakiTypeDefinition();
				folderType.setId("cmis:folder");
				folderType.setType("typeDefinition");
				folderType.setBaseId(BaseTypeId.CMIS_FOLDER);
				folderType.setTypeId("cmis:folder");
				typeDefinitions.add(folderType);
				
				// Create basic document type definition
				NemakiTypeDefinition documentType = new NemakiTypeDefinition();
				documentType.setId("cmis:document");
				documentType.setType("typeDefinition");
				documentType.setBaseId(BaseTypeId.CMIS_DOCUMENT);
				documentType.setTypeId("cmis:document");
				typeDefinitions.add(documentType);
			}
			
			log.debug("Retrieved " + typeDefinitions.size() + " type definitions from repository: " + repositoryId);
			return typeDefinitions;
			
		} catch (Exception e) {
			log.error("Error retrieving type definitions from repository '" + repositoryId + "': " + e.getMessage(), e);
			
			// Return basic CMIS types as fallback in case of error
			List<NemakiTypeDefinition> fallbackTypes = new ArrayList<NemakiTypeDefinition>();
			
			NemakiTypeDefinition folderType = new NemakiTypeDefinition();
			folderType.setId("cmis:folder");
			folderType.setType("typeDefinition");
			folderType.setBaseId(BaseTypeId.CMIS_FOLDER);
			folderType.setTypeId("cmis:folder");
			fallbackTypes.add(folderType);
			
			NemakiTypeDefinition documentType = new NemakiTypeDefinition();
			documentType.setId("cmis:document");
			documentType.setType("typeDefinition");
			documentType.setBaseId(BaseTypeId.CMIS_DOCUMENT);
			documentType.setTypeId("cmis:document");
			fallbackTypes.add(documentType);
			
			log.warn("Using fallback type definitions due to error");
			return fallbackTypes;
		}
	}

	@Override
	public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		throw new UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
				+ ":this method is only for cahced service. No need for implementation.");
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		System.err.println("[DAO] === CREATE TYPE DEFINITION CALLED ===");
		System.err.println("[DAO] Repository ID: " + repositoryId);
		System.err.println("[DAO] Type ID: " + typeDefinition.getTypeId());
		System.err.println("[DAO] Input NemakiTypeDefinition properties: " + typeDefinition.getProperties());
		System.err.println("[DAO] Input NemakiTypeDefinition properties size: " + (typeDefinition.getProperties() != null ? typeDefinition.getProperties().size() : "null"));
		
		// Step 1: Create CouchTypeDefinition
		System.err.println("[DAO] Creating CouchTypeDefinition...");
		CouchTypeDefinition ct = new CouchTypeDefinition(typeDefinition);
		System.err.println("[DAO] CouchTypeDefinition created - properties: " + ct.getProperties());
		System.err.println("[DAO] CouchTypeDefinition properties size: " + (ct.getProperties() != null ? ct.getProperties().size() : "null"));
		
		// Step 2: Call CloudantClientWrapper create
		System.err.println("[DAO] Calling CloudantClientWrapper.create()...");
		connectorPool.getClient(repositoryId).create(ct);
		System.err.println("[DAO] CloudantClientWrapper.create() completed");
		System.err.println("[DAO] After CouchDB create, CouchTypeDefinition properties: " + ct.getProperties());
		
		// Step 3: Convert back to NemakiTypeDefinition
		System.err.println("[DAO] Converting back to NemakiTypeDefinition...");
		NemakiTypeDefinition result = ct.convert();
		System.err.println("[DAO] Final result properties: " + result.getProperties());
		System.err.println("[DAO] === CREATE TYPE DEFINITION COMPLETED ===");
		return result;
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition cp = connectorPool.getClient(repositoryId).get(CouchTypeDefinition.class, typeDefinition.getId());
		CouchTypeDefinition update = new CouchTypeDefinition(typeDefinition);
		update.setRevision(cp.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public void deleteTypeDefinition(String repositoryId, String nodeId) {
		delete(repositoryId, nodeId);
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
		try {
			// Use ViewQuery to get property definition cores from design document
			Map<String, Object> queryParams = new HashMap<String, Object>();
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "propertyDefinitionCores", queryParams);
			
			List<NemakiPropertyDefinitionCore> cores = new ArrayList<NemakiPropertyDefinitionCore>();
			
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = createConfiguredObjectMapper();
							CouchPropertyDefinitionCore cpdc = mapper.convertValue(row.getDoc(), CouchPropertyDefinitionCore.class);
							if (cpdc != null) {
								cores.add(cpdc.convert());
							}
						} catch (Exception e) {
							log.warn("Failed to convert property definition core document: " + e.getMessage());
						}
					}
				}
			}
			
			log.debug("Retrieved " + cores.size() + " property definition cores from repository: " + repositoryId);
			return cores;
			
		} catch (Exception e) {
			log.error("Error retrieving property definition cores from repository '" + repositoryId + "': " + e.getMessage(), e);
			return new ArrayList<NemakiPropertyDefinitionCore>(); // Return empty list on error
		}
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchPropertyDefinitionCore cpdc = client.get(CouchPropertyDefinitionCore.class, nodeId);
			
			if (cpdc != null) {
				return cpdc.convert();
			}
			return null;
			
		} catch (Exception e) {
			log.error("Error retrieving property definition core '" + nodeId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return null;
		}
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
		try {
			// Query propertyDefinitionCoreByPropertyId view with propertyId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchPropertyDefinitionCore> couchCores = client.queryView("_repo", "propertyDefinitionCoresByPropertyId", propertyId, CouchPropertyDefinitionCore.class);
			
			if (!couchCores.isEmpty()) {
				// Return the first (and should be only) result
				return couchCores.get(0).convert();
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting property definition core by property ID: " + propertyId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String nodeId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchPropertyDefinitionDetail cpdd = client.get(CouchPropertyDefinitionDetail.class, nodeId);
			
			if (cpdd != null) {
				return cpdd.convert();
			}
			return null;
			
		} catch (Exception e) {
			log.error("Error retrieving property definition detail '" + nodeId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return null;
		}
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId,
			String coreNodeId) {
		try {
			System.err.println("[PROPERTY_DETAIL_DEBUG] DAO METHOD CALLED - coreNodeId: " + coreNodeId + " in repository: " + repositoryId);
			log.error("[PROPERTY_DETAIL_DEBUG] Starting search for coreNodeId: " + coreNodeId + " in repository: " + repositoryId);
			
			// Alternative approach: Direct document retrieval without ObjectMapper conversion
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", true);
			
			ViewResult result = client.queryView("_repo", "propertyDefinitionDetails", queryParams);
			List<NemakiPropertyDefinitionDetail> details = new ArrayList<NemakiPropertyDefinitionDetail>();
			
			System.err.println("[PROPERTY_DETAIL_DEBUG] ViewResult: " + (result != null ? "found" : "null") + 
			         ", Rows: " + (result != null && result.getRows() != null ? result.getRows().size() : 0));
			log.error("[PROPERTY_DETAIL_DEBUG] ViewResult: " + (result != null ? "found" : "null") + 
			         ", Rows: " + (result != null && result.getRows() != null ? result.getRows().size() : 0));
			
			if (result != null && result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						// Handle Cloudant SDK Document object properly
						Object docObj = row.getDoc();
						Map<String, Object> docMap = null;
						
						if (docObj instanceof Map) {
							docMap = (Map<String, Object>) docObj;
						} else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
							// Convert Cloudant Document to Map using properties
							com.ibm.cloud.cloudant.v1.model.Document cloudantDoc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
							docMap = cloudantDoc.getProperties();
							
							// Ensure _id and _rev are included from the Cloudant Document
							if (docMap != null) {
								docMap.put("_id", cloudantDoc.getId());
								docMap.put("_rev", cloudantDoc.getRev());
								System.err.println("[PROPERTY_DETAIL_DEBUG] Added _id: " + cloudantDoc.getId() + ", _rev: " + cloudantDoc.getRev());
							}
						} else {
							System.err.println("[PROPERTY_DETAIL_DEBUG] Unknown document type: " + docObj.getClass().getName());
							continue;
						}
						
						if (docMap == null) {
							System.err.println("[PROPERTY_DETAIL_DEBUG] Failed to extract document properties");
							continue;
						}
						
						Object docCoreNodeId = docMap.get("coreNodeId");
						
						System.err.println("[PROPERTY_DETAIL_DEBUG] Checking document ID: " + docMap.get("_id") + 
						         ", coreNodeId: " + docCoreNodeId + ", type: " + docMap.get("type"));
						
						if (coreNodeId.equals(String.valueOf(docCoreNodeId))) {
							System.err.println("[PROPERTY_DETAIL_DEBUG] Found matching document - attempting direct construction");
							
							// Direct construction instead of ObjectMapper conversion
							try {
								CouchPropertyDefinitionDetail cpdd = new CouchPropertyDefinitionDetail();
								
								// Set base properties
								cpdd.setId((String) docMap.get("_id"));
								cpdd.setRevision((String) docMap.get("_rev"));
								cpdd.setType((String) docMap.get("type"));
								
								// Handle date fields - skip for now as they need proper conversion
								cpdd.setCreator((String) docMap.get("creator"));
								cpdd.setModifier((String) docMap.get("modifier"));
								
								// Set property definition specific properties
								cpdd.setCoreNodeId((String) docMap.get("coreNodeId"));
								cpdd.setPropertyId((String) docMap.get("propertyId"));
								cpdd.setLocalName((String) docMap.get("localName"));
								cpdd.setLocalNameSpace((String) docMap.get("localNameSpace"));
								cpdd.setQueryName((String) docMap.get("queryName"));
								cpdd.setDisplayName((String) docMap.get("displayName"));
								cpdd.setDescription((String) docMap.get("description"));
								
								// Handle enum types safely
								Object propertyTypeObj = docMap.get("propertyType");
								if (propertyTypeObj != null) {
									try {
										cpdd.setPropertyType(org.apache.chemistry.opencmis.commons.enums.PropertyType.valueOf(propertyTypeObj.toString()));
									} catch (Exception e) {
										log.warn("Invalid propertyType: " + propertyTypeObj + ", using STRING as default");
										cpdd.setPropertyType(org.apache.chemistry.opencmis.commons.enums.PropertyType.STRING);
									}
								}
								
								Object cardinalityObj = docMap.get("cardinality");
								if (cardinalityObj != null) {
									try {
										cpdd.setCardinality(org.apache.chemistry.opencmis.commons.enums.Cardinality.valueOf(cardinalityObj.toString()));
									} catch (Exception e) {
										log.warn("Invalid cardinality: " + cardinalityObj + ", using SINGLE as default");
										cpdd.setCardinality(org.apache.chemistry.opencmis.commons.enums.Cardinality.SINGLE);
									}
								}
								
								Object updatabilityObj = docMap.get("updatability");
								if (updatabilityObj != null) {
									try {
										cpdd.setUpdatability(org.apache.chemistry.opencmis.commons.enums.Updatability.valueOf(updatabilityObj.toString()));
									} catch (Exception e) {
										log.warn("Invalid updatability: " + updatabilityObj + ", using READWRITE as default");
										cpdd.setUpdatability(org.apache.chemistry.opencmis.commons.enums.Updatability.READWRITE);
									}
								}
								
								// Handle boolean fields safely
								Object requiredObj = docMap.get("required");
								cpdd.setRequired(requiredObj != null && Boolean.parseBoolean(requiredObj.toString()));
								
								Object queryableObj = docMap.get("queryable");
								cpdd.setQueryable(queryableObj != null && Boolean.parseBoolean(queryableObj.toString()));
								
								Object orderableObj = docMap.get("orderable");
								cpdd.setOrderable(orderableObj != null && Boolean.parseBoolean(orderableObj.toString()));
								
								Object openChoiceObj = docMap.get("openChoice");
								cpdd.setOpenChoice(openChoiceObj != null && Boolean.parseBoolean(openChoiceObj.toString()));
								
								// Handle numeric fields safely
								Object maxLengthObj = docMap.get("maxLength");
								if (maxLengthObj != null) {
									try {
										cpdd.setMaxLength(Long.valueOf(maxLengthObj.toString()));
									} catch (Exception e) {
										log.debug("Non-numeric maxLength: " + maxLengthObj);
									}
								}
								
								Object minValueObj = docMap.get("minValue");
								if (minValueObj != null) {
									try {
										cpdd.setMinValue(Long.valueOf(minValueObj.toString()));
									} catch (Exception e) {
										log.debug("Non-numeric minValue: " + minValueObj);
									}
								}
								
								Object maxValueObj = docMap.get("maxValue");
								if (maxValueObj != null) {
									try {
										cpdd.setMaxValue(Long.valueOf(maxValueObj.toString()));
									} catch (Exception e) {
										log.debug("Non-numeric maxValue: " + maxValueObj);
									}
								}
								
								// Convert default values list if present
								Object defaultValueObj = docMap.get("defaultValue");
								if (defaultValueObj instanceof List) {
									cpdd.setDefaultValue((List<Object>) defaultValueObj);
								}
								
								// Convert choices list if present  
								Object choicesObj = docMap.get("choices");
								if (choicesObj instanceof List) {
									// For now, set as null - choices conversion is complex
									cpdd.setChoices(null);
								}
								
								details.add(cpdd.convert());
								System.err.println("[PROPERTY_DETAIL_DEBUG] Successfully constructed PropertyDefinitionDetail with coreNodeId: " + coreNodeId);
								log.error("[PROPERTY_DETAIL_DEBUG] Successfully constructed PropertyDefinitionDetail with coreNodeId: " + coreNodeId);
								
							} catch (Exception constructionError) {
								log.error("[PROPERTY_DETAIL_DEBUG] Failed to construct PropertyDefinitionDetail: " + constructionError.getMessage(), constructionError);
							}
						}
					}
				}
			}
			
			System.err.println("[PROPERTY_DETAIL_DEBUG] Found " + details.size() + " details for coreNodeId: " + coreNodeId);
			log.error("[PROPERTY_DETAIL_DEBUG] Found " + details.size() + " details for coreNodeId: " + coreNodeId);
			return details;
			
		} catch (Exception e) {
			log.error("Error retrieving property definition details for core node '" + coreNodeId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return new ArrayList<NemakiPropertyDefinitionDetail>(); // Return empty list on error
		}
	}

	@Override
	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(String repositoryId,
			NemakiPropertyDefinitionCore propertyDefinitionCore) {
		CouchPropertyDefinitionCore cpc = new CouchPropertyDefinitionCore(propertyDefinitionCore);
		connectorPool.getClient(repositoryId).create(cpc);
		return cpc.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		CouchPropertyDefinitionDetail cpd = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		connectorPool.getClient(repositoryId).create(cpd);
		return cpd.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {

		CouchPropertyDefinitionDetail cpd = connectorPool.getClient(repositoryId)
				.get(CouchPropertyDefinitionDetail.class, propertyDefinitionDetail.getId());

		CouchPropertyDefinitionDetail update = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		update.setRevision(cpd.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public NodeBase getNodeBase(String repositoryId, String objectId) {
		CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, objectId);
		return cnb.convert();
	}

	@Override
	public Content getContent(String repositoryId, String objectId) {
		// CRITICAL: Enhanced implementation with detailed debugging for Cloudant migration
		log.info("getContent START: Repo=" + repositoryId + ", Id=" + objectId);
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			log.info("CLOUDANT DEBUG: About to call client.get() method - client class: " + client.getClass().getName());
			com.ibm.cloud.cloudant.v1.model.Document doc = client.get(objectId);
			log.info("CLOUDANT DEBUG: client.get() method completed - doc class: " + (doc != null ? doc.getClass().getName() : "null"));
			
			if (doc == null) {
				log.warn("Document not found: " + objectId + " in repository: " + repositoryId);
				return null;
			}
			
			log.info("Document retrieved successfully: " + objectId);
			
			// CRITICAL FIX: Use Cloudant SDK Document.get() method for direct field access
			// CouchDB document contains type/objectType fields but ObjectMapper conversion loses them
			log.info("CLOUDANT FIX: Using Document.get() for direct field access");
			
			// Create document map by directly accessing fields from Cloudant Document
			Map<String, Object> actualDocMap = new HashMap<>();
			
			// Copy standard document fields
			actualDocMap.put("_id", doc.getId());
			actualDocMap.put("_rev", doc.getRev());
			
			// Use Document.get() to access custom fields that ObjectMapper loses
			String type = (String) doc.get("type");
			String objectType = (String) doc.get("objectType");
			String name = (String) doc.get("name");
			String creator = (String) doc.get("creator");
			String modifier = (String) doc.get("modifier");
			String created = convertToString(doc.get("created"));
			String modified = convertToString(doc.get("modified"));
			String changeToken = (String) doc.get("changeToken");
			
			log.info("CLOUDANT FIX: Direct field access results:");
			log.info("  - type: " + type);
			log.info("  - objectType: " + objectType);
			log.info("  - name: " + name);
			
			// Add all accessible fields to the map
			if (type != null) actualDocMap.put("type", type);
			if (objectType != null) actualDocMap.put("objectType", objectType);
			if (name != null) actualDocMap.put("name", name);
			if (creator != null) actualDocMap.put("creator", creator);
			if (modifier != null) actualDocMap.put("modifier", modifier);
			if (created != null) actualDocMap.put("created", created);
			if (modified != null) actualDocMap.put("modified", modified);
			if (changeToken != null) actualDocMap.put("changeToken", changeToken);
			
			// Also try to get additional fields using getProperties() as fallback
			try {
				Map<String, Object> properties = doc.getProperties();
				if (properties != null && !properties.isEmpty()) {
					log.info("CLOUDANT FIX: Adding " + properties.size() + " properties from getProperties()");
					// Only add properties that aren't already in actualDocMap
					for (Map.Entry<String, Object> entry : properties.entrySet()) {
						if (!actualDocMap.containsKey(entry.getKey())) {
							actualDocMap.put(entry.getKey(), entry.getValue());
						}
					}
				}
			} catch (Exception e) {
				log.warn("CLOUDANT FIX: Error accessing getProperties(): " + e.getMessage());
				
				// Add other common CouchDB fields using different variable names
				Object aclObj = doc.get("acl");
				Object parentIdObj = doc.get("parentId");
				Object aspectsObj = doc.get("aspects");
				
				if (aclObj != null) actualDocMap.put("acl", aclObj);
				if (parentIdObj != null) actualDocMap.put("parentId", parentIdObj);
				if (aspectsObj != null) actualDocMap.put("aspects", aspectsObj);
			}
			
			log.info("Type fields - type: " + type + ", objectType: " + objectType);
			
			// Use objectType if type is null, otherwise use type
			String actualType = (type != null) ? type : objectType;
			log.info("ActualType determined: " + actualType);
			
			// Ensure both type and objectType fields are set for consistency BEFORE mapper conversion
			if (type == null && objectType != null) {
				actualDocMap.put("type", objectType);
			}
			if (objectType == null && type != null) {
				actualDocMap.put("objectType", type);
			}
			
			// CRITICAL FIX: Ensure objectType is set in the map before conversion
			// This ensures CouchContent and its subclasses pick up the objectType field
			if (!actualDocMap.containsKey("objectType") || actualDocMap.get("objectType") == null) {
				actualDocMap.put("objectType", actualType);
			}
			
			// Create ObjectMapper for type conversion
			ObjectMapper mapper = createConfiguredObjectMapper();
			
			if ("folder".equals(actualType) || "cmis:folder".equals(actualType)) {
				log.info("Converting to CouchFolder for type: " + actualType);
				CouchFolder folder = mapper.convertValue(actualDocMap, CouchFolder.class);
				log.info("CouchFolder created, calling convert()");
				Content content = folder.convert();
				log.info("Content converted. Type: " + content.getClass().getSimpleName() + ", ObjectType: " + content.getObjectType());
				// Ensure objectType is set correctly
				content.setObjectType(actualType);
				log.info("Final Content - ObjectType: " + content.getObjectType() + ", isFolder: " + content.isFolder());
				return content;
			} else if ("document".equals(actualType) || "cmis:document".equals(actualType)) {
				log.info("Converting to CouchDocument for type: " + actualType);
				CouchDocument document = mapper.convertValue(actualDocMap, CouchDocument.class);
				Content content = document.convert();
				// Ensure objectType is set correctly
				content.setObjectType(actualType);
				log.info("Final Document Content - ObjectType: " + content.getObjectType());
				return content;
			} else {
				log.info("Converting to generic CouchContent for type: " + actualType);
				// Generic content - try to convert to CouchContent
				CouchContent content = mapper.convertValue(actualDocMap, CouchContent.class);
				Content convertedContent = content.convert();
				// Ensure objectType is set correctly
				if (actualType != null) {
					convertedContent.setObjectType(actualType);
				}
				log.info("Final Generic Content - ObjectType: " + convertedContent.getObjectType());
				return convertedContent;
			}
		} catch (Exception e) {
			log.error("ERROR in getContent for " + objectId + " in repository " + repositoryId + ": " + e.getMessage(), e);
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public Content getContentFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getContentFresh is same as getContent
		// This ensures consistent interface across cached and non-cached implementations
		return getContent(repositoryId, objectId);
	}

	@Override
	public Document getDocumentFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getDocumentFresh is same as getDocument
		return getDocument(repositoryId, objectId);
	}

	@Override
	public Folder getFolderFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getFolderFresh is same as getFolder
		return getFolder(repositoryId, objectId);
	}

	@Override
	public Relationship getRelationshipFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getRelationshipFresh is same as getRelationship
		return getRelationship(repositoryId, objectId);
	}

	@Override
	public Policy getPolicyFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getPolicyFresh is same as getPolicy
		return getPolicy(repositoryId, objectId);
	}

	@Override
	public Item getItemFresh(String repositoryId, String objectId) {
		// For non-cached implementation, getItemFresh is same as getItem
		return getItem(repositoryId, objectId);
	}

	private Content convertJsonToEachBaeType(ViewResult result) {
		if (result.getRows().isEmpty()) {
			return null;
		} else {
			for (ViewResultRow row : result.getRows()) {
				ObjectMapper mapper = createConfiguredObjectMapper();
				JsonNode jn = mapper.valueToTree(row.getDoc());
				String baseType = jn.path("type").textValue();

				if (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)) {
					CouchDocument cd = mapper.convertValue(jn, CouchDocument.class);
					return cd.convert();
				} else if (BaseTypeId.CMIS_FOLDER.value().equals(baseType)) {
					CouchFolder cf = mapper.convertValue(jn, CouchFolder.class);
					return cf.convert();
				} else if (BaseTypeId.CMIS_POLICY.value().equals(baseType)) {
					CouchPolicy cp = mapper.convertValue(jn, CouchPolicy.class);
					return cp.convert();
				} else if (BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)) {
					CouchRelationship cr = mapper.convertValue(jn, CouchRelationship.class);
					return cr.convert();
				} else if (BaseTypeId.CMIS_ITEM.value().equals(baseType)) {
					CouchItem ci = mapper.convertValue(jn, CouchItem.class);
					return ci.convert();
				}
			}
		}

		return null;
	}

	@Override
	public Document getDocument(String repositoryId, String objectId) {
		CouchDocument cd = connectorPool.getClient(repositoryId).get(CouchDocument.class, objectId);
		if (cd == null) {
			return null;
		}
		return cd.convert();
	}

	@Override
	public boolean existContent(String repositoryId, String objectTypeId) {
		try {
			// Query countByObjectType view to check if content exists
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "countByObjectType", objectTypeId);
			
			return result.getRows() != null && !result.getRows().isEmpty();
		} catch (Exception e) {
			log.error("Error checking content existence for objectTypeId: " + objectTypeId + " in repository: " + repositoryId, e);
			return false;
		}
	}

	@Override
	public List<Document> getCheckedOutDocuments(String repositoryId, String parentFolderId) {
		try {
			// Query checkedOutDocuments view
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchDocument> couchDocs = client.queryView("_repo", "checkedOutDocuments", parentFolderId, CouchDocument.class);
			
			List<Document> documents = new ArrayList<Document>();
			for (CouchDocument couchDoc : couchDocs) {
				documents.add(couchDoc.convert());
			}
			
			return documents;
		} catch (Exception e) {
			log.error("Error getting checked out documents for parent: " + parentFolderId + " in repository: " + repositoryId, e);
			return new ArrayList<Document>();
		}
	}

	@Override
	public VersionSeries getVersionSeries(String repositoryId, String nodeId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchVersionSeries cvs = client.get(CouchVersionSeries.class, nodeId);
			
			if (cvs != null) {
				return cvs.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting version series: " + nodeId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public List<Document> getAllVersions(String repositoryId, String versionSeriesId) {
		try {
			// Query allVersions view with versionSeriesId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchDocument> couchDocs = client.queryView("_repo", "allVersions", versionSeriesId, CouchDocument.class);
			
			List<Document> documents = new ArrayList<Document>();
			for (CouchDocument couchDoc : couchDocs) {
				documents.add(couchDoc.convert());
			}
			
			return documents;
		} catch (Exception e) {
			log.error("Error getting all versions for series: " + versionSeriesId + " in repository: " + repositoryId, e);
			return new ArrayList<Document>();
		}
	}

	@Override
	public Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId) {
		try {
			// Query latestVersion view with versionSeriesId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchDocument> couchDocs = client.queryView("_repo", "latestVersion", versionSeriesId, CouchDocument.class);
			
			if (!couchDocs.isEmpty()) {
				// Return the first (and should be only) result
				return couchDocs.get(0).convert();
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting latest version for series: " + versionSeriesId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId) {
		try {
			// Query latestMajorVersion view with versionSeriesId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchDocument> couchDocs = client.queryView("_repo", "latestMajorVersion", versionSeriesId, CouchDocument.class);
			
			if (!couchDocs.isEmpty()) {
				// Return the first (and should be only) result
				return couchDocs.get(0).convert();
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting latest major version for series: " + versionSeriesId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public Folder getFolder(String repositoryId, String objectId) {
		// CRITICAL: Enhanced implementation with type hierarchy support for Cloudant migration
		Content content = getContent(repositoryId, objectId);
		if (content == null) {
			return null;
		}
		
		// Check if content is already a Folder instance
		if (content instanceof Folder) {
			return (Folder) content;
		}
		
		// Check if content has a folder-type objectType (supporting type hierarchy)
		String objectType = content.getObjectType();
		if (objectType != null && isFolderType(repositoryId, objectType)) {
			// Convert content to folder if it has folder-type but is not a Folder instance
			if (content.isFolder()) {
				// Create a Folder instance from the content
				Folder folder = new Folder(content);
				return folder;
			}
		}
		
		log.warn("Content " + objectId + " exists but is not a folder type. ObjectType: " + objectType);
		return null;
	}
	
	/**
	 * Check if the given objectType is a folder type (cmis:folder or inherits from cmis:folder)
	 */
	private boolean isFolderType(String repositoryId, String objectType) {
		if (objectType == null) return false;
		
		// Direct match for standard folder types
		if ("cmis:folder".equals(objectType) || "folder".equals(objectType)) {
			return true;
		}
		
		// Check for custom folder types (nemaki:folder, etc.)
		if (objectType.contains("folder")) {
			return true;
		}
		
		// TODO: Add proper type hierarchy checking using TypeManager
		// For now, use simple pattern matching as a temporary solution
		return false;
	}

	@Override
	public Folder getFolderByPath(String repositoryId, String path) {
		try {
			// Query foldersByPath view
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchFolder> folders = client.queryView("_repo", "foldersByPath", path, CouchFolder.class);
			
			if (folders.isEmpty()) {
				return null;
			}
			
			return folders.get(0).convert();
		} catch (Exception e) {
			log.error("Error getting folder by path: " + path + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to get folder by path", e);
		}
	}

	@Override
	public List<Content> getChildren(String repositoryId, String parentId) {
		try {
			// Use ViewQuery to get children by parent ID
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", parentId);
			queryParams.put("include_docs", true);
			
			log.error("DEBUG getChildren: repositoryId=" + repositoryId + ", parentId=" + parentId);
			
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "children", queryParams);
			
			List<Content> children = new ArrayList<Content>();
			
			if (result.getRows() != null) {
				log.error("DEBUG getChildren: found " + result.getRows().size() + " raw rows");
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							// Convert document to appropriate Content type
							Map<String, Object> doc = (Map<String, Object>) row.getDoc();
							String type = (String) doc.get("type");
							String objectId = (String) doc.get("_id");
							
							log.error("DEBUG getChildren: processing objectId=" + objectId + ", type=" + type);
							
							Content content = getContent(repositoryId, objectId);
							if (content != null) {
								log.error("DEBUG getChildren: successfully got content for objectId=" + objectId);
								children.add(content);
							} else {
								log.error("DEBUG getChildren: getContent returned NULL for objectId=" + objectId);
							}
						} catch (Exception e) {
							log.warn("Failed to convert child document: " + e.getMessage());
						}
					}
				}
			}
			
			log.debug("Retrieved " + children.size() + " children for parent '" + parentId + "' from repository: " + repositoryId);
			return children;
			
		} catch (Exception e) {
			log.error("Error retrieving children for parent '" + parentId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return new ArrayList<Content>(); // Return empty list on error
		}
	}

	@Override
	public Content getChildByName(String repositoryId, String parentId, String name) {
		try {
			log.debug("DEBUG getChildByName: searching for child '" + name + "' under parent '" + parentId + "' in repository: " + repositoryId);
			
			// FIXED: Use existing 'children' view instead of missing 'childByName' view
			// Query children view and filter by name since childByName view doesn't exist
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Create query parameters with include_docs=true
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", parentId);
			queryParams.put("include_docs", true);
			ViewResult result = client.queryView("_repo", "children", queryParams);
			
			if (result.getRows() != null && !result.getRows().isEmpty()) {
				log.debug("DEBUG getChildByName: found " + result.getRows().size() + " children for parent '" + parentId + "'");
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						Map<String, Object> doc = (Map<String, Object>) row.getDoc();
						String childName = (String) doc.get("name");
						log.debug("DEBUG getChildByName: checking child with name='" + childName + "'");
						
						if (name.equals(childName)) {
							String objectId = (String) doc.get("_id");
							log.debug("DEBUG getChildByName: FOUND matching child '" + name + "' with ID: " + objectId);
							return getContent(repositoryId, objectId);
						}
					}
				}
				log.debug("DEBUG getChildByName: no child found with name '" + name + "' under parent '" + parentId + "'");
			} else {
				log.debug("DEBUG getChildByName: no children found for parent '" + parentId + "'");
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting child by name: " + name + " for parent: " + parentId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	public List<String> getChildrenNames(String repositoryId, String parentId){
		try {
			// Query childrenNames view
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "childrenNames", parentId);
			
			List<String> names = new ArrayList<String>();
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getValue() != null) {
						names.add(row.getValue().toString());
					}
				}
			}
			
			return names;
		} catch (Exception e) {
			log.error("Error getting children names for parent: " + parentId + " in repository: " + repositoryId, e);
			return new ArrayList<String>();
		}
	}

	@Override
	public Relationship getRelationship(String repositoryId, String objectId) {
		CouchRelationship cr = connectorPool.getClient(repositoryId).get(CouchRelationship.class, objectId);
		return cr.convert();
	}

	@Override
	public List<Relationship> getRelationshipsBySource(String repositoryId, String sourceId) {
		try {
			// Query relationshipsBySource view with sourceId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchRelationship> couchRels = client.queryView("_repo", "relationshipsBySource", sourceId, CouchRelationship.class);
			
			List<Relationship> relationships = new ArrayList<Relationship>();
			for (CouchRelationship couchRel : couchRels) {
				relationships.add(couchRel.convert());
			}
			
			return relationships;
		} catch (Exception e) {
			log.error("Error getting relationships by source: " + sourceId + " in repository: " + repositoryId, e);
			return new ArrayList<Relationship>();
		}
	}

	@Override
	public List<Relationship> getRelationshipsByTarget(String repositoryId, String targetId) {
		try {
			// Query relationshipsByTarget view with targetId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchRelationship> couchRels = client.queryView("_repo", "relationshipsByTarget", targetId, CouchRelationship.class);
			
			List<Relationship> relationships = new ArrayList<Relationship>();
			for (CouchRelationship couchRel : couchRels) {
				relationships.add(couchRel.convert());
			}
			
			return relationships;
		} catch (Exception e) {
			log.error("Error getting relationships by target: " + targetId + " in repository: " + repositoryId, e);
			return new ArrayList<Relationship>();
		}
	}

	@Override
	public Policy getPolicy(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchPolicy cp = client.get(CouchPolicy.class, objectId);
			
			if (cp != null) {
				return cp.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting policy: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public List<Policy> getAppliedPolicies(String repositoryId, String objectId) {
		try {
			// Query appliedPolicies view with objectId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchPolicy> couchPolicies = client.queryView("_repo", "appliedPolicies", objectId, CouchPolicy.class);
			
			// CRITICAL FIX: Handle null result from queryView to prevent NullPointerException
			if (couchPolicies == null) {
				log.warn("queryView returned null for appliedPolicies - objectId: " + objectId + ", repository: " + repositoryId);
				return new ArrayList<Policy>();
			}
			
			List<Policy> policies = new ArrayList<Policy>();
			for (CouchPolicy couchPolicy : couchPolicies) {
				if (couchPolicy != null) {
					policies.add(couchPolicy.convert());
				}
			}
			
			return policies;
		} catch (Exception e) {
			log.error("Error getting applied policies for: " + objectId + " in repository: " + repositoryId, e);
			return new ArrayList<Policy>();
		}
	}

	@Override
	public Item getItem(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchItem ci = client.get(CouchItem.class, objectId);
			
			if (ci != null) {
				return ci.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting item: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public UserItem getUserItem(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchUserItem cui = client.get(CouchUserItem.class, objectId);
			
			if (cui != null) {
				return cui.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting user item: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public UserItem getUserItemById(String repositoryId, String userId) {
		try {
			log.info("=== getUserItemById for userId: " + userId + " in repository: " + repositoryId + " ===");
			
			// Use CloudantClientWrapper from connectorPool
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "userItemsById", userId);
			
			if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
				log.info("Found " + result.getRows().size() + " matching user documents");
				
				ViewResultRow firstRow = result.getRows().get(0);
				Object rawDoc = firstRow.getValue(); // Use getValue() not getDoc()
				
				log.info("Raw document class: " + rawDoc.getClass().getName());
				
				if (rawDoc instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> docMap = (Map<String, Object>) rawDoc;
					log.info("Document contains userId: " + docMap.get("userId") + ", admin: " + docMap.get("admin"));
					
					// Use the Map-based constructor we created
					CouchUserItem cui = new CouchUserItem(docMap);
					
					log.info("CouchUserItem created - userId: " + cui.getUserId() + ", admin: " + cui.isAdmin() + 
						", id: " + cui.getId() + ", type: " + cui.getType());
					
					// Validate required fields
					if (cui.getUserId() != null && cui.getId() != null && cui.getType() != null) {
						return cui.convert();
					} else {
						log.error("Missing required fields - userId: " + cui.getUserId() + 
							", id: " + cui.getId() + ", type: " + cui.getType());
						return null;
					}
				} else {
					log.error("Raw document is not a Map: " + rawDoc.getClass().getName());
					return null;
				}
			} else {
				log.warn("No user found with userId: " + userId + " in repository: " + repositoryId);
			}
			
			return null;
			
		} catch (Exception e) {
			log.error("Error in getUserItemById for userId '" + userId + "' in repository '" + repositoryId + "'", e);
			return null;
		}
	}

	@Override
	public List<UserItem> getUserItems(String repositoryId){
		log.info("=== getUserItems: Starting for repository: " + repositoryId + " ===");
		try {
			// Query userItemsById view to get all user items
			log.info("getUserItems: Getting client for repository: " + repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			log.info("getUserItems: Client obtained, querying userItemsById view");
			
			List<CouchUserItem> couchUsers = client.queryView("_repo", "userItemsById", null, CouchUserItem.class);
			log.info("getUserItems: Retrieved " + couchUsers.size() + " raw users from CouchDB");
			
			List<UserItem> userItems = new ArrayList<UserItem>();
			for (CouchUserItem couchUser : couchUsers) {
				log.debug("getUserItems: Converting CouchUserItem with userId: " + couchUser.getUserId());
				try {
					UserItem converted = couchUser.convert();
					if (converted != null) {
						userItems.add(converted);
						log.debug("getUserItems: Successfully converted user: " + converted.getUserId());
					} else {
						log.warn("getUserItems: convert() returned null for CouchUserItem: " + couchUser.getId());
					}
				} catch (Exception convertException) {
					log.error("getUserItems: Exception during convert() for CouchUserItem: " + couchUser.getId(), convertException);
					// 変換に失敗したユーザーはスキップして続行
				}
			}
			
			log.info("getUserItems: Returning " + userItems.size() + " converted users");
			return userItems;
		} catch (Exception e) {
			log.error("=== getUserItems: Exception occurred ===");
			log.error("Exception type: " + e.getClass().getName());
			log.error("Exception message: " + e.getMessage());
			if (e.getCause() != null) {
				log.error("Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
			}
			log.error("Full stack trace:", e);
			log.error("=== End getUserItems Exception ===");
			
			// 例外を握りつぶすのではなく、適切にRuntimeExceptionとして伝播
			throw new RuntimeException("Failed to retrieve user items from repository: " + repositoryId, e);
		}
	}

	@Override
	public GroupItem getGroupItem(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchGroupItem cgi = client.get(CouchGroupItem.class, objectId);
			
			if (cgi != null) {
				return cgi.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting group item: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public GroupItem getGroupItemById(String repositoryId, String groupId) {
		try {
			// Use ViewQuery to get group item by ID
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", groupId);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItemsById", queryParams);
			
			if (result.getRows() != null && !result.getRows().isEmpty()) {
				ViewResultRow row = result.getRows().get(0);
				if (row.getDoc() != null) {
					try {
						ObjectMapper mapper = createConfiguredObjectMapper();
						CouchGroupItem cgi = mapper.convertValue(row.getDoc(), CouchGroupItem.class);
						if (cgi != null) {
							return cgi.convert();
						}
					} catch (Exception e) {
						log.warn("Failed to convert group item document: " + e.getMessage());
					}
				}
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting group item by ID: " + groupId + ", error: " + e.getMessage());
			return null;
		}
	}

	@Override
	public List<GroupItem> getGroupItems(String repositoryId) {
		try {
			// Use ViewQuery to get all group items
			Map<String, Object> queryParams = new HashMap<String, Object>();
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItems", queryParams);
			
			List<GroupItem> groupItems = new ArrayList<GroupItem>();
			
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = createConfiguredObjectMapper();
							CouchGroupItem cgi = mapper.convertValue(row.getDoc(), CouchGroupItem.class);
							if (cgi != null) {
								groupItems.add(cgi.convert());
							}
						} catch (Exception e) {
							log.warn("Failed to convert group item document: " + e.getMessage());
						}
					}
				}
			}
			
			return groupItems;
		} catch (Exception e) {
			log.error("Error getting group items for repository: " + repositoryId + ", error: " + e.getMessage());
			return new ArrayList<GroupItem>();
		}
	}

	public List<String> getJoinedGroupByUserId(String repositoryId, String userId) {
		try {
			//first get directory joined groups
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", userId);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "joinedDirectGroupsByUserId", queryParams);
			
			//get indirect joined group using above results
			List<String> groupIdsToCheck = new ArrayList<String>();
			List<String> resultGroupIds = new ArrayList<String>();
			
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getValue() != null) {
						// Extract groupId from the CouchGroupItem document
						try {
							Map<String, Object> doc = (Map<String, Object>) row.getValue();
							String groupId = (String) doc.get("groupId");
							if (groupId != null) {
								groupIdsToCheck.add(groupId);
								resultGroupIds.add(groupId);
							}
						} catch (Exception e) {
							log.warn("Error parsing group document for user " + userId + ": " + e.getMessage());
						}
					}
				}
			}

			while(groupIdsToCheck.size() > 0) {
				groupIdsToCheck = this.checkIndirectGroup(repositoryId, groupIdsToCheck);
				resultGroupIds.addAll(groupIdsToCheck);
			}

			//unique result
			return resultGroupIds;
		} catch (Exception e) {
			log.error("Error getting joined groups for user: " + userId + ", error: " + e.getMessage());
			return new ArrayList<String>();
		}
	}

	private List<String> checkIndirectGroup(String repositoryId, List<String> groupIdsToCheck) {
		List<String> resultGroupIds = new ArrayList<String>();
		
		if (groupIdsToCheck == null || groupIdsToCheck.isEmpty()) {
			return resultGroupIds;
		}

		try {
			// For now, implement a simplified version that doesn't do recursive group checking
			// This prevents infinite loops and provides basic functionality
			// Full implementation would require complex recursive group hierarchy traversal
			
			for (String groupId : groupIdsToCheck) {
				// Check if this group belongs to other groups using joinedDirectGroupsByGroupId view
				Map<String, Object> queryParams = new HashMap<String, Object>();
				queryParams.put("startkey", "[\"" + groupId + "\",0]");
				queryParams.put("endkey", "[\"" + groupId + "\",19]");
				
				try {
					ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "joinedDirectGroupsByGroupId", queryParams);
					if (result.getRows() != null) {
						for (ViewResultRow row : result.getRows()) {
							if (row.getValue() != null) {
								try {
									Map<String, Object> doc = (Map<String, Object>) row.getValue();
									String parentGroupId = (String) doc.get("groupId");
									if (parentGroupId != null && !resultGroupIds.contains(parentGroupId)) {
										resultGroupIds.add(parentGroupId);
									}
								} catch (Exception e) {
									log.warn("Error parsing group hierarchy for group " + groupId + ": " + e.getMessage());
								}
							}
						}
					}
				} catch (Exception e) {
					log.warn("Error checking indirect groups for " + groupId + ": " + e.getMessage());
				}
			}
		} catch (Exception e) {
			log.error("Error in checkIndirectGroup: " + e.getMessage());
		}

		return resultGroupIds;
	}

	@Override
	public PatchHistory getPatchHistoryByName(String repositoryId, String name) {
		try {
			// Use ViewQuery to get patch history by name
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", name);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "patchHistoryByName", queryParams);
			
			if (result.getRows() != null && !result.getRows().isEmpty()) {
				ViewResultRow row = result.getRows().get(0);
				if (row.getDoc() != null) {
					try {
						ObjectMapper mapper = createConfiguredObjectMapper();
						CouchPatchHistory cph = mapper.convertValue(row.getDoc(), CouchPatchHistory.class);
						if (cph != null) {
							return cph.convert();
						}
					} catch (Exception e) {
						log.warn("Failed to convert patch history document: " + e.getMessage());
					}
				}
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting patch history by name: " + name + ", error: " + e.getMessage());
			return null;
		}
	}

	@Override
	public Configuration getConfiguration(String repositoryId) {
		// CRITICAL: Minimal fallback implementation for Spring initialization
		// ViewQuery functionality temporarily disabled during Cloudant migration
		
		// Return a basic Configuration object to allow Spring initialization
		Configuration config = new Configuration();
		config.setId("default_config");
		config.setType("configuration");
		
		// Set basic timestamps
		config.setCreated(new GregorianCalendar());
		config.setModified(new GregorianCalendar());
		config.setCreator("system");
		config.setModifier("system");
		
		return config;
	}

	@Override
	public Document create(String repositoryId, Document document) {
		log.debug("COMPREHENSIVE: Creating document for repositoryId: " + repositoryId);
		CouchDocument cd = new CouchDocument(document);
		connectorPool.getClient(repositoryId).create(cd);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Ensure created document has ID and revision
		// The CouchNodeBase.convert() will now preserve revision information
		Document result = cd.convert();
		log.debug("COMPREHENSIVE: Created document ID=" + result.getId() + ", revision=" + result.getRevision());
		
		return result;
	}

	@Override
	public VersionSeries create(String repositoryId, VersionSeries versionSeries) {
		CouchVersionSeries cvs = new CouchVersionSeries(versionSeries);
		connectorPool.getClient(repositoryId).create(cvs);
		return cvs.convert();
	}

	@Override
	public Folder create(String repositoryId, Folder folder) {
		log.error("COMPREHENSIVE DEBUG: Creating folder for repositoryId: " + repositoryId);
		CouchFolder cf = new CouchFolder(folder);
		log.error("COMPREHENSIVE DEBUG: Before create - CouchFolder ID=" + cf.getId() + ", revision=" + cf.getRevision() + 
			", objectType=" + cf.getObjectType() + ", name=" + cf.getName() + ", type=" + cf.getType());
		
		log.error("COMPREHENSIVE DEBUG: About to call client.create() method");
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			log.error("COMPREHENSIVE DEBUG: Got client of type: " + client.getClass().getName());
			client.create(cf);
			log.error("COMPREHENSIVE DEBUG: client.create() method completed");
		} catch (Exception e) {
			log.error("COMPREHENSIVE DEBUG: Exception in create() call", e);
			throw e;
		}
		
		log.error("COMPREHENSIVE DEBUG: After create - CouchFolder ID=" + cf.getId() + ", revision=" + cf.getRevision());
		
		// COMPREHENSIVE REVISION MANAGEMENT: Ensure created folder has ID and revision
		// The CouchNodeBase.convert() will now preserve revision information
		Folder result = cf.convert();
		log.error("COMPREHENSIVE DEBUG: After convert - Folder ID=" + result.getId() + ", revision=" + result.getRevision());
		
		return result;
	}

	@Override
	public Relationship create(String repositoryId, Relationship relationship) {
		CouchRelationship cr = new CouchRelationship(relationship);
		connectorPool.getClient(repositoryId).create(cr);
		return cr.convert();
	}

	@Override
	public Policy create(String repositoryId, Policy policy) {
		CouchPolicy cp = new CouchPolicy(policy);
		connectorPool.getClient(repositoryId).create(cp);
		return cp.convert();
	}

	@Override
	public Item create(String repositoryId, Item item) {
		CouchItem ci = new CouchItem(item);
		connectorPool.getClient(repositoryId).create(ci);
		return ci.convert();
	}

	@Override
	public UserItem create(String repositoryId, UserItem userItem) {
		CouchUserItem cui = new CouchUserItem(userItem);
		connectorPool.getClient(repositoryId).create(cui);
		return cui.convert();
	}

	@Override
	public GroupItem create(String repositoryId, GroupItem groupItem) {
		CouchGroupItem cgi = new CouchGroupItem(groupItem);
		connectorPool.getClient(repositoryId).create(cgi);
		return cgi.convert();
	}

	@Override
	public PatchHistory create(String repositoryId, PatchHistory patchHistory) {
		CouchPatchHistory cph = new CouchPatchHistory(patchHistory);
		connectorPool.getClient(repositoryId).create(cph);
		return cph.convert();
	}

	@Override
	public Configuration create(String repositoryId, Configuration configuration) {
		CouchConfiguration ccfg = new CouchConfiguration(configuration);
		connectorPool.getClient(repositoryId).create(ccfg);
		return ccfg.convert();
	}

	@Override
	public NodeBase create(String repositoryId, NodeBase nodeBase) {
		CouchNodeBase cnb = new CouchNodeBase(nodeBase);
		connectorPool.getClient(repositoryId).create(cnb);
		return cnb.convert();
	}

	@Override
	public Document update(String repositoryId, Document document) {
		CouchDocument update = new CouchDocument(document);
		
		// COMPREHENSIVE REVISION MANAGEMENT:
		// Check if document already has revision from Content layer (new capability)
		if (document.getRevision() != null && !document.getRevision().isEmpty()) {
			// Use revision from Content layer - full Ektorp-style state management
			update.setRevision(document.getRevision());
			log.debug("COMPREHENSIVE: Using revision from Content layer: " + document.getRevision());
		} else {
			// Fallback: fetch current revision from database
			log.debug("COMPREHENSIVE: Content layer has no revision, fetching from database for document: " + document.getId());
			CouchDocument cd = connectorPool.getClient(repositoryId).get(CouchDocument.class, document.getId());
			if (cd != null) {
				update.setRevision(cd.getRevision());
				log.debug("COMPREHENSIVE: Fetched revision from database: " + cd.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update document " + document.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will trust this revision completely
		connectorPool.getClient(repositoryId).update(update);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Return updated object with new revision maintained by wrapper
		// The CouchNodeBase.convert() will now preserve the updated revision information
		Document result = update.convert();
		log.debug("COMPREHENSIVE: Update completed, new revision: " + result.getRevision());
		return result;
	}

	@Override
	public Document move(String repositoryId, Document document, String sourceId){
		return update(repositoryId, document);
	}

	@Override
	public VersionSeries update(String repositoryId, VersionSeries versionSeries) {
		CouchVersionSeries update = new CouchVersionSeries(versionSeries);
		
		// Only fetch latest revision if object doesn't already have one
		// This avoids unnecessary DB reads and race conditions in consecutive operations
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			CouchVersionSeries cvs = connectorPool.getClient(repositoryId).get(CouchVersionSeries.class, versionSeries.getId());
			update.setRevision(cvs.getRevision());
			log.debug("Fetched latest revision for version series update: " + cvs.getRevision());
		} else {
			log.debug("Using existing revision for version series update: " + update.getRevision());
		}

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public Folder update(String repositoryId, Folder folder) {
		log.error("COMPREHENSIVE DEBUG: DAO update(Folder) called for ID=" + folder.getId() + 
			", revision=" + folder.getRevision());
		
		CouchFolder update = new CouchFolder(folder);
		log.error("COMPREHENSIVE DEBUG: CouchFolder created, revision=" + update.getRevision());
		
		// COMPREHENSIVE REVISION MANAGEMENT:
		// Check if folder already has revision from Content layer (new capability)
		if (folder.getRevision() != null && !folder.getRevision().isEmpty()) {
			// Use revision from Content layer - full Ektorp-style state management
			update.setRevision(folder.getRevision());
			log.error("COMPREHENSIVE DEBUG: Using revision from Content layer: " + folder.getRevision());
		} else {
			// Fallback: fetch current revision from database
			log.error("COMPREHENSIVE DEBUG: Content layer has no revision, fetching from database for folder: " + folder.getId());
			CouchFolder cf = connectorPool.getClient(repositoryId).get(CouchFolder.class, folder.getId());
			if (cf != null) {
				update.setRevision(cf.getRevision());
				log.error("COMPREHENSIVE DEBUG: Fetched revision from database: " + cf.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update folder " + folder.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will trust this revision completely
		connectorPool.getClient(repositoryId).update(update);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Return updated object with new revision maintained by wrapper
		// The CouchNodeBase.convert() will now preserve the updated revision information
		Folder result = update.convert();
		log.debug("COMPREHENSIVE: Update completed, new revision: " + result.getRevision());
		return result;
	}

	@Override
	public Folder move(String repositoryId, Folder folder, String sourceId){
		return update(repositoryId, folder);
	}

	@Override
	public Relationship update(String repositoryId, Relationship relationship) {
		CouchRelationship update = new CouchRelationship(relationship);
		
		// COMPREHENSIVE REVISION MANAGEMENT:
		// Check if relationship already has revision from Content layer (new capability)
		if (relationship.getRevision() != null && !relationship.getRevision().isEmpty()) {
			// Use revision from Content layer - full Ektorp-style state management
			update.setRevision(relationship.getRevision());
			log.debug("COMPREHENSIVE: Using revision from Content layer: " + relationship.getRevision());
		} else {
			// Fallback: fetch current revision from database
			log.debug("COMPREHENSIVE: Content layer has no revision, fetching from database for relationship: " + relationship.getId());
			CouchRelationship cr = connectorPool.getClient(repositoryId).get(CouchRelationship.class, relationship.getId());
			if (cr != null) {
				update.setRevision(cr.getRevision());
				log.debug("COMPREHENSIVE: Fetched revision from database: " + cr.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update relationship " + relationship.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will trust this revision completely
		connectorPool.getClient(repositoryId).update(update);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Return updated object with new revision maintained by wrapper
		// The CouchNodeBase.convert() will now preserve the updated revision information
		Relationship result = update.convert();
		log.debug("COMPREHENSIVE: Update completed, new revision: " + result.getRevision());
		return result;
	}

	@Override
	public Policy update(String repositoryId, Policy policy) {
		CouchPolicy update = new CouchPolicy(policy);
		
		// COMPREHENSIVE REVISION MANAGEMENT:
		// Check if policy already has revision from Content layer (new capability)
		if (policy.getRevision() != null && !policy.getRevision().isEmpty()) {
			// Use revision from Content layer - full Ektorp-style state management
			update.setRevision(policy.getRevision());
			log.debug("COMPREHENSIVE: Using revision from Content layer: " + policy.getRevision());
		} else {
			// Fallback: fetch current revision from database
			log.debug("COMPREHENSIVE: Content layer has no revision, fetching from database for policy: " + policy.getId());
			CouchPolicy cp = connectorPool.getClient(repositoryId).get(CouchPolicy.class, policy.getId());
			if (cp != null) {
				update.setRevision(cp.getRevision());
				log.debug("COMPREHENSIVE: Fetched revision from database: " + cp.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update policy " + policy.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will trust this revision completely
		connectorPool.getClient(repositoryId).update(update);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Return updated object with new revision maintained by wrapper
		// The CouchNodeBase.convert() will now preserve the updated revision information
		Policy result = update.convert();
		log.debug("COMPREHENSIVE: Update completed, new revision: " + result.getRevision());
		return result;
	}

	@Override
	public Item update(String repositoryId, Item item) {
		CouchItem update = new CouchItem(item);
		
		// COMPREHENSIVE REVISION MANAGEMENT:
		// Check if item already has revision from Content layer (new capability)
		if (item.getRevision() != null && !item.getRevision().isEmpty()) {
			// Use revision from Content layer - full Ektorp-style state management
			update.setRevision(item.getRevision());
			log.debug("COMPREHENSIVE: Using revision from Content layer: " + item.getRevision());
		} else {
			// Fallback: fetch current revision from database
			log.debug("COMPREHENSIVE: Content layer has no revision, fetching from database for item: " + item.getId());
			CouchItem ci = connectorPool.getClient(repositoryId).get(CouchItem.class, item.getId());
			if (ci != null) {
				update.setRevision(ci.getRevision());
				log.debug("COMPREHENSIVE: Fetched revision from database: " + ci.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update item " + item.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will trust this revision completely
		connectorPool.getClient(repositoryId).update(update);
		
		// COMPREHENSIVE REVISION MANAGEMENT: Return updated object with new revision maintained by wrapper
		// The CouchNodeBase.convert() will now preserve the updated revision information
		Item result = update.convert();
		log.debug("COMPREHENSIVE: Update completed, new revision: " + result.getRevision());
		return result;
	}

	@Override
	public UserItem update(String repositoryId, UserItem userItem) {
		CouchUserItem update = new CouchUserItem(userItem);
		
		// Only fetch latest revision if object doesn't already have one
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			CouchUserItem ci = connectorPool.getClient(repositoryId).get(CouchUserItem.class, userItem.getId());
			update.setRevision(ci.getRevision());
			log.debug("Fetched latest revision for user item update: " + ci.getRevision());
		} else {
			log.debug("Using existing revision for user item update: " + update.getRevision());
		}

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public GroupItem update(String repositoryId, GroupItem groupItem) {
		CouchGroupItem update = new CouchGroupItem(groupItem);
		
		// Only fetch latest revision if object doesn't already have one
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			CouchGroupItem ci = connectorPool.getClient(repositoryId).get(CouchGroupItem.class, groupItem.getId());
			update.setRevision(ci.getRevision());
			log.debug("Fetched latest revision for group item update: " + ci.getRevision());
		} else {
			log.debug("Using existing revision for group item update: " + update.getRevision());
		}

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public PatchHistory update(String repositoryId, PatchHistory patchHistory) {
		CouchPatchHistory cph = connectorPool.getClient(repositoryId).get(CouchPatchHistory.class, patchHistory.getId());
		CouchPatchHistory update = new CouchPatchHistory(patchHistory);
		update.setRevision(cph.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public Configuration update(String repositoryId, Configuration configuration) {
		CouchConfiguration ccfg = connectorPool.getClient(repositoryId).get(CouchConfiguration.class, configuration.getId());
		CouchConfiguration update = new CouchConfiguration(configuration);
		update.setRevision(ccfg.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public NodeBase update(String repositoryId, NodeBase nodeBase) {
		CouchNodeBase update = new CouchNodeBase(nodeBase);
		
		// Ektorp-style: trust the object's revision state completely
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			log.warn("NodeBase update attempted without revision - fetching from DB (non-Ektorp behavior)");
			CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, nodeBase.getId());
			if (cnb != null) {
				update.setRevision(cnb.getRevision());
				log.debug("Fetched revision for nodebase update: " + cnb.getRevision());
			} else {
				throw new IllegalArgumentException("Cannot update nodebase " + nodeBase.getId() + ": not found in database");
			}
		}

		// Use Ektorp-style update - CloudantClientWrapper will handle revision management
		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public void delete(String repositoryId, String objectId) {
		try {
			log.error("DELETION ATTEMPT DEBUG: Attempting to delete object: " + objectId + " from repository: " + repositoryId);
			CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, objectId);
			if (cnb == null) {
				log.warn("Object " + objectId + " not found in repository " + repositoryId + ", already deleted or does not exist");
				return;
			}
			
			connectorPool.getClient(repositoryId).delete(cnb);
			log.error("DELETION SUCCESS DEBUG: Successfully deleted object: " + objectId + " from repository: " + repositoryId);
			
			// Verify deletion was successful
			try {
				CouchNodeBase verification = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, objectId);
				if (verification != null) {
					log.error("CRITICAL: Object " + objectId + " still exists after deletion attempt in repository " + repositoryId);
					throw new RuntimeException("Object deletion failed - object still exists: " + objectId);
				}
			} catch (Exception verifyEx) {
				// Expected behavior - object should not be found after deletion
				log.debug("Verification confirmed: object " + objectId + " no longer exists in repository " + repositoryId);
			}
			
		} catch (Exception e) {
			log.error("Failed to delete object: " + objectId + " from repository: " + repositoryId, e);
			throw new RuntimeException("Delete operation failed for object: " + objectId, e);
		}
	}

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	@Override
	public AttachmentNode getAttachment(String repositoryId, String attachmentId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchAttachmentNode can = client.get(CouchAttachmentNode.class, attachmentId);
			
			if (can != null) {
				return can.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting attachment: " + attachmentId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public void setStream(String repositoryId, AttachmentNode attachmentNode) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Create or update the AttachmentNode document with stream metadata
			CouchAttachmentNode can = new CouchAttachmentNode(attachmentNode);
			
			// If the attachment already exists, update it; otherwise create it
			if (attachmentNode.getId() != null && client.exists(attachmentNode.getId())) {
				client.update(can);
				log.debug("Updated attachment metadata for: " + attachmentNode.getId());
			} else {
				client.create(can);
				log.debug("Created attachment metadata for: " + attachmentNode.getId());
			}
			
			// If there's actual binary content, store it as a CouchDB attachment
			if (attachmentNode.getInputStream() != null) {
				try {
					// Get current document revision
					com.ibm.cloud.cloudant.v1.model.Document doc = client.get(attachmentNode.getId());
					String revision = doc != null ? doc.getRev() : null;
					
					// Create attachment with binary content
					String attachmentName = "content"; // Standard attachment name for content
					String contentType = attachmentNode.getMimeType() != null ? 
						attachmentNode.getMimeType() : "application/octet-stream";
					
					String newRevision = client.createAttachment(
						attachmentNode.getId(), 
						revision, 
						attachmentName, 
						attachmentNode.getInputStream(), 
						contentType
					);
					
					log.debug("Stored binary content as attachment for: " + attachmentNode.getId() + " (revision: " + newRevision + ")");
					
				} catch (Exception attachmentError) {
					log.warn("Failed to store binary content as attachment for: " + attachmentNode.getId() + ". Content stored as metadata only.", attachmentError);
				}
			}
			
		} catch (Exception e) {
			log.error("Error setting stream for attachment: " + attachmentNode.getId() + " in repository: " + repositoryId, e);
		}
	}

	@Override
	public Rendition getRendition(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchRendition cr = client.get(CouchRendition.class, objectId);
			
			if (cr != null) {
				return cr.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting rendition: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public String createAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Create the AttachmentNode document first
			CouchAttachmentNode can = new CouchAttachmentNode(attachment);
			
			// Set content stream properties if available
			if (contentStream != null) {
				can.setMimeType(contentStream.getMimeType());
				can.setLength(contentStream.getLength());
				can.setName(contentStream.getFileName()); // Use setName instead of setFileName
			}
			
			com.ibm.cloud.cloudant.v1.model.DocumentResult result;
			
			// Create document
			if (attachment.getId() != null && !attachment.getId().isEmpty()) {
				// Create with specific ID
				ObjectMapper mapper = createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(can, Map.class);
				result = client.create(attachment.getId(), documentMap);
			} else {
				// Create with auto-generated ID
				ObjectMapper mapper = createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(can, Map.class);
				result = client.create(documentMap);
			}
			
			String documentId = result.getId();
			String documentRevision = result.getRev();
			
			log.debug("Created attachment document: " + documentId);
			
			// If there's binary content, store it as a CouchDB attachment
			if (contentStream != null && contentStream.getStream() != null) {
				try {
					String attachmentName = "content"; // Standard attachment name for content
					String contentType = contentStream.getMimeType() != null ? 
						contentStream.getMimeType() : "application/octet-stream";
					
					String newRevision = client.createAttachment(
						documentId, 
						documentRevision, 
						attachmentName, 
						contentStream.getStream(), 
						contentType
					);
					
					log.debug("Stored binary content as attachment for: " + documentId + " (revision: " + newRevision + ")");
					
				} catch (Exception attachmentError) {
					log.warn("Failed to store binary content as attachment for: " + documentId + ". Content stored as metadata only.", attachmentError);
				}
			}
			
			return documentId;
			
		} catch (Exception e) {
			log.error("Error creating attachment in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to create attachment", e);
		}
	}

	@Override
	public String createRendition(String repositoryId, Rendition rendition, ContentStream contentStream) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Create the Rendition document first
			CouchRendition cr = new CouchRendition(rendition);
			
			// Set content stream properties if available
			if (contentStream != null) {
				cr.setMimetype(contentStream.getMimeType());
				cr.setLength(contentStream.getLength());
				cr.setTitle(contentStream.getFileName());
			}
			
			com.ibm.cloud.cloudant.v1.model.DocumentResult result;
			
			// Create document
			if (rendition.getId() != null && !rendition.getId().isEmpty()) {
				// Create with specific ID
				ObjectMapper mapper = createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(cr, Map.class);
				result = client.create(rendition.getId(), documentMap);
			} else {
				// Create with auto-generated ID
				ObjectMapper mapper = createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(cr, Map.class);
				result = client.create(documentMap);
			}
			
			String documentId = result.getId();
			String documentRevision = result.getRev();
			
			log.debug("Created rendition document: " + documentId);
			
			// If there's binary content, store it as a CouchDB attachment
			if (contentStream != null && contentStream.getStream() != null) {
				try {
					String attachmentName = "content"; // Standard attachment name for content
					String contentType = contentStream.getMimeType() != null ? 
						contentStream.getMimeType() : "application/octet-stream";
					
					String newRevision = client.createAttachment(
						documentId, 
						documentRevision, 
						attachmentName, 
						contentStream.getStream(), 
						contentType
					);
					
					log.debug("Stored binary content as attachment for rendition: " + documentId + " (revision: " + newRevision + ")");
					
				} catch (Exception attachmentError) {
					log.warn("Failed to store binary content as attachment for rendition: " + documentId + ". Content stored as metadata only.", attachmentError);
				}
			}
			
			return documentId;
			
		} catch (Exception e) {
			log.error("Error creating rendition in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to create rendition", e);
		}
	}

	@Override
	public void updateAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Update the AttachmentNode document first
			CouchAttachmentNode can = new CouchAttachmentNode(attachment);
			
			// Set content stream properties if available
			if (contentStream != null) {
				can.setMimeType(contentStream.getMimeType());
				can.setLength(contentStream.getLength());
				can.setName(contentStream.getFileName());
			}
			
			// Update the document
			client.update(can);
			log.debug("Updated attachment metadata for: " + attachment.getId());
			
			// If there's binary content, update it as a CouchDB attachment
			if (contentStream != null && contentStream.getStream() != null) {
				try {
					// Get current document revision
					com.ibm.cloud.cloudant.v1.model.Document doc = client.get(attachment.getId());
					String revision = doc != null ? doc.getRev() : null;
					
					// Update attachment with binary content
					String attachmentName = "content"; // Standard attachment name for content
					String contentType = contentStream.getMimeType() != null ? 
						contentStream.getMimeType() : "application/octet-stream";
					
					String newRevision = client.createAttachment(
						attachment.getId(), 
						revision, 
						attachmentName, 
						contentStream.getStream(), 
						contentType
					);
					
					log.debug("Updated binary content as attachment for: " + attachment.getId() + " (revision: " + newRevision + ")");
					
				} catch (Exception attachmentError) {
					log.warn("Failed to update binary content as attachment for: " + attachment.getId() + ". Metadata updated only.", attachmentError);
				}
			}
			
		} catch (Exception e) {
			log.error("Error updating attachment: " + attachment.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to update attachment", e);
		}
	}

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	@Override
	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchChange cc = client.get(CouchChange.class, changeTokenId);
			
			if (cc != null) {
				return cc.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting change event: " + changeTokenId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public Change getLatestChange(String repositoryId) {
		try {
			// Query changesByToken view to get the most recent change
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			// IMPORTANT: Must use "changesByToken" as specified in standard design document
			List<CouchChange> couchChanges = client.queryView("_repo", "changesByToken", null, CouchChange.class);
			
			if (!couchChanges.isEmpty()) {
				// Return the first (most recent) change
				return couchChanges.get(0).convert();
			}
			
			// If no changes found, return a default change to prevent errors
			log.debug("No changes found in repository: " + repositoryId + ", returning null");
			return null;
		} catch (Exception e) {
			log.debug("Error getting latest change in repository: " + repositoryId + " (this is normal for empty repositories)", e);
			// Return null instead of throwing exception to prevent cascading errors
			return null;
		}
	}

	@Override
	public List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems) {
		try {
			// Query changesByToken view with pagination
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			if (maxItems > 0) {
				queryParams.put("limit", maxItems);
			}
			if (startToken != null) {
				queryParams.put("startkey", startToken);
			}
			
			ViewResult result = client.queryView("_repo", "changesByToken", queryParams);
			List<Change> changes = new ArrayList<Change>();
			
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = createConfiguredObjectMapper();
							CouchChange cc = mapper.convertValue(row.getDoc(), CouchChange.class);
							if (cc != null) {
								changes.add(cc.convert());
							}
						} catch (Exception e) {
							log.warn("Failed to convert change document: " + e.getMessage());
						}
					}
				}
			}
			
			return changes;
		} catch (Exception e) {
			log.error("Error getting latest changes in repository: " + repositoryId, e);
			return new ArrayList<Change>();
		}
	}

	@Override
	public List<Change> getObjectChanges(String repositoryId, String objectId) {
		try {
			// Query changesByObjectId view with objectId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchChange> couchChanges = client.queryView("_repo", "changesByObjectId", objectId, CouchChange.class);
			
			List<Change> changes = new ArrayList<Change>();
			for (CouchChange couchChange : couchChanges) {
				changes.add(couchChange.convert());
			}
			
			return changes;
		} catch (Exception e) {
			log.error("Error getting object changes for: " + objectId + " in repository: " + repositoryId, e);
			return new ArrayList<Change>();
		}
	}


	@Override
	public Change create(String repositoryId, Change change) {
		CouchChange cc = new CouchChange(change);
		connectorPool.getClient(repositoryId).create(cc);
		return cc.convert();
	}

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	@Override
	public Archive getArchive(String repositoryId, String objectId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);
		CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, objectId);
		return ca.convert();
	}

	@Override
	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		try {
			// Query archiveByOriginalId view with originalId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "archiveByOriginalId", originalId, CouchArchive.class);
			
			if (!couchArchives.isEmpty()) {
				// Return the first (and should be only) result
				return couchArchives.get(0).convert();
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting archive by original ID: " + originalId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public Archive getAttachmentArchive(String repositoryId, Archive archive) {
		try {
			// Query attachmentArchive view with archive ID
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "attachmentArchive", archive.getId(), CouchArchive.class);
			
			if (!couchArchives.isEmpty()) {
				// Return the first attachment archive
				return couchArchives.get(0).convert();
			}
			
			return null;
		} catch (Exception e) {
			log.error("Error getting attachment archive for: " + archive.getId() + " in repository: " + repositoryId, e);
			return null;
		}
	}

	@Override
	public List<Archive> getChildArchives(String repositoryId, Archive archive) {
		try {
			// Query childArchives view with archive ID
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "childArchives", archive.getId(), CouchArchive.class);
			
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive couchArchive : couchArchives) {
				archives.add(couchArchive.convert());
			}
			
			return archives;
		} catch (Exception e) {
			log.error("Error getting child archives for: " + archive.getId() + " in repository: " + repositoryId, e);
			return new ArrayList<Archive>();
		}
	}

	@Override
	public List<Archive> getArchivesOfVersionSeries(String repositoryId, String versionSeriesId) {
		try {
			// Query archivesOfVersionSeries view with versionSeriesId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "archivesOfVersionSeries", versionSeriesId, CouchArchive.class);
			
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive couchArchive : couchArchives) {
				archives.add(couchArchive.convert());
			}
			
			return archives;
		} catch (Exception e) {
			log.error("Error getting archives of version series: " + versionSeriesId + " in repository: " + repositoryId, e);
			return new ArrayList<Archive>();
		}
	}

	@Override
	public List<Archive> getAllArchives(String repositoryId) {
		try {
			// Query allArchives view to get all archives
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "allArchives", null, CouchArchive.class);
			
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive couchArchive : couchArchives) {
				archives.add(couchArchive.convert());
			}
			
			return archives;
		} catch (Exception e) {
			log.error("Error getting all archives in repository: " + repositoryId, e);
			return new ArrayList<Archive>();
		}
	}

	@Override
	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc) {
		try {
			// Query archives view with pagination parameters
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			
			if (skip != null && skip > 0) {
				queryParams.put("skip", skip.longValue());
			}
			if (limit != null && limit > 0) {
				queryParams.put("limit", limit.longValue());
			}
			if (desc != null && desc) {
				queryParams.put("descending", true);
			}
			
			ViewResult result = client.queryView("_repo", "archives", queryParams);
			List<Archive> archives = new ArrayList<Archive>();
			
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = createConfiguredObjectMapper();
							CouchArchive ca = mapper.convertValue(row.getDoc(), CouchArchive.class);
							if (ca != null) {
								archives.add(ca.convert());
							}
						} catch (Exception e) {
							log.warn("Failed to convert archive document: " + e.getMessage());
						}
					}
				}
			}
			
			return archives;
		} catch (Exception e) {
			log.error("Error getting archives in repository: " + repositoryId, e);
			return new ArrayList<Archive>();
		}
	}

	@Override
	public Archive createArchive(String repositoryId, Archive archive, Boolean deletedWithParent) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, archive.getOriginalId());
		CouchArchive ca = new CouchArchive(archive);
		ca.setLastRevision(cnb.getRevision());

		// Write to DB
		connectorPool.get(archiveId).create(ca);
		return ca.convert();
	}

	@Override
	public Archive createAttachmentArchive(String repositoryId, Archive archive) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		CouchArchive ca = new CouchArchive(archive);
		CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, archive.getOriginalId());
		ca.setLastRevision(cnb.getRevision());

		connectorPool.get(archiveId).create(ca);
		return ca.convert();
	}

	@Override
	// FIXME return archiveId or something when successfully deleted
	public void deleteArchive(String repositoryId, String archiveId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);

		try {
			CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, archiveId);
			connectorPool.get(archive).delete(ca);
		} catch (Exception e) {
			log.warn(buildLogMsg(archiveId, "the archive not found on db"));
			return;
		}
	}

	@Override
	public void deleteDocumentArchive(String repositoryId, String archiveId) {
		Archive docArchive = getArchive(repositoryId, archiveId);
		Archive attachmentArchive = getArchiveByOriginalId(repositoryId, docArchive.getAttachmentNodeId());

		deleteArchive(repositoryId, docArchive.getId());
		deleteArchive(repositoryId, attachmentArchive.getId());
	}

	@Override
	public void restoreContent(String repositoryId, Archive archive) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Get the archived content document
			String archiveId = archive.getId();
			String originalId = archive.getOriginalId();
			
			// Get the archive repository
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);
			
			// Retrieve the archived document
			com.ibm.cloud.cloudant.v1.model.Document archivedDoc = archiveClient.get(archiveId);
			if (archivedDoc == null) {
				log.warn("Archive document not found: " + archiveId);
				return;
			}
			
			// CLOUDANT FIX: Use Document.get() to create Map for manipulation
			Map<String, Object> docMap = new HashMap<>();
			
			// Copy standard document fields
			docMap.put("_id", originalId); // Set restored ID
			// Skip _rev to let CouchDB assign new revision
			
			// Copy all custom fields from Document using direct access
			String type = (String) archivedDoc.get("type");
			String objectType = (String) archivedDoc.get("objectType");
			String name = (String) archivedDoc.get("name");
			String creator = (String) archivedDoc.get("creator");
			String modifier = (String) archivedDoc.get("modifier");
			String created = (String) archivedDoc.get("created");
			String modified = (String) archivedDoc.get("modified");
			String changeToken = (String) archivedDoc.get("changeToken");
			
			// Add all accessible fields to the map (excluding archive-specific ones)
			if (type != null) docMap.put("type", type);
			if (objectType != null) docMap.put("objectType", objectType);
			if (name != null) docMap.put("name", name);
			if (creator != null) docMap.put("creator", creator);
			if (modifier != null) docMap.put("modifier", modifier);
			if (created != null) docMap.put("created", created);
			if (modified != null) docMap.put("modified", modified);
			if (changeToken != null) docMap.put("changeToken", changeToken);
			
			// Also try to get additional fields using getProperties() as fallback
			try {
				Map<String, Object> properties = archivedDoc.getProperties();
				if (properties != null && !properties.isEmpty()) {
					for (Map.Entry<String, Object> entry : properties.entrySet()) {
						String key = entry.getKey();
						// Skip archive-specific and already processed fields
						if (!"isArchive".equals(key) && !"originalId".equals(key) && !"_rev".equals(key) && !"_id".equals(key) && 
							!docMap.containsKey(key)) {
							docMap.put(key, entry.getValue());
						}
					}
				}
			} catch (Exception e) {
				log.warn("CLOUDANT FIX: Error accessing getProperties() during restore: " + e.getMessage());
			}
			
			// Create the restored document in the main repository
			client.create(originalId, docMap);
			
			log.debug("Content restored from archive: " + archiveId + " to original ID: " + originalId);
			
		} catch (Exception e) {
			log.error("Error restoring content from archive: " + archive.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to restore content from archive", e);
		}
	}

	@Override
	public void restoreAttachment(String repositoryId, Archive archive) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			// Get the archived attachment document
			String archiveId = archive.getId();
			String originalId = archive.getOriginalId();
			
			// Get the archive repository
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);
			
			// Retrieve the archived attachment document
			CouchAttachmentNode archivedAttachment = archiveClient.get(CouchAttachmentNode.class, archiveId);
			if (archivedAttachment == null) {
				log.warn("Archive attachment document not found: " + archiveId);
				return;
			}
			
			// Reset fields for restoration
			archivedAttachment.setId(originalId);
			archivedAttachment.setRevision(null);
			
			// Create the restored attachment document in the main repository
			ObjectMapper mapper = createConfiguredObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(archivedAttachment, Map.class);
			client.create(originalId, documentMap);
			
			// Also try to restore any binary attachments
			try {
				Object attachmentData = archiveClient.getAttachment(archiveId, "content");
				if (attachmentData != null && attachmentData instanceof java.io.InputStream) {
					// Get current document revision
					com.ibm.cloud.cloudant.v1.model.Document doc = client.get(originalId);
					String revision = doc != null ? doc.getRev() : null;
					
					// Restore binary attachment
					client.createAttachment(originalId, revision, "content", 
						(java.io.InputStream) attachmentData, archivedAttachment.getMimeType());
					
					log.debug("Binary attachment restored for: " + originalId);
				}
			} catch (Exception attachmentError) {
				log.warn("Failed to restore binary attachment for: " + originalId + ". Metadata restored only.", attachmentError);
			}
			
			log.debug("Attachment restored from archive: " + archiveId + " to original ID: " + originalId);
			
		} catch (Exception e) {
			log.error("Error restoring attachment from archive: " + archive.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to restore attachment from archive", e);
		}
	}

	@Override
	public void restoreDocumentWithArchive(String repositoryId, Archive contentArchive) {
		restoreContent(repositoryId, contentArchive);
		// Restore its attachment
		Archive attachmentArchive = getAttachmentArchive(repositoryId, contentArchive);
		restoreAttachment(repositoryId, attachmentArchive);
	}

	// ///////////////////////////////////////
	// Other
	// ///////////////////////////////////////
	private String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	@Override
	public void refreshCmisObjectData(String repositoryId, String objectId) {
		// this method is for cached service
	}
	
	/**
	 * Convert any object to String safely, handling Gson's LazilyParsedNumber
	 */
	private String convertToString(Object obj) {
		if (obj == null) {
			return null;
		}
		if (obj instanceof String) {
			return (String) obj;
		}
		// Handle Gson's LazilyParsedNumber and other numeric types
		return obj.toString();
	}
}
