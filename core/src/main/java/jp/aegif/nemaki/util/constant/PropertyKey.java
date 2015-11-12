package jp.aegif.nemaki.util.constant;

public interface PropertyKey {
	//DB
	final String DB_COUCHDB_URL= "db.couchdb.url";
	final String DB_COUCHDB_MAX_CONNECTIONS= "db.couchdb.max.connections";
	final String DB_COUCHDB_CONNECTION_TIMEOUT= "db.couchdb.connection.timeout";
	final String DB_COUCHDB_SOCKET_TIMEOUT= "db.couchdb.socket.timeout";

	//CMIS
	final String CMIS_SERVER_DEFAULT_MAX_ITEMS_TYPES = "cmis.server.default.max.items.types";
	final String CMIS_SERVER_DEFAULT_DEPTH_TYPES = "cmis.server.default.depth.types";
	final String CMIS_SERVER_DEFAULT_MAX_ITEMS_OBJECTS = "cmis.server.default.max.items.objects";
	final String CMIS_SERVER_DEFAULT_MAX_DEPTH_OBJECTS = "cmis.server.default.depth.objects";
	final String REPOSITORY_DEFINITION = "repository.definition";

	//Solr
	final String SOLR_PROTOCOL = "solr.protocol";
	final String SOLR_HOST = "solr.host";
	final String SOLR_PORT = "solr.port";
	final String SOLR_CONTEXT = "solr.context";
	final String SOLR_INDEXING_FORCE= "solr.indexing.force";
	final String SOLR_NEMAKI_USERID= "solr.nemaki.userid";


	//Config file path
	final String PERMISSION_DEFINITION= "permission.definition";
	final String PERMISSION_MAPPING_DEFINITION= "permission.mapping.definition";
	final String OVRRIDE_FILE= "override.file";

	//Capability
	final String CAPABILITY_EXTENDED_ORDERBY_DEFAULT = "capability.extended.orderBy.default";
	final String CAPABILITY_EXTENDED_PREVIEW = "capability.extended.preview";
	final String CAPABILITY_EXTENDED_INCLUDE_RELATIONSHIPS = "capability.extended.include.relationships";
	final String CAPABILITY_EXTENDED_BUILD_UNIQUE_NAME = "capability.extended.build.unique.name";
	final String CAPABILITY_EXTENDED_AUTH_TOKEN = "capability.extended.auth.token";

	//Rest
	final String REST_USER_ENABLED = "rest.user.enabled";
	final String REST_GROUP_ENABLED = "rest.group.enabled";
	final String REST_TYPE_ENABLED = "rest.type.enabled";
	final String REST_ARCHIVE_ENABLED = "rest.archive.enabled";
	final String REST_SOLR_ENABLED = "rest.solr.enabled";
	final String REST_AUTHTOKEN_ENABLED = "rest.authtoken.enabled";

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

	//Base types
	final String BASETYPE_DOCUMENT_LOCAL_NAME = "basetype.document.localName";
	final String BASETYPE_DOCUMENT_DISPLAY_NAME = "basetype.document.displayName";
	final String BASETYPE_DOCUMENT_DESCRIPTION = "basetype.document.description";
	final String BASETYPE_DOCUMENT_CREATABLE = "basetype.document.creatable";
	final String BASETYPE_DOCUMENT_FILEABLE = "basetype.document.fileable";
	final String BASETYPE_DOCUMENT_QUERYABLE = "basetype.document.queryable";
	final String BASETYPE_DOCUMENT_CONTROLLABLE_POLICY = "basetype.document.controllablePolicy";
	final String BASETYPE_DOCUMENT_CONTROLLABLE_ACL = "basetype.document.controllableAcl";
	final String BASETYPE_DOCUMENT_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.document.includedInSupertypeQuery";
	final String BASETYPE_DOCUMENT_FULLTEXT_INDEXED = "basetype.document.fulltextIndexed";
	final String BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_CREATE = "basetype.document.typeMutability.canCreate";
	final String BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_UPDATE = "basetype.document.typeMutability.canUpdate";
	final String BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_DELETE = "basetype.document.typeMutability.canDelete";
	final String BASETYPE_DOCUMENT_VERSIONABLE = "basetype.document.versionable";
	final String BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED = "basetype.document.contentStreamAllowed";

