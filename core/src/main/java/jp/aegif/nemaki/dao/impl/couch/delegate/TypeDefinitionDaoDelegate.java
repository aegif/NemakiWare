package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionCore;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionDetail;
import jp.aegif.nemaki.model.couch.CouchTypeDefinition;

/**
 * Delegate for Type & Property Definition DAO operations.
 * Extracted from ContentDaoServiceImpl as part of class decomposition.
 */
public class TypeDefinitionDaoDelegate {

	private static final Log log = LogFactory.getLog(TypeDefinitionDaoDelegate.class);

	private final CloudantClientPool connectorPool;
	private final DaoHelper daoHelper;
	private final Runnable refreshTypesCallback;
	private final BiConsumer<String, String> deleteFunction;

	public TypeDefinitionDaoDelegate(CloudantClientPool connectorPool, DaoHelper daoHelper,
			Runnable refreshTypesCallback, BiConsumer<String, String> deleteFunction) {
		this.connectorPool = connectorPool;
		this.daoHelper = daoHelper;
		this.refreshTypesCallback = refreshTypesCallback;
		this.deleteFunction = deleteFunction;
	}

	@SuppressWarnings("unchecked")
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		try {
			// Use ViewQuery to get type definitions from design document

			Map<String, Object> queryParams = new HashMap<String, Object>();
			// CRITICAL FIX: Must include documents to get full type definition data
			queryParams.put("include_docs", true);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "typeDefinitions", queryParams);

			List<NemakiTypeDefinition> typeDefinitions = new ArrayList<NemakiTypeDefinition>();

			// Handle null result gracefully (occurs during initial startup when design documents may not exist yet)
			if (result != null && result.getRows() != null) {
				int processedCount = 0;

				for (ViewResultRow row : result.getRows()) {
					processedCount++;

					if (row.getDoc() != null) {
						// Convert document to CouchTypeDefinition, then to NemakiTypeDefinition
						try {
							// Handle both Document and Map types from Cloudant SDK
							Map<String, Object> docMap = null;
							Object docObj = row.getDoc();

							if (docObj instanceof Map) {
								// Already a Map, use directly
								docMap = (Map<String, Object>) docObj;
								} else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
								// Convert Document to Map using properties
								com.ibm.cloud.cloudant.v1.model.Document doc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
								docMap = doc.getProperties();

								// CRITICAL FIX: Add CouchDB metadata (_id, _rev) to properties map
								// getProperties() only returns document content, not CouchDB metadata
								// CouchNodeBase constructor needs _id and _rev fields for proper object initialization
								if (doc.getId() != null) {
									docMap.put("_id", doc.getId());
									}
								if (doc.getRev() != null) {
									docMap.put("_rev", doc.getRev());
									}

								} else {
									continue;
							}
									String typeId = (String) docMap.get("typeId");












								CouchTypeDefinition ctd = new CouchTypeDefinition(docMap);


									if (ctd != null) {
									typeDefinitions.add(ctd.convert());
								}
						} catch (Exception e) {
							String typeId = "unknown";
							try {
								Object docObj = row.getDoc();
								if (docObj instanceof Map) {
									typeId = (String) ((Map<String, Object>) docObj).get("typeId");
								} else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
									typeId = (String) ((com.ibm.cloud.cloudant.v1.model.Document) docObj).getProperties().get("typeId");
								}
							} catch (Exception ex) {
								// Ignore, use default "unknown"
							}
								e.printStackTrace();
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

	public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		throw new UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
				+ ":this method is only for cahced service. No need for implementation.");
	}

	public NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {

		// Step 1: Create CouchTypeDefinition
			CouchTypeDefinition ct = new CouchTypeDefinition(typeDefinition);

		// Step 2: Call CloudantClientWrapper create

		try {
			connectorPool.getClient(repositoryId).create(ct);
			} catch (Exception e) {
				e.printStackTrace();
			throw e;
		}

		// Step 3: Convert back to NemakiTypeDefinition
			NemakiTypeDefinition result = ct.convert();

		if (refreshTypesCallback != null) {
			refreshTypesCallback.run();
		}

			return result;
	}

	public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition cp = connectorPool.getClient(repositoryId).get(CouchTypeDefinition.class, typeDefinition.getId());
		CouchTypeDefinition update = new CouchTypeDefinition(typeDefinition);
		update.setRevision(cp.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}

	public void deleteTypeDefinition(String repositoryId, String nodeId) {

			deleteFunction.accept(repositoryId, nodeId);
		}

	public void clearTypeCache(String repositoryId) {
		// No-op: Non-cached implementation doesn't have a cache to clear
	}

	@SuppressWarnings("unchecked")
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

							ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();

