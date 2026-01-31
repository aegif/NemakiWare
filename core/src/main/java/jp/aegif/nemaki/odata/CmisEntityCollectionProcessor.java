package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
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
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.api.uri.queryoption.SearchOption;
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

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        
        // Get expand option for navigation properties
        ExpandOption expandOption = uriInfo.getExpandOption();
        Set<String> expandProperties = null;
        if (expandOption != null) {
            expandProperties = getExpandProperties(expandOption);
        }
        
        // Get search option for full-text search
        SearchOption searchOption = uriInfo.getSearchOption();
        String searchTerm = null;
        if (searchOption != null && searchOption.getSearchExpression() != null) {
            searchTerm = searchOption.getText();
        }
        
        // Fetch the data from CMIS
        String baseUri = request.getRawBaseUri();
        EntityCollection entityCollection = getData(edmEntitySet, top, skip, filterClause, orderByClause, selectedProperties, expandProperties, searchTerm, baseUri);
        
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
            String filterClause, String orderByClause, Set<String> selectedProperties, Set<String> expandProperties, String searchTerm, String baseUri) throws ODataApplicationException {
        EntityCollection entityCollection = new EntityCollection();
        String entitySetName = edmEntitySet.getName();
        
        try {
            String cmisQuery = buildCmisQuery(entitySetName, filterClause, orderByClause, selectedProperties, searchTerm);
            
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
                        Entity entity = convertToEntity(objectData, selectedProperties, baseUri, entitySetName);
                        
                        // Handle $expand for navigation properties
                        if (expandProperties != null && !expandProperties.isEmpty()) {
                            expandNavigationProperties(entity, objectData, expandProperties);
                        }
                        
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
     * Build CMIS query based on entity set name with optional filter, orderby, select, and search clauses.
     */
    private String buildCmisQuery(String entitySetName, String filterClause, String orderByClause, Set<String> selectedProperties, String searchTerm) {
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
        
        // Build WHERE clause combining filter and search
        List<String> whereClauses = new ArrayList<>();
        
        // Add filter clause if provided
        if (filterClause != null && !filterClause.isEmpty()) {
            whereClauses.add(filterClause);
        }
        
        // Add full-text search clause if search term is provided
        // CMIS uses CONTAINS() function for full-text search
        if (searchTerm != null && !searchTerm.isEmpty()) {
            // Convert OData $search to CMIS CONTAINS() syntax
            String containsClause = convertSearchToContains(searchTerm);
            whereClauses.add(containsClause);
        }
        
        // Combine WHERE clauses with AND
        if (!whereClauses.isEmpty()) {
            query.append(" WHERE ");
            query.append(String.join(" AND ", whereClauses));
        }
        
        // Add ORDER BY clause if orderby is provided
        if (orderByClause != null && !orderByClause.isEmpty()) {
            query.append(" ORDER BY ").append(orderByClause);
        }
        
        return query.toString();
    }
    
    /**
     * Convert OData $search expression to CMIS CONTAINS() clause.
     * 
     * Supports:
     * - Implicit AND: Space-separated terms are combined with AND
     *   Example: "test document" -> CONTAINS('test') AND CONTAINS('document')
     * - Explicit OR: OR keyword between terms
     *   Example: "test OR draft" -> CONTAINS('test') OR CONTAINS('draft')
     * - Phrase search: Double-quoted strings are searched as phrases
     *   Example: '"test document"' -> CONTAINS('"test document"')
     * - NOT: NOT keyword before a term
     *   Example: "test NOT draft" -> CONTAINS('test') AND NOT CONTAINS('draft')
     * 
     * TODO: Future improvements for operator priority and parentheses support:
     * - Implement standard operator precedence (NOT > AND > OR)
     * - Support explicit parentheses for grouping: "(a OR b) AND c"
     * - Current implementation processes operators left-to-right sequentially,
     *   which may produce unexpected results for complex mixed expressions like
     *   "a AND b OR c" (currently: ((a AND b) OR c), standard: (a AND b) OR c)
     * 
     * @param searchTerm The raw search term from OData $search
     * @return The CMIS CONTAINS() clause
     */
    private String convertSearchToContains(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return "CONTAINS('')";
        }
        
        List<String> tokens = tokenizeSearchExpression(searchTerm);
        if (tokens.isEmpty()) {
            return "CONTAINS('')";
        }
        
        // Build CMIS CONTAINS clauses from tokens
        List<String> containsClauses = new ArrayList<>();
        String currentOperator = "AND";
        boolean negateNext = false;
        
        for (String token : tokens) {
            if (token.equalsIgnoreCase("AND")) {
                currentOperator = "AND";
                continue;
            } else if (token.equalsIgnoreCase("OR")) {
                currentOperator = "OR";
                continue;
            } else if (token.equalsIgnoreCase("NOT")) {
                negateNext = true;
                continue;
            }
            
            // Escape the token for CMIS
            String escapedToken = escapeSearchTerm(token);
            String containsClause = "CONTAINS('" + escapedToken + "')";
            
            if (negateNext) {
                containsClause = "NOT " + containsClause;
                negateNext = false;
            }
            
            if (containsClauses.isEmpty()) {
                containsClauses.add(containsClause);
            } else {
                // Combine with previous clause using current operator
                String lastClause = containsClauses.remove(containsClauses.size() - 1);
                containsClauses.add("(" + lastClause + " " + currentOperator + " " + containsClause + ")");
            }
            
            // Reset operator to AND (implicit)
            currentOperator = "AND";
        }
        
        if (containsClauses.isEmpty()) {
            return "CONTAINS('')";
        }
        
        return containsClauses.get(0);
    }
    
    /**
     * Tokenize a search expression into individual terms and operators.
     * Handles quoted phrases as single tokens.
     * 
     * @param searchTerm The raw search expression
     * @return List of tokens (terms and operators)
     */
    private List<String> tokenizeSearchExpression(String searchTerm) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < searchTerm.length(); i++) {
            char c = searchTerm.charAt(i);
            
            if (c == '"') {
                if (inQuotes) {
                    // End of quoted phrase - include the quotes
                    currentToken.append(c);
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                    inQuotes = false;
                } else {
                    // Start of quoted phrase
                    if (currentToken.length() > 0) {
                        tokens.add(currentToken.toString());
                        currentToken = new StringBuilder();
                    }
                    currentToken.append(c);
                    inQuotes = true;
                }
            } else if (Character.isWhitespace(c) && !inQuotes) {
                // End of token (if not in quotes)
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
            } else {
                currentToken.append(c);
            }
        }
        
        // Add the last token if any
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }
        
        return tokens;
    }
    
    /**
     * Escape special characters in search term for CMIS CONTAINS() function.
     * 
     * CMIS full-text search has specific syntax requirements:
     * - Single quotes must be doubled (' -> '')
     * - Backslashes must be escaped (\ -> \\)
     * - Colons, asterisks, and question marks are escaped
     * 
     * Note: Double quotes are preserved for phrase matching.
     * 
     * @param searchTerm The raw search term from OData $search
     * @return The escaped search term safe for CMIS CONTAINS()
     */
    private String escapeSearchTerm(String searchTerm) {
        if (searchTerm == null) {
            return null;
        }
        
        // Escape backslashes first (must be done before other escapes)
        String escaped = searchTerm.replace("\\", "\\\\");
        
        // Escape single quotes (CMIS string delimiter)
        escaped = escaped.replace("'", "''");
        
        // Remove or escape characters that could be interpreted as CMIS query operators
        // Colons can be problematic in some full-text search implementations
        escaped = escaped.replace(":", "\\:");
        
        // Asterisks and question marks are wildcards in some implementations
        escaped = escaped.replace("*", "\\*");
        escaped = escaped.replace("?", "\\?");
        
        return escaped;
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
    private Entity convertToEntity(ObjectData objectData, Set<String> selectedProperties, String baseUri, String entitySetName) {
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
        
        // Set the entity ID as absolute URI (OData spec requirement)
        // Format: {baseUri}/{EntitySet}('{objectId}')
        String objectId = getPropertyValue(objectData, "cmis:objectId");
        if (objectId != null && baseUri != null && entitySetName != null) {
            // Encode the object ID for URL safety (handles /, ?, #, &, =, etc.)
            String encodedId = encodeODataKeyValue(objectId);
            String absoluteUri = baseUri + "/" + entitySetName + "('" + encodedId + "')";
            entity.setId(URI.create(absoluteUri));
        } else if (objectId != null) {
            // Fallback to just the object ID if base URI is not available
            entity.setId(URI.create(encodeODataKeyValue(objectId)));
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
     * Overloaded convertToEntity for backward compatibility (used in expand methods).
     * Uses just the object ID without absolute URI format.
     */
    private Entity convertToEntity(ObjectData objectData, Set<String> selectedProperties) {
        return convertToEntity(objectData, selectedProperties, null, null);
    }
    
    /**
     * Encode a value for use in OData entity key (inside single quotes).
     * 
     * This handles URI reserved characters that could break the URI structure:
     * - Single quotes are doubled (' -> '')
     * - Other reserved characters (/, ?, #, &, =, etc.) are URL-encoded
     * 
     * @param value The raw value to encode
     * @return The encoded value safe for use in OData entity key
     */
    private String encodeODataKeyValue(String value) {
        if (value == null) {
            return null;
        }
        
        // First, URL-encode the value to handle reserved characters
        String encoded;
        try {
            encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported, but handle the exception anyway
            encoded = value;
        }
        
        // URL encoding converts spaces to '+', but OData expects %20
        encoded = encoded.replace("+", "%20");
        
        // Single quotes need to be doubled for OData string literals
        // Note: URLEncoder encodes ' as %27, but OData expects '' for escaping
        encoded = encoded.replace("%27", "''");
        
        return encoded;
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
    
    /**
     * Extract navigation property names from $expand option.
     * Supports: parent, parents, children, source, target
     */
    private Set<String> getExpandProperties(ExpandOption expandOption) {
        Set<String> expandProperties = new HashSet<>();
        if (expandOption != null && expandOption.getExpandItems() != null) {
            for (ExpandItem expandItem : expandOption.getExpandItems()) {
                if (expandItem.getResourcePath() != null && !expandItem.getResourcePath().getUriResourceParts().isEmpty()) {
                    UriResource uriResource = expandItem.getResourcePath().getUriResourceParts().get(0);
                    expandProperties.add(uriResource.getSegmentValue());
                }
            }
        }
        return expandProperties;
    }
    
    /**
     * Expand navigation properties for an entity based on $expand option.
     * Supports:
     * - parent: Single parent folder (for Documents and Folders)
     * - parents: Collection of parent folders (for Documents with multi-filing)
     * - children: Collection of child objects (for Folders)
     * - source/target: Related objects (for Relationships)
     */
    private void expandNavigationProperties(Entity entity, ObjectData objectData, Set<String> expandProperties) {
        if (expandProperties == null || expandProperties.isEmpty()) {
            return;
        }
        
        Properties properties = objectData.getProperties();
        if (properties == null) {
            return;
        }
        
        // Get the base type to determine which navigation properties are valid
        PropertyData<?> baseTypeProperty = properties.getProperties().get("cmis:baseTypeId");
        String baseTypeId = baseTypeProperty != null ? (String) baseTypeProperty.getFirstValue() : null;
        
        // Get object ID for fetching related objects
        PropertyData<?> objectIdProperty = properties.getProperties().get("cmis:objectId");
        String objectId = objectIdProperty != null ? (String) objectIdProperty.getFirstValue() : null;
        
        if (objectId == null) {
            return;
        }
        
        try {
            // Handle 'parent' expansion (single parent folder)
            if (expandProperties.contains("parent")) {
                expandParentFolder(entity, properties);
            }
            
            // Handle 'parents' expansion (collection of parent folders for multi-filed documents)
            if (expandProperties.contains("parents")) {
                expandParentFolders(entity, objectId);
            }
            
            // Handle 'children' expansion (for folders only)
            if (expandProperties.contains("children") && "cmis:folder".equals(baseTypeId)) {
                expandChildren(entity, objectId);
            }
            
            // Handle 'source' and 'target' expansion (for relationships only)
            if ("cmis:relationship".equals(baseTypeId)) {
                if (expandProperties.contains("source")) {
                    expandRelationshipSource(entity, properties);
                }
                if (expandProperties.contains("target")) {
                    expandRelationshipTarget(entity, properties);
                }
            }
        } catch (Exception e) {
            // Log error but don't fail the entire request
            // Navigation property expansion is optional
        }
    }
    
    /**
     * Expand single parent folder navigation property.
     */
    private void expandParentFolder(Entity entity, Properties properties) {
        PropertyData<?> parentIdProperty = properties.getProperties().get("cmis:parentId");
        if (parentIdProperty == null || parentIdProperty.getFirstValue() == null) {
            return;
        }
        
        String parentId = (String) parentIdProperty.getFirstValue();
        
        try {
            ObjectData parentObject = objectService.getObject(
                    callContext,
                    repositoryId,
                    parentId,
                    "*",  // filter - get all properties
                    Boolean.FALSE,  // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,  // renditionFilter
                    Boolean.FALSE,  // includePolicyIds
                    Boolean.FALSE,  // includeAcl
                    null   // extension
            );
            
            if (parentObject != null) {
                Entity parentEntity = convertToEntity(parentObject, null);
                Link parentLink = new Link();
                parentLink.setTitle("parent");
                parentLink.setType("application/json");
                parentLink.setInlineEntity(parentEntity);
                entity.getNavigationLinks().add(parentLink);
            }
        } catch (Exception e) {
            // Parent not accessible or doesn't exist
        }
    }
    
    /**
     * Expand parent folders collection (for multi-filed documents).
     */
    private void expandParentFolders(Entity entity, String objectId) {
        try {
            List<ObjectParentData> parents = navigationService.getObjectParents(
                    callContext,
                    repositoryId,
                    objectId,
                    "*",  // filter
                    Boolean.FALSE,  // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,  // renditionFilter
                    Boolean.FALSE,  // includeRelativePathSegment
                    null   // extension
            );
            
            if (parents != null && !parents.isEmpty()) {
                EntityCollection parentsCollection = new EntityCollection();
                for (ObjectParentData parentData : parents) {
                    if (parentData.getObject() != null) {
                        Entity parentEntity = convertToEntity(parentData.getObject(), null);
                        parentsCollection.getEntities().add(parentEntity);
                    }
                }
                
                Link parentsLink = new Link();
                parentsLink.setTitle("parents");
                parentsLink.setType("application/json");
                parentsLink.setInlineEntitySet(parentsCollection);
                entity.getNavigationLinks().add(parentsLink);
            }
        } catch (Exception e) {
            // Parents not accessible
        }
    }
    
    /**
     * Expand children collection for folders.
     */
    private void expandChildren(Entity entity, String folderId) {
        try {
            ObjectInFolderList children = navigationService.getChildren(
                    callContext,
                    repositoryId,
                    folderId,
                    "*",  // filter
                    null,  // orderBy
                    Boolean.FALSE,  // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,  // renditionFilter
                    Boolean.FALSE,  // includePathSegment
                    BigInteger.valueOf(100),  // maxItems - limit to 100 children
                    BigInteger.ZERO,  // skipCount
                    null,  // parentObjectData
                    null   // extension
            );
            
            if (children != null && children.getObjects() != null) {
                EntityCollection childrenCollection = new EntityCollection();
                for (ObjectInFolderData childData : children.getObjects()) {
                    if (childData.getObject() != null) {
                        Entity childEntity = convertToEntity(childData.getObject(), null);
                        childrenCollection.getEntities().add(childEntity);
                    }
                }
                
                if (children.getNumItems() != null) {
                    childrenCollection.setCount(children.getNumItems().intValue());
                }
                
                Link childrenLink = new Link();
                childrenLink.setTitle("children");
                childrenLink.setType("application/json");
                childrenLink.setInlineEntitySet(childrenCollection);
                entity.getNavigationLinks().add(childrenLink);
            }
        } catch (Exception e) {
            // Children not accessible
        }
    }
    
    /**
     * Expand source object for relationships.
     */
    private void expandRelationshipSource(Entity entity, Properties properties) {
        PropertyData<?> sourceIdProperty = properties.getProperties().get("cmis:sourceId");
        if (sourceIdProperty == null || sourceIdProperty.getFirstValue() == null) {
            return;
        }
        
        String sourceId = (String) sourceIdProperty.getFirstValue();
        expandRelatedObject(entity, sourceId, "source");
    }
    
    /**
     * Expand target object for relationships.
     */
    private void expandRelationshipTarget(Entity entity, Properties properties) {
        PropertyData<?> targetIdProperty = properties.getProperties().get("cmis:targetId");
        if (targetIdProperty == null || targetIdProperty.getFirstValue() == null) {
            return;
        }
        
        String targetId = (String) targetIdProperty.getFirstValue();
        expandRelatedObject(entity, targetId, "target");
    }
    
    /**
     * Helper method to expand a related object by ID.
     */
    private void expandRelatedObject(Entity entity, String objectId, String linkTitle) {
        try {
            ObjectData relatedObject = objectService.getObject(
                    callContext,
                    repositoryId,
                    objectId,
                    "*",  // filter
                    Boolean.FALSE,  // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,  // renditionFilter
                    Boolean.FALSE,  // includePolicyIds
                    Boolean.FALSE,  // includeAcl
                    null   // extension
            );
            
            if (relatedObject != null) {
                Entity relatedEntity = convertToEntity(relatedObject, null);
                Link link = new Link();
                link.setTitle(linkTitle);
                link.setType("application/json");
                link.setInlineEntity(relatedEntity);
                entity.getNavigationLinks().add(link);
            }
        } catch (Exception e) {
            // Related object not accessible
        }
    }
}