	final String BASETYPE_FOLDER_LOCAL_NAME = "basetype.folder.localName";
	final String BASETYPE_FOLDER_DISPLAY_NAME = "basetype.folder.displayName";
	final String BASETYPE_FOLDER_DESCRIPTION = "basetype.folder.description";
	final String BASETYPE_FOLDER_CREATABLE = "basetype.folder.creatable";
	final String BASETYPE_FOLDER_QUERYABLE = "basetype.folder.queryable";
	final String BASETYPE_FOLDER_CONTROLLABLE_POLICY = "basetype.folder.controllablePolicy";
	final String BASETYPE_FOLDER_CONTROLLABLE_ACL = "basetype.folder.controllableAcl";
	final String BASETYPE_FOLDER_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.folder.includedInSupertypeQuery";
	final String BASETYPE_FOLDER_FULLTEXT_INDEXED = "basetype.folder.fulltextIndexed";
	final String BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_CREATE = "basetype.folder.typeMutability.canCreate";
	final String BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_UPDATE = "basetype.folder.typeMutability.canUpdate";
	final String BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_DELETE = "basetype.folder.typeMutability.canDelete";

	final String BASETYPE_RELATIONSHIP_LOCAL_NAME = "basetype.relationship.localName";
	final String BASETYPE_RELATIONSHIP_DISPLAY_NAME = "basetype.relationship.displayName";
	final String BASETYPE_RELATIONSHIP_DESCRIPTION = "basetype.relationship.description";
	final String BASETYPE_RELATIONSHIP_CREATABLE = "basetype.relationship.creatable";
	final String BASETYPE_RELATIONSHIP_QUERYABLE = "basetype.relationship.queryable";
	final String BASETYPE_RELATIONSHIP_CONTROLLABLE_POLICY = "basetype.relationship.controllablePolicy";
	final String BASETYPE_RELATIONSHIP_CONTROLLABLE_ACL = "basetype.relationship.controllableAcl";
	final String BASETYPE_RELATIONSHIP_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.relationship.includedInSupertypeQuery";
	final String BASETYPE_RELATIONSHIP_FULLTEXT_INDEXED = "basetype.relationship.fulltextIndexed";
	final String BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_CREATE = "basetype.relationship.typeMutability.canCreate";
	final String BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_UPDATE = "basetype.relationship.typeMutability.canUpdate";
	final String BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_DELETE = "basetype.relationship.typeMutability.canDelete";
	final String BASETYPE_RELATIONSHIP_ALLOWED_SOURCE_TYPES = "basetype.relationship.allowedSourceTypes";
	final String BASETYPE_RELATIONSHIP_ALLOWED_TARGET_TYPES = "basetype.relationship.allowedTargetTypes";

	final String BASETYPE_POLICY_LOCAL_NAME = "basetype.policy.localName";
	final String BASETYPE_POLICY_DISPLAY_NAME = "basetype.policy.displayName";
	final String BASETYPE_POLICY_DESCRIPTION = "basetype.policy.description";
	final String BASETYPE_POLICY_CREATABLE = "basetype.policy.creatable";
	final String BASETYPE_POLICY_FILEABLE = "basetype.policy.fileable";
	final String BASETYPE_POLICY_QUERYABLE = "basetype.policy.queryable";
	final String BASETYPE_POLICY_CONTROLLABLE_POLICY = "basetype.policy.controllablePolicy";
	final String BASETYPE_POLICY_CONTROLLABLE_ACL = "basetype.policy.controllableAcl";
	final String BASETYPE_POLICY_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.policy.includedInSupertypeQuery";
	final String BASETYPE_POLICY_FULLTEXT_INDEXED = "basetype.policy.fulltextIndexed";
	final String BASETYPE_POLICY_TYPE_MUTABILITY_CAN_CREATE = "basetype.policy.typeMutability.canCreate";
	final String BASETYPE_POLICY_TYPE_MUTABILITY_CAN_UPDATE = "basetype.policy.typeMutability.canUpdate";
	final String BASETYPE_POLICY_TYPE_MUTABILITY_CAN_DELETE = "basetype.policy.typeMutability.canDelete";