							// CRITICAL FIX: Create order-isolated Map to prevent JSON sequence contamination
							// Handle both Document and Map types from Cloudant SDK
							Map<String, Object> originalDoc = null;
							Object docObj = row.getDoc();

							if (docObj instanceof Map) {
								// Already a Map, use directly
								originalDoc = (Map<String, Object>) docObj;
							} else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
								// Convert Document to Map using properties
								com.ibm.cloud.cloudant.v1.model.Document doc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
								originalDoc = doc.getProperties();

								// Add CouchDB metadata (_id, _rev) to properties map
								if (doc.getId() != null) {
									originalDoc.put("_id", doc.getId());
								}
								if (doc.getRev() != null) {
									originalDoc.put("_rev", doc.getRev());
								}
							} else {
								continue;
							}

							Map<String, Object> isolatedDoc = new LinkedHashMap<>();

							// CONTAMINATION DEFENSE: Add properties in controlled order to prevent sequence pollution
							if (originalDoc.containsKey("_id")) isolatedDoc.put("_id", originalDoc.get("_id"));
							if (originalDoc.containsKey("_rev")) isolatedDoc.put("_rev", originalDoc.get("_rev"));
							if (originalDoc.containsKey("objectType")) isolatedDoc.put("objectType", originalDoc.get("objectType"));
							if (originalDoc.containsKey("type")) isolatedDoc.put("type", originalDoc.get("type"));

							// CRITICAL: Process property fields in deterministic order
							if (originalDoc.containsKey("propertyId")) isolatedDoc.put("propertyId", originalDoc.get("propertyId"));
							if (originalDoc.containsKey("queryName")) isolatedDoc.put("queryName", originalDoc.get("queryName"));
							if (originalDoc.containsKey("localName")) isolatedDoc.put("localName", originalDoc.get("localName"));
							if (originalDoc.containsKey("localNamespace")) isolatedDoc.put("localNamespace", originalDoc.get("localNamespace"));
							if (originalDoc.containsKey("displayName")) isolatedDoc.put("displayName", originalDoc.get("displayName"));
							if (originalDoc.containsKey("description")) isolatedDoc.put("description", originalDoc.get("description"));
							if (originalDoc.containsKey("propertyType")) isolatedDoc.put("propertyType", originalDoc.get("propertyType"));
							if (originalDoc.containsKey("cardinality")) isolatedDoc.put("cardinality", originalDoc.get("cardinality"));
							if (originalDoc.containsKey("updatability")) isolatedDoc.put("updatability", originalDoc.get("updatability"));
							if (originalDoc.containsKey("inherited")) isolatedDoc.put("inherited", originalDoc.get("inherited"));
							if (originalDoc.containsKey("required")) isolatedDoc.put("required", originalDoc.get("required"));
							if (originalDoc.containsKey("queryable")) isolatedDoc.put("queryable", originalDoc.get("queryable"));
							if (originalDoc.containsKey("orderable")) isolatedDoc.put("orderable", originalDoc.get("orderable"));
							if (originalDoc.containsKey("openChoice")) isolatedDoc.put("openChoice", originalDoc.get("openChoice"));
							if (originalDoc.containsKey("choices")) isolatedDoc.put("choices", originalDoc.get("choices"));
							if (originalDoc.containsKey("defaultValue")) isolatedDoc.put("defaultValue", originalDoc.get("defaultValue"));
							if (originalDoc.containsKey("resolution")) isolatedDoc.put("resolution", originalDoc.get("resolution"));
							if (originalDoc.containsKey("precision")) isolatedDoc.put("precision", originalDoc.get("precision"));
							if (originalDoc.containsKey("maxLength")) isolatedDoc.put("maxLength", originalDoc.get("maxLength"));
							if (originalDoc.containsKey("minValue")) isolatedDoc.put("minValue", originalDoc.get("minValue"));
							if (originalDoc.containsKey("maxValue")) isolatedDoc.put("maxValue", originalDoc.get("maxValue"));

							// Add any remaining fields (preserving original values while controlling order)
							for (Map.Entry<String, Object> entry : originalDoc.entrySet()) {
								if (!isolatedDoc.containsKey(entry.getKey())) {
									isolatedDoc.put(entry.getKey(), entry.getValue());
								}
							}

							CouchPropertyDefinitionCore cpdc = mapper.convertValue(isolatedDoc, CouchPropertyDefinitionCore.class);

							// CRITICAL CONTAMINATION PREVENTION: Validate PropertyId before conversion
							if (cpdc != null) {
								if (cpdc.getPropertyId() == null) {
									continue; // Skip NULL PropertyId entries to prevent contamination
								}

								// ADDITIONAL VALIDATION: Check for empty PropertyId
								if (cpdc.getPropertyId().trim().isEmpty()) {
									continue; // Skip empty PropertyId entries
								}

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

	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchPropertyDefinitionCore cpdc = client.get(CouchPropertyDefinitionCore.class, nodeId);

			if (cpdc != null) {
				// CRITICAL CONTAMINATION PREVENTION: Validate PropertyId before conversion
				if (cpdc.getPropertyId() == null) {
					return null; // Return null instead of processing contaminated data
				}

				// ADDITIONAL VALIDATION: Check for empty PropertyId
				if (cpdc.getPropertyId().trim().isEmpty()) {
					return null; // Return null instead of processing contaminated data
				}

				if (log.isDebugEnabled()) {
					log.debug("TCK: About to call convert() on CouchPropertyDefinitionCore with propertyId=" +
						cpdc.getPropertyId() + ", propertyType=" + cpdc.getPropertyType());
				}
				NemakiPropertyDefinitionCore result = cpdc.convert();
				if (log.isDebugEnabled()) {
					log.debug("TCK: After convert(), NemakiPropertyDefinitionCore has propertyId=" +
						result.getPropertyId() + ", propertyType=" + result.getPropertyType());
				}
				return result;
			}

			return null;

		} catch (Exception e) {
			log.error("Error retrieving property definition core '" + nodeId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return null;
		}
	}

	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
		try {
			// Query propertyDefinitionCoreByPropertyId view with propertyId
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchPropertyDefinitionCore> couchCores = client.queryView("_repo", "propertyDefinitionCoresByPropertyId", propertyId, CouchPropertyDefinitionCore.class);

			if (!couchCores.isEmpty()) {
				CouchPropertyDefinitionCore cpdc = couchCores.get(0);

				// CRITICAL CONTAMINATION PREVENTION: Validate PropertyId before conversion
				if (cpdc == null) {
					return null;
				}

				if (cpdc.getPropertyId() == null) {
					return null; // Return null instead of processing contaminated data
				}

				// ADDITIONAL VALIDATION: Check for empty PropertyId
				if (cpdc.getPropertyId().trim().isEmpty()) {
					return null; // Return null instead of processing contaminated data
				}
				// Return the first (and should be only) result
				return cpdc.convert();
			}

			return null;
		} catch (Exception e) {
			log.error("Error getting property definition core by property ID: " + propertyId + " in repository: " + repositoryId, e);
			return null;
		}
	}

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

	@SuppressWarnings("unchecked")
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId,
			String coreNodeId) {
		try {

			// Alternative approach: Direct document retrieval without ObjectMapper conversion
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", true);

			ViewResult result = client.queryView("_repo", "propertyDefinitionDetails", queryParams);
			List<NemakiPropertyDefinitionDetail> details = new ArrayList<NemakiPropertyDefinitionDetail>();




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
												}
						} else {
									continue;
						}

						if (docMap == null) {
									continue;
						}

						Object docCoreNodeId = docMap.get("coreNodeId");



						if (coreNodeId.equals(String.valueOf(docCoreNodeId))) {

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

							} catch (Exception constructionError) {
								}
						}
					}
				}
			}

					return details;

		} catch (Exception e) {
			log.error("Error retrieving property definition details for core node '" + coreNodeId + "' from repository '" + repositoryId + "': " + e.getMessage(), e);
			return new ArrayList<NemakiPropertyDefinitionDetail>(); // Return empty list on error
		}
	}

	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(String repositoryId,
			NemakiPropertyDefinitionCore propertyDefinitionCore) {

		// CRITICAL DEBUG: Trace ID assignment during PropertyDefinitionCore creation

		CouchPropertyDefinitionCore cpc = new CouchPropertyDefinitionCore(propertyDefinitionCore);

		// The create() method should set the ID on the cpc object
		connectorPool.getClient(repositoryId).create(cpc);

		// CRITICAL CONTAMINATION PREVENTION: Validate PropertyId before conversion
		if (cpc.getPropertyId() == null) {
					throw new IllegalStateException("PropertyId cannot be null in createPropertyDefinitionCore - this indicates a creation failure");
		}

		// ADDITIONAL VALIDATION: Check for empty PropertyId
		if (cpc.getPropertyId().trim().isEmpty()) {
				throw new IllegalStateException("PropertyId cannot be empty in createPropertyDefinitionCore - this indicates a creation failure");
		}

			NemakiPropertyDefinitionCore result = cpc.convert();

		return result;
	}

	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		CouchPropertyDefinitionDetail cpd = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		connectorPool.getClient(repositoryId).create(cpd);
		return cpd.convert();
	}

	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {

		CouchPropertyDefinitionDetail cpd = connectorPool.getClient(repositoryId)
				.get(CouchPropertyDefinitionDetail.class, propertyDefinitionDetail.getId());

		CouchPropertyDefinitionDetail update = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		update.setRevision(cpd.getRevision());

		connectorPool.getClient(repositoryId).update(update);
		return update.convert();
	}
}
