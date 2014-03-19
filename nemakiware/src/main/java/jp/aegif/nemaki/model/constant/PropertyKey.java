package jp.aegif.nemaki.model.constant;

public interface PropertyKey {
	final String DB_COUCHDB_PROTOCOL = "db.couchdb.protocol";
	final String DB_COUCHDB_HOST= "db.couchdb.host";
	final String DB_COUCHDB_PORT= "db.couchdb.port";
	final String DB_COUCHDB_MAX_CONNECTIONS= "db.couchdb.max.connections";
	final String CMIS_REPOSITORY_MAIN = "cmis.repository.main";
	final String CMIS_REPOSITORY_MAIN_DESCRIPTION= "cmis.repository.main.description";
	final String CMIS_REPOSITORY_MAIN_ROOT= "cmis.repository.main.root";
	final String CMIS_REPOSITORY_MAIN_PRINCIPAL_ANONYMOUS= "cmis.repository.main.principal.anonymous";
	final String CMIS_REPOSITORY_MAIN_PRINCIPAL_ANYONE= "cmis.repository.main.principal.anyone";
	final String CMIS_REPOSITORY_MAIN_THINCLIENTURI= "cmis.repository.main.thinClientUri";
	final String CMIS_REPOSITORY_MAIN_VENDOR= "cmis.repository.main.vendor";
	final String CMIS_REPOSITORY_MAIN_PRODUCT_NAME= "cmis.repository.main.product.name";
	final String CMIS_REPOSITORY_MAIN_PRODUCT_VERSION= "cmis.repository.main.product.version";
	final String CMIS_REPOSITORY_MAIN_NAMESPACE= "cmis.repository.main.namespace";
	final String CMIS_REPOSITORY_ARCHIVE= "cmis.repository.archive";
	final String CMIS_REPOSITORIES= "cmis.repositories";
	final String CMIS_PRINCIPAL_ADMIN= "cmis.principal.admin.id";
	final String SOLR_PROTOCOL = "solr.protocol";
	final String SOLR_HOST = "solr.host";
	final String SOLR_PORT = "solr.port";
	final String SOLR_CONTEXT = "solr.context";
	final String SOLR_INDEXING_FORCE= "solr.indexing.force";
	final String PERMISSION_DEFINITION= "permission.definition";
	final String PERMISSION_MAPPING_DEFINITION= "permission.mapping.definition";
	final String OVRRIDE_FILE= "override.file";
	final String CAPABILITY_EXTENDED_ORDERBY_DEFAULT = "capability.extended.orderBy.default";

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