	final String BASETYPE_ITEM_LOCAL_NAME = "basetype.item.localName";
	final String BASETYPE_ITEM_DISPLAY_NAME = "basetype.item.displayName";
	final String BASETYPE_ITEM_DESCRIPTION = "basetype.item.description";
	final String BASETYPE_ITEM_CREATABLE = "basetype.item.creatable";
	final String BASETYPE_ITEM_FILEABLE = "basetype.item.fileable";
	final String BASETYPE_ITEM_QUERYABLE = "basetype.item.queryable";
	final String BASETYPE_ITEM_CONTROLLABLE_POLICY = "basetype.item.controllablePolicy";
	final String BASETYPE_ITEM_CONTROLLABLE_ACL = "basetype.item.controllableAcl";
	final String BASETYPE_ITEM_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.item.includedInSupertypeQuery";
	final String BASETYPE_ITEM_FULLTEXT_INDEXED = "basetype.item.fulltextIndexed";
	final String BASETYPE_ITEM_TYPE_MUTABILITY_CAN_CREATE = "basetype.item.typeMutability.canCreate";
	final String BASETYPE_ITEM_TYPE_MUTABILITY_CAN_UPDATE = "basetype.item.typeMutability.canUpdate";
	final String BASETYPE_ITEM_TYPE_MUTABILITY_CAN_DELETE = "basetype.item.typeMutability.canDelete";

	final String BASETYPE_SECONDARY_LOCAL_NAME = "basetype.secondary.localName";
	final String BASETYPE_SECONDARY_DISPLAY_NAME = "basetype.secondary.displayName";
	final String BASETYPE_SECONDARY_DESCRIPTION = "basetype.secondary.description";
	final String BASETYPE_SECONDARY_QUERYABLE = "basetype.secondary.queryable";
	final String BASETYPE_SECONDARY_INCLUDED_IN_SUPER_TYPE_QUERY = "basetype.secondary.includedInSupertypeQuery";
	final String BASETYPE_SECONDARY_FULLTEXT_INDEXED = "basetype.secondary.fulltextIndexed";
	final String BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_CREATE = "basetype.secondary.typeMutability.canCreate";
	final String BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_UPDATE = "basetype.secondary.typeMutability.canUpdate";
	final String BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_DELETE = "basetype.secondary.typeMutability.canDelete";

	//Property
	final String PROPERTY_NAME_UPDATABILITY="property.name.updatability";
	final String PROPERTY_NAME_QUERYABLE="property.name.queryable";
	final String PROPERTY_NAME_ORDERABLE="property.name.orderable";

	final String PROPERTY_DESCRIPTION_UPDATABILITY="property.description.updatability";
	final String PROPERTY_DESCRIPTION_QUERYABLE="property.description.queryable";
	final String PROPERTY_DESCRIPTION_ORDERABLE="property.description.orderable";

	final String PROPERTY_OBJECT_ID_ORDERABLE= "property.objectId.orderable";

	final String PROPERTY_BASE_TYPE_ID_QUERYABLE="property.baseTypeId.queryable";
	final String PROPERTY_BASE_TYPE_ID_ORDERABLE="property.baseTypeId.orderable";

	final String PROPERTY_OBJECT_TYPE_ID_QUERYABLE="property.objectTypeId.queryable";
	final String PROPERTY_OBJECT_TYPE_ID_ORDERABLE="property.objectTypeId.orderable";

	final String PROPERTY_SECONDARY_OBJECT_TYPE_IDS_UPDATABILITY="property.secondaryObjectTypeIds.updatability";
	final String PROPERTY_SECONDARY_OBJECT_TYPE_IDS_QUERYABLE="property.secondaryObjectTypeIds.queryable";

	final String PROPERTY_IS_IMMUTABLE_QUERYABLE = "property.isImmutable.queryable";
	final String PROPERTY_IS_IMMUTABLE_ORDERABLE = "property.isImmutable.orderable";

	final String PROPERTY_IS_LATEST_VERSION_QUERYABLE = "property.isLatestVersion.queryable";
	final String PROPERTY_IS_LATEST_VERSION_ORDERABLE = "property.isLatestVersion.orderable";

	final String PROPERTY_IS_MAJOR_VERSION_QUERYABLE = "property.isMajorVersion.queryable";
	final String PROPERTY_IS_MAJOR_VERSION_ORDERABLE = "property.isMajorVersion.orderable";

	final String PROPERTY_IS_LATEST_MAJOR_VERSION_QUERYABLE = "property.isLatestMajorVersion.queryable";
	final String PROPERTY_IS_LATEST_MAJOR_VERSION_ORDERABLE = "property.isLatestMajorVersion.orderable";

	final String PROPERTY_IS_PRIVATE_WORKING_COPY_QUERYABLE = "property.isPrivateWorkingCopy.queryable";
	final String PROPERTY_IS_PRIVATE_WORKING_COPY_ORDERABLE = "property.isPrivateWorkingCopy.orderable";

	final String PROPERTY_VERSION_LABEL_QUERYABLE = "property.versionLabel.queryable";
	final String PROPERTY_VERSION_LABEL_ORDERABLE = "property.versionLabel.orderable";

