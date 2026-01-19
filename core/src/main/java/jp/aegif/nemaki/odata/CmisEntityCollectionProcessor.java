package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.EntityCollectionProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.queryoption.CountOption;
import org.apache.olingo.server.api.uri.queryoption.FilterOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByItem;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;
import org.apache.olingo.server.api.uri.queryoption.SelectItem;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.expression.UnaryOperatorKind;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OData Entity Collection Processor for CMIS objects.
 * 
 * Handles requests for entity collections (e.g., /Documents, /Folders).
 * Converts CMIS query results to OData entity collections.
 */
public class CmisEntityCollectionProcessor implements EntityCollectionProcessor {
    
    private OData odata;
    private ServiceMetadata serviceMetadata;
    
    private final RepositoryService repositoryService;
    private final ObjectService objectService;
    private final NavigationService navigationService;
    private final DiscoveryService discoveryService;
    private final String repositoryId;
    private final CallContext callContext;
    
    public CmisEntityCollectionProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            NavigationService navigationService,
            DiscoveryService discoveryService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
        this.navigationService = navigationService;
        this.discoveryService = discoveryService;
        this.repositoryId = repositoryId;
        this.callContext = callContext;
    }
    
    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }
    
    @Override
    public void readEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Get the entity set from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
        
        // Get pagination options
        int top = getTopOption(uriInfo);
        int skip = getSkipOption(uriInfo);
        boolean count = getCountOption(uriInfo);
        
        // Get filter option
        FilterOption filterOption = uriInfo.getFilterOption();
        String filterClause = null;
        if (filterOption != null) {
            filterClause = convertFilterToWhereClause(filterOption);
        }
        
        // Get orderby option
        OrderByOption orderByOption = uriInfo.getOrderByOption();
        String orderByClause = null;
        if (orderByOption != null) {
            orderByClause = convertOrderByToClause(orderByOption);
        }
        
        // Get select option for property filtering
        SelectOption selectOption = uriInfo.getSelectOption();
        Set<String> selectedProperties = null;
        if (selectOption != null) {
            selectedProperties = getSelectedProperties(selectOption);
        }
        
        // Fetch the data from CMIS
        EntityCollection entityCollection = getData(edmEntitySet, top, skip, filterClause, orderByClause, selectedProperties);
        
        // Serialize the response
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        
        EdmEntityType edmEntityType = edmEntitySet.getEntityType();
        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).build();
        
        final String id = request.getRawBaseUri() + "/" + edmEntitySet.getName();
        EntityCollectionSerializerOptions.Builder optsBuilder = EntityCollectionSerializerOptions.with()
                .id(id)
                .contextURL(contextUrl)
                .count(uriInfo.getCountOption())
                .select(selectOption);
        EntityCollectionSerializerOptions opts = optsBuilder.build();
        
        SerializerResult serializerResult = serializer.entityCollection(serviceMetadata, edmEntityType, entityCollection, opts);
        
        response.setContent(serializerResult.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }
    
    /**
     * Get data from CMIS based on the entity set name.
     */
    private EntityCollection getData(EdmEntitySet edmEntitySet, int maxItems, int skipCount, 
            String filterClause, String orderByClause, Set<String> selectedProperties) throws ODataApplicationException {
        EntityCollection entityCollection = new EntityCollection();
        String entitySetName = edmEntitySet.getName();
        
        try {
            String cmisQuery = buildCmisQuery(entitySetName, filterClause, orderByClause, selectedProperties);
            
            if (cmisQuery != null) {
                ObjectList objectList = discoveryService.query(
                        callContext,
                        repositoryId,
                        cmisQuery,
                        Boolean.FALSE,  // searchAllVersions
                        Boolean.TRUE,   // includeAllowableActions
                        IncludeRelationships.NONE,
                        null,           // renditionFilter
                        BigInteger.valueOf(maxItems),
                        BigInteger.valueOf(skipCount),
                        null            // extension
                );
                
                if (objectList != null && objectList.getObjects() != null) {
                    for (ObjectData objectData : objectList.getObjects()) {
                        Entity entity = convertToEntity(objectData, selectedProperties);
                        entityCollection.getEntities().add(entity);
                    }
                    
                    if (objectList.getNumItems() != null) {
                        entityCollection.setCount(objectList.getNumItems().intValue());
                    }
                }
            }
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error fetching data: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
        
        return entityCollection;
    }
    
    /**
     * Build CMIS query based on entity set name with optional filter, orderby, and select clauses.
     */
    private String buildCmisQuery(String entitySetName, String filterClause, String orderByClause, Set<String> selectedProperties) {
        String baseType = getBaseTypeForEntitySet(entitySetName);
        if (baseType == null) {
            return null;
        }
        
        StringBuilder query = new StringBuilder();
        
        // Build SELECT clause
        if (selectedProperties != null && !selectedProperties.isEmpty()) {
            query.append("SELECT ");
            List<String> cmisProperties = new ArrayList<>();
            // Always include objectId
            cmisProperties.add("cmis:objectId");
            for (String prop : selectedProperties) {
                String cmisProperty = mapODataPropertyToCmis(prop);
                if (cmisProperty != null && !cmisProperty.equals("cmis:objectId")) {
                    cmisProperties.add(cmisProperty);
                }
            }
            query.append(String.join(", ", cmisProperties));
        } else {
            query.append("SELECT *");
        }
        
        query.append(" FROM ").append(baseType);
        
        // Add WHERE clause if filter is provided
        if (filterClause != null && !filterClause.isEmpty()) {
            query.append(" WHERE ").append(filterClause);
        }
        
        // Add ORDER BY clause if orderby is provided
        if (orderByClause != null && !orderByClause.isEmpty()) {
            query.append(" ORDER BY ").append(orderByClause);
        }
        
        return query.toString();
    }
    
    /**
     * Get the CMIS base type for an entity set.
     */
    private String getBaseTypeForEntitySet(String entitySetName) {
        switch (entitySetName) {
            case CmisEdmProvider.ES_DOCUMENTS_NAME:
                return "cmis:document";
            case CmisEdmProvider.ES_FOLDERS_NAME:
                return "cmis:folder";
            case CmisEdmProvider.ES_RELATIONSHIPS_NAME:
                return "cmis:relationship";
            case CmisEdmProvider.ES_POLICIES_NAME:
                return "cmis:policy";
            case CmisEdmProvider.ES_ITEMS_NAME:
                return "cmis:item";
            case CmisEdmProvider.ES_OBJECTS_NAME:
                return "cmis:document";
            default:
                return null;
        }
    }
    
    /**
     * Convert OData filter expression to CMIS WHERE clause.
     */
    private String convertFilterToWhereClause(FilterOption filterOption) throws ODataApplicationException {
        if (filterOption == null || filterOption.getExpression() == null) {
            return null;
        }
        
        try {
            Expression expression = filterOption.getExpression();
            return convertExpressionToCmis(expression);
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error converting filter expression: " + e.getMessage(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    /**
     * Convert OData expression to CMIS query expression.
     * Supports basic comparison operators and string functions.
     */
    private String convertExpressionToCmis(Expression expression) throws ODataApplicationException {
        if (expression == null) {
            return null;
        }
        
        String exprStr = expression.toString();
        
        // Handle binary expressions (e.g., name eq 'value')
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Binary) {
            org.apache.olingo.server.api.uri.queryoption.expression.Binary binary = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Binary) expression;
            
            String left = convertExpressionToCmis(binary.getLeftOperand());
            String right = convertExpressionToCmis(binary.getRightOperand());
            BinaryOperatorKind operator = binary.getOperator();
            
            switch (operator) {
                case EQ:
                    return left + " = " + right;
                case NE:
                    return left + " <> " + right;
                case LT:
                    return left + " < " + right;
                case LE:
                    return left + " <= " + right;
                case GT:
                    return left + " > " + right;
                case GE:
                    return left + " >= " + right;
                case AND:
                    return "(" + left + " AND " + right + ")";
                case OR:
                    return "(" + left + " OR " + right + ")";
                default:
                    throw new ODataApplicationException(
                            "Unsupported operator: " + operator,
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
            }
        }
        
        // Handle method expressions (e.g., startswith, contains)
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Method) {
            org.apache.olingo.server.api.uri.queryoption.expression.Method method = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Method) expression;
            
            MethodKind methodKind = method.getMethod();
            List<Expression> parameters = method.getParameters();
            
            if (parameters.size() >= 2) {
                String property = convertExpressionToCmis(parameters.get(0));
                String value = convertExpressionToCmis(parameters.get(1));
                
                // Remove quotes from value for LIKE patterns
                String unquotedValue = value;
                if (value.startsWith("'") && value.endsWith("'")) {
                    unquotedValue = value.substring(1, value.length() - 1);
                }
                
                switch (methodKind) {
                    case STARTSWITH:
                        return property + " LIKE '" + unquotedValue + "%'";
                    case ENDSWITH:
                        return property + " LIKE '%" + unquotedValue + "'";
                    case CONTAINS:
                        return "CONTAINS(" + property + ", '" + unquotedValue + "')";
                    default:
                        throw new ODataApplicationException(
                                "Unsupported method: " + methodKind,
                                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                                Locale.ENGLISH
                        );
                }
            }
        }
        
        // Handle member expressions (property references)
        if (expression instanceof Member) {
            Member member = (Member) expression;
            String propertyName = member.getResourcePath().getUriResourceParts().get(0).toString();
            String cmisProperty = mapODataPropertyToCmis(propertyName);
            return cmisProperty != null ? cmisProperty : propertyName;
        }
        
        // Handle literal expressions
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Literal) {
            org.apache.olingo.server.api.uri.queryoption.expression.Literal literal = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Literal) expression;
            return literal.getText();
        }
        
        // Handle unary expressions (e.g., not)
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Unary) {
            org.apache.olingo.server.api.uri.queryoption.expression.Unary unary = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Unary) expression;
            
            String operand = convertExpressionToCmis(unary.getOperand());
            UnaryOperatorKind operator = unary.getOperator();
            
            if (operator == UnaryOperatorKind.NOT) {
                return "NOT (" + operand + ")";
            }
        }
        
        // Fallback: return the expression as string
        return exprStr;
    }
    
    /**
     * Convert OData orderby option to CMIS ORDER BY clause.
     */
    private String convertOrderByToClause(OrderByOption orderByOption) {
        if (orderByOption == null || orderByOption.getOrders() == null || orderByOption.getOrders().isEmpty()) {
            return null;
        }
        
        List<String> orderClauses = new ArrayList<>();
        for (OrderByItem item : orderByOption.getOrders()) {
            String property = item.getExpression().toString();
            String cmisProperty = mapODataPropertyToCmis(property);
            if (cmisProperty != null) {
                String direction = item.isDescending() ? " DESC" : " ASC";
                orderClauses.add(cmisProperty + direction);
            }
        }
        
        return orderClauses.isEmpty() ? null : String.join(", ", orderClauses);
    }
    
    /**
     * Get selected properties from SelectOption.
     */
    private Set<String> getSelectedProperties(SelectOption selectOption) {
        if (selectOption == null || selectOption.getSelectItems() == null) {
            return null;
        }
        
        Set<String> properties = new HashSet<>();
        for (SelectItem item : selectOption.getSelectItems()) {
            if (item.isStar()) {
                return null; // Select all
            }
            if (item.getResourcePath() != null && !item.getResourcePath().getUriResourceParts().isEmpty()) {
                String propertyName = item.getResourcePath().getUriResourceParts().get(0).toString();
                properties.add(propertyName);
            }
        }
        
        return properties.isEmpty() ? null : properties;
    }
    
    /**
     * Map OData property name to CMIS property ID.
     */
    private String mapODataPropertyToCmis(String odataProperty) {
        switch (odataProperty) {
            case "objectId":
                return "cmis:objectId";
            case "objectTypeId":
                return "cmis:objectTypeId";
            case "baseTypeId":
                return "cmis:baseTypeId";
            case "name":
                return "cmis:name";
            case "description":
                return "cmis:description";
            case "createdBy":
                return "cmis:createdBy";
            case "creationDate":
                return "cmis:creationDate";
            case "lastModifiedBy":
                return "cmis:lastModifiedBy";
            case "lastModificationDate":
                return "cmis:lastModificationDate";
            case "changeToken":
                return "cmis:changeToken";
            case "isImmutable":
                return "cmis:isImmutable";
            case "isLatestVersion":
                return "cmis:isLatestVersion";
            case "isMajorVersion":
                return "cmis:isMajorVersion";
            case "isLatestMajorVersion":
                return "cmis:isLatestMajorVersion";
            case "isPrivateWorkingCopy":
                return "cmis:isPrivateWorkingCopy";
            case "versionLabel":
                return "cmis:versionLabel";
            case "versionSeriesId":
                return "cmis:versionSeriesId";
            case "isVersionSeriesCheckedOut":
                return "cmis:isVersionSeriesCheckedOut";
            case "versionSeriesCheckedOutBy":
                return "cmis:versionSeriesCheckedOutBy";
            case "versionSeriesCheckedOutId":
                return "cmis:versionSeriesCheckedOutId";
            case "checkinComment":
                return "cmis:checkinComment";
            case "contentStreamLength":
                return "cmis:contentStreamLength";
            case "contentStreamMimeType":
                return "cmis:contentStreamMimeType";
            case "contentStreamFileName":
                return "cmis:contentStreamFileName";
            case "contentStreamId":
                return "cmis:contentStreamId";
            case "parentId":
                return "cmis:parentId";
            case "path":
                return "cmis:path";
            case "sourceId":
                return "cmis:sourceId";
            case "targetId":
                return "cmis:targetId";
            case "policyText":
                return "cmis:policyText";
            default:
                return null;
        }
    }
    
    /**
     * Convert CMIS ObjectData to OData Entity with optional property filtering.
     */
    private Entity convertToEntity(ObjectData objectData, Set<String> selectedProperties) {
        Entity entity = new Entity();
        
        if (objectData.getProperties() != null) {
            for (PropertyData<?> propertyData : objectData.getProperties().getPropertyList()) {
                String propertyId = propertyData.getId();
                Object value = propertyData.getFirstValue();
                
                // Map CMIS property names to OData property names
                String odataPropertyName = mapPropertyName(propertyId);
                if (odataPropertyName != null && value != null) {
                    // If selectedProperties is specified, only include selected properties
                    // Always include objectId as it's the key
                    if (selectedProperties == null || 
                        selectedProperties.contains(odataPropertyName) || 
                        odataPropertyName.equals("objectId")) {
                        Property property = convertProperty(odataPropertyName, value);
                        if (property != null) {
                            entity.addProperty(property);
                        }
                    }
                }
            }
        }
        
        // Set the entity ID
        String objectId = getPropertyValue(objectData, "cmis:objectId");
        if (objectId != null) {
            entity.setId(URI.create(objectId));
        }
        
        return entity;
    }
    
    /**
     * Map CMIS property ID to OData property name.
     */
    private String mapPropertyName(String cmisPropertyId) {
        switch (cmisPropertyId) {
            case "cmis:objectId":
                return "objectId";
            case "cmis:objectTypeId":
                return "objectTypeId";
            case "cmis:baseTypeId":
                return "baseTypeId";
            case "cmis:name":
                return "name";
            case "cmis:description":
                return "description";
            case "cmis:createdBy":
                return "createdBy";
            case "cmis:creationDate":
                return "creationDate";
            case "cmis:lastModifiedBy":
                return "lastModifiedBy";
            case "cmis:lastModificationDate":
                return "lastModificationDate";
            case "cmis:changeToken":
                return "changeToken";
            case "cmis:isImmutable":
                return "isImmutable";
            case "cmis:isLatestVersion":
                return "isLatestVersion";
            case "cmis:isMajorVersion":
                return "isMajorVersion";
            case "cmis:isLatestMajorVersion":
                return "isLatestMajorVersion";
            case "cmis:isPrivateWorkingCopy":
                return "isPrivateWorkingCopy";
            case "cmis:versionLabel":
                return "versionLabel";
            case "cmis:versionSeriesId":
                return "versionSeriesId";
            case "cmis:isVersionSeriesCheckedOut":
                return "isVersionSeriesCheckedOut";
            case "cmis:versionSeriesCheckedOutBy":
                return "versionSeriesCheckedOutBy";
            case "cmis:versionSeriesCheckedOutId":
                return "versionSeriesCheckedOutId";
            case "cmis:checkinComment":
                return "checkinComment";
            case "cmis:contentStreamLength":
                return "contentStreamLength";
            case "cmis:contentStreamMimeType":
                return "contentStreamMimeType";
            case "cmis:contentStreamFileName":
                return "contentStreamFileName";
            case "cmis:contentStreamId":
                return "contentStreamId";
            case "cmis:parentId":
                return "parentId";
            case "cmis:path":
                return "path";
            case "cmis:sourceId":
                return "sourceId";
            case "cmis:targetId":
                return "targetId";
            case "cmis:policyText":
                return "policyText";
            default:
                return null;
        }
    }
    
    /**
     * Convert a CMIS property value to an OData Property.
     */
    private Property convertProperty(String name, Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof String) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof Boolean) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof Number) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof GregorianCalendar) {
            return new Property(null, name, ValueType.PRIMITIVE, ((GregorianCalendar) value).getTime());
        } else {
            return new Property(null, name, ValueType.PRIMITIVE, value.toString());
        }
    }
    
    /**
     * Get a string property value from ObjectData.
     */
    private String getPropertyValue(ObjectData objectData, String propertyId) {
        if (objectData.getProperties() != null) {
            PropertyData<?> propertyData = objectData.getProperties().getProperties().get(propertyId);
            if (propertyData != null && propertyData.getFirstValue() != null) {
                return propertyData.getFirstValue().toString();
            }
        }
        return null;
    }
    
    /**
     * Get the $top query option value.
     */
    private int getTopOption(UriInfo uriInfo) {
        TopOption topOption = uriInfo.getTopOption();
        if (topOption != null) {
            return topOption.getValue();
        }
        return 100; // Default max items
    }
    
    /**
     * Get the $skip query option value.
     */
    private int getSkipOption(UriInfo uriInfo) {
        SkipOption skipOption = uriInfo.getSkipOption();
        if (skipOption != null) {
            return skipOption.getValue();
        }
        return 0;
    }
    
    /**
     * Get the $count query option value.
     */
    private boolean getCountOption(UriInfo uriInfo) {
        CountOption countOption = uriInfo.getCountOption();
        if (countOption != null) {
            return countOption.getValue();
        }
        return false;
    }
}
