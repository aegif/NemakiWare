package jp.aegif.nemaki.model.constant;

public interface PropertyKey {
	final String DB_HOST= "db.host";
	final String DB_MAXCONNECTIONS= "db.maxConnections";
	final String DB_PORT= "db.port";
	final String DB_PROTOCOL = "db.protocol";
	final String REPOSITORY_MAIN= "repository.main";
	final String REPOSITORY_ARCHIVE= "repository.archive";
	final String REPOSITORIES= "repositories";
	final String PRINCIPAL_ADMIN= "principal.admin.id";
	final String SOLR_URL= "solr.url";
	final String SOLR_INDEXING_FORCE= "solr.indexing.force";
	final String PERMISSION_DEFINITION= "permission.definition";
	final String PERMISSION_MAPPING_DEFINITION= "permission.mapping.definition";
	final String OVRRIDE_FILE= "override.file";
	
	//Capabilities
	final String CAPABILITY_GET_DESCENDENTS = "capability.getDescendants";
	final String CAPABILITY_GET_FOLDER_TREE = "capability.getFolderTree";
	final String CAPABILITY_ORDER_BY = "capability.orderBy";
	final String CAPABILITY_CONTENT_STREAM_UPDATABILITY = "capability.contentStreamUpdatability";
	final String CAPABILITY_CHANGES = "capability.changes";
	final String CAPABILITY_RENDITIONS = "capability.renditions";
	final String CAPABILITY_MULTIFILING = "capability.multifiling";
	final String CAPABILITY_UNFILING = "capability.unfiling";
	final String CAPABILITY_VERSION_SPECIFIC_FILING = "capability.versionSpecificFiling";
	final String CAPABILITY_PWC_UPDATABLE = "capability.pwcUpdatable";
	final String CAPABILITY_PWC_SEARCHABLE = "capability.pwcSearchable";
	final String CAPABILITY_ALL_VERSION_SEARCHABLE = "capability.allVersionsSearchable";
	final String CAPABILITY_QUERY = "capability.query";
	final String CAPABILITY_JOIN = "capability.join";
	final String CAPABILITY_CREATABLE_PROPERTY_TYPES = "capability.creatablePropertyTypes";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_ID = "capability.newTypeSettableAttributes.id";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCAL_NAME = "capability.newTypeSettableAttributes.localName";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCAL_NAME_SPACE = "capability.newTypeSettableAttributes.localNamespace";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAY_NAME = "capability.newTypeSettableAttributes.displayName";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERY_NAME = "capability.newTypeSettableAttributes.queryName";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION= "capability.newTypeSettableAttributes.description";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATABLE= "capability.newTypeSettableAttributes.creatable";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE= "capability.newTypeSettableAttributes.fileable";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE = "capability.newTypeSettableAttributes.queryable";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXT_INDEXED = "capability.newTypeSettableAttributes.fulltextIndexed";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDE_IN_SUPERTYPE_QUERY = "capability.newTypeSettableAttributes.includeInSupetypeQuery";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLLABLE_POLICY = "capability.newTypeSettableAttributes.controllablePolicy";
	final String CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLLABLE_ACL = "capability.newTypeSettableAttributes.controllableACL";
	final String CAPABILITY_ACL = "capability.acl";
	
}