	final String PROPERTY_VERSION_SERIES_ID_QUERYABLE = "property.versionSeriesId.queryable";
	final String PROPERTY_VERSION_SERIES_ID_ORDERABLE = "property.versionSeriesId.orderable";

	final String PROPERTY_IS_VERSION_SERIES_CHECKED_OUT_QUERYABLE = "property.isVersionSeriesCheckedOut.queryable";
	final String PROPERTY_IS_VERSION_SERIES_CHECKED_OUT_ORDERABLE = "property.isVersionSeriesCheckedOut.orderable";

	final String PROPERTY_VERSION_SERIES_CHECKED_OUT_BY_QUERYABLE = "property.versionSeriesCheckedOutBy.queryable";
	final String PROPERTY_VERSION_SERIES_CHECKED_OUT_BY_ORDERABLE = "property.versionSeriesCheckedOutBy.orderable";

	final String PROPERTY_VERSION_SERIES_CHECKED_OUT_ID_QUERYABLE = "property.versionSeriesCheckedOutId.queryable";
	final String PROPERTY_VERSION_SERIES_CHECKED_OUT_ID_ORDERABLE = "property.versionSeriesCheckedOutId.orderable";

	final String PROPERTY_CHECK_IN_COMMENT_QUERYABLE = "property.checkInComment.queryable";
	final String PROPERTY_CHECK_IN_COMMENT_ORDERABLE = "property.checkInComment.orderable";

	final String PROPERTY_CONTENT_STREAM_LENGTH_QUERYABLE = "property.contentStreamLength.queryable";
	final String PROPERTY_CONTENT_STREAM_LENGTH_ORDERABLE = "property.contentStreamLength.orderable";

	final String PROPERTY_CONTENT_STREAM_MIME_TYPE_QUERYABLE = "property.contentStreamMimeType.queryable";
	final String PROPERTY_CONTENT_STREAM_MIME_TYPE_ORDERABLE = "property.contentStreamMimeType.orderable";

	final String PROPERTY_CONTENT_STREAM_FILE_NAME_QUERYABLE = "property.contentStreamFileName.queryable";
	final String PROPERTY_CONTENT_STREAM_FILE_NAME_ORDERABLE = "property.contentStreamFileName.orderable";

	final String PROPERTY_CONTENT_STREAM_ID_QUERYABLE = "property.contentStreamId.queryable";
	final String PROPERTY_CONTENT_STREAM_ID_ORDERABLE = "property.contentStreamId.orderable";

	final String PROPERTY_PARENT_ID_QUERYABLE = "property.parentId.queryable";

	final String PROPERTY_PATH_QUERYABLE = "property.path.queryable";
	final String PROPERTY_PATH_ORDERABLE = "property.path.orderable";

	final String PROPERTY_SOURCE_ID_QUERYABLE = "property.sourceId.queryable";
	final String PROPERTY_SOURCE_ID_ORDERABLE = "property.sourceId.orderable";

	final String PROPERTY_TARGET_ID_QUERYABLE = "property.targetId.queryable";
	final String PROPERTY_TARGET_ID_ORDERABLE = "property.targetId.orderable";

	final String PROPERTY_POLICY_TEXT_QUERYABLE = "property.policyText.queryable";
	final String PROPERTY_POLICY_TEXT_ORDERABLE = "property.policyText.orderable";

	//Rendition service
	final String JODCONVERTER_REGISTRY_DATAFORMATS = "jodconverter.registry.dataformats";
	final String JODCONVERTER_OFFICEHOME = "jodconverter.officehome";

	//Log
	final String LOG_ASPECT_DEFAULT = "log.aspect.default";
	final String LOG_ASPECT_EXPRESSION = "log.aspect.expression";
	final String LOG_CONFIG_PATH = "log.config.path";
	final String LOG_LEVEL = "log.level";
	final String LOG_RETURN_VALUE = "log.return.value";
	final String LOG_FQN = "log.fqn";
	final String LOG_ARGUMENTS = "log.arguments";
	final String LOG_BEFORE = "log.before";
	final String LOG_AFTER = "log.after";
	final String LOG_CALLCONTEXT = "log.callcontext";

	//Cache
	final String CACHE_CMIS_ENABLED = "cache.cmis.enabled";

	//Auth token
	final String AUTH_TOKEN_EXPIRATION = "auth.token.expiration";

	//External authentication
	final String EXTERNAL_AUTHENTICATION_PROXY_HEADER = "external.authenticaion.proxyHeader";
}
