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
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmType;
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
import org.apache.olingo.server.api.uri.UriResourceFunction;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.UriParameter;
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

    // A function/function-import returning a collection is dispatched by Olingo
    // to this same EntityCollectionProcessor. Only one processor can be
    // registered per Olingo interface, so instead of registering
    // CmisFunctionProcessor as a second EntityCollectionProcessor (which would
    // clobber this one), we delegate function URIs to it.
    private CmisFunctionProcessor functionProcessor;

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

    /** Inject the delegate that handles function(-import) collection URIs. */
    public void setFunctionProcessor(CmisFunctionProcessor functionProcessor) {
        this.functionProcessor = functionProcessor;
    }

    @Override
    public void readEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {

        // Get the entity set from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();

        // Olingo parses the $id of $entity/NemakiWare.CMIS.Policy?$id=Objects again and keeps the cast in the UriInfo —
        // the type the $id answers when none is written. It was not read, so every entity of the set was answered,
        // documents for a type that has none (R253, 3.4.2). A cast is answered only when it is the type of what the $id
        // answers: the entity set, the navigation or the function at the end of its path. It is read before a function
        // is handed on, since the function processor does not read it. Only a $entity request is held to it: Olingo
        // also puts a path's own cast (Objects/NemakiWare.CMIS.Policy) there, and that is the guard below (R250).
        EdmEntityType entityCast = uriInfo.getEntityTypeCast();
        if (uriInfo.getKind() == org.apache.olingo.server.api.uri.UriInfoKind.entityId && entityCast != null) {
            EdmType answered = typeAnsweredBy(resourcePaths);
            if (answered == null || !answered.getFullQualifiedName().equals(entityCast.getFullQualifiedName())) {
                throw new ODataApplicationException(
                        "a type cast on $entity is answered only for the type its $id answers, not for "
                                + entityCast.getFullQualifiedName().getFullQualifiedNameAsString(),
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
            }
        }

        // A function (or unbound function import) that returns a collection is
        // dispatched here by Olingo as well; hand it to the function processor
        // instead of treating the first path segment as an entity set.
        if (functionProcessor != null) {
            for (UriResource part : resourcePaths) {
                if (part instanceof UriResourceFunction) {
                    functionProcessor.readEntityCollection(request, response, uriInfo, responseFormat);
                    return;
                }
            }
        }

        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();

        // Olingo keeps the cast of Objects/NemakiWare.CMIS.Policy on the entity set segment. Read the entity set alone,
        // the cast was dropped and every entity of the set was answered, of the cast's type or not — every document
        // for a type that has none (R250, 3.4.2). A cast is answered only when it is the entity set's own type, where
        // it changes nothing; no other is narrowed to, so it is refused.
        EdmType collectionCast = uriResourceEntitySet.getTypeFilterOnCollection();
        if (collectionCast != null
                && !edmEntitySet.getEntityType().getFullQualifiedName().equals(collectionCast.getFullQualifiedName())) {
            throw new ODataApplicationException(
                    "a type cast on the entity set is answered only for the entity set's own type, not for "
                            + collectionCast.getFullQualifiedName().getFullQualifiedNameAsString(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }

        // A $skiptoken was neither read nor refused, so the first page was answered for it (R256, 3.4.2). The next
        // link this processor writes pages with $skip, so a $skiptoken is refused on both readers.
        if (uriInfo.getSkipTokenOption() != null) {
            throw new ODataApplicationException(SKIPTOKEN_NOT_TRANSLATED, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }

        // Neither the key nor a navigation segment after it was read: Folders('F')/children answered every folder,
        // Documents('D')/parents every document, and a key that names nothing the same (R251, 3.4.2). Only the two a
        // CMIS navigation answers are read — a folder's children and a document's parents.
        if (resourcePaths.size() > 1 || !uriResourceEntitySet.getKeyPredicates().isEmpty()) {
            readNavigation(request, response, uriInfo, responseFormat, resourcePaths, uriResourceEntitySet, edmEntitySet);
            return;
        }

        // Types, Users and Groups have no CMIS query behind them: the listing made none and answered an empty set with a
        // count of 0, though the repository has types, users and groups (R257, 3.4.2). Their contents are not translated;
        // the listing is refused.
        if (getBaseTypeForEntitySet(edmEntitySet.getName()) == null) {
            throw new ODataApplicationException("the entity set " + edmEntitySet.getName() + " is not translated",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
        }

        // Get pagination options
        int top = getTopOption(uriInfo);
        int skip = getSkipOption(uriInfo);

        // Get filter option
        FilterOption filterOption = uriInfo.getFilterOption();
        String filterClause = null;
        if (filterOption != null) {
            filterClause = convertFilterToWhereClause(filterOption, edmEntitySet.getEntityType());
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
        Page page = new Page(top);
        EntityCollection entityCollection = getData(edmEntitySet, top, skip, filterClause, orderByClause, selectedProperties, expandProperties, searchTerm, baseUri, page);
        finishPage(entityCollection, page, uriInfo, request, top, skip);
        
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
    
    private static final String NAVIGATION_REFUSED =
            "a path with a key or a navigation is answered only for Folders('<id>')/children and Documents('<id>')/parents";
    private static final String NAVIGATION_NOT_TRANSLATED =
            "$filter, $search, $orderby, $apply and $expand are not translated on a navigation path";

    /**
     * Answer a path with a key or a navigation segment (R251, 3.4.2): {@code Folders('<id>')/children} with the children
     * of that folder and {@code Documents('<id>')/parents} with the parents of that document. Any other such path is
     * refused (400), a key that names no entity of the set is not found (404), and a query option this path cannot
     * translate is refused rather than dropped.
     */
    private void readNavigation(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat,
            List<UriResource> resourcePaths, UriResourceEntitySet entitySetSegment, EdmEntitySet edmEntitySet)
            throws ODataApplicationException, SerializerException {
        String setName = edmEntitySet.getName();
        UriResourceNavigation navigation = resourcePaths.size() == 2 && resourcePaths.get(1) instanceof UriResourceNavigation
                ? (UriResourceNavigation) resourcePaths.get(1) : null;
        String navigationName = navigation != null ? navigation.getProperty().getName() : null;
        boolean children = CmisEdmProvider.ES_FOLDERS_NAME.equals(setName) && "children".equals(navigationName);
        boolean parents = CmisEdmProvider.ES_DOCUMENTS_NAME.equals(setName) && "parents".equals(navigationName);
        if (!(children || parents)
                || entitySetSegment.getKeyPredicates().size() != 1
                || !isAbsentOrOwnType(entitySetSegment.getTypeFilterOnEntry(), edmEntitySet.getEntityType())
                || !navigation.getKeyPredicates().isEmpty()
                || navigation.getTypeFilterOnEntry() != null
                || !isAbsentOrOwnType(navigation.getTypeFilterOnCollection(), navigation.getProperty().getType())) {
            throw new ODataApplicationException(NAVIGATION_REFUSED, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }
        if (uriInfo.getFilterOption() != null || uriInfo.getSearchOption() != null
                || uriInfo.getOrderByOption() != null || uriInfo.getApplyOption() != null
                || uriInfo.getExpandOption() != null) {
            throw new ODataApplicationException(NAVIGATION_NOT_TRANSLATED, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }
        UriParameter keyPredicate = entitySetSegment.getKeyPredicates().get(0);
        String key = stringKey(keyPredicate);

        // The key must name an entity of the set: a folder for Folders, a document for Documents.
        ObjectData keyed;
        try {
            keyed = objectService.getObject(callContext, repositoryId, key, "cmis:objectId,cmis:baseTypeId",
                    Boolean.FALSE, IncludeRelationships.NONE, null, Boolean.FALSE, Boolean.FALSE, null);
        } catch (Exception e) {
            throw ODataExceptions.map("Error reading " + setName, e);
        }
        String wanted = children ? "cmis:folder" : "cmis:document";
        if (keyed == null || !wanted.equals(getPropertyValue(keyed, "cmis:baseTypeId"))) {
            throw new ODataApplicationException("Entity not found: " + key, HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH);
        }

        int top = getTopOption(uriInfo);
        int skip = getSkipOption(uriInfo);
        SelectOption selectOption = uriInfo.getSelectOption();
        Set<String> selectedProperties = selectOption != null ? getSelectedProperties(selectOption) : null;
        String baseUri = request.getRawBaseUri();
        EntityCollection entityCollection = new EntityCollection();
        Page page = new Page(top);
        try {
            if (children) {
                ObjectInFolderList list = navigationService.getChildren(callContext, repositoryId, key, "*", null,
                        Boolean.TRUE, IncludeRelationships.NONE, null, Boolean.FALSE, BigInteger.valueOf(top),
                        BigInteger.valueOf(skip), new Holder<ObjectData>(), null);
                if (list != null && list.getObjects() != null) {
                    for (ObjectInFolderData child : list.getObjects()) {
                        if (child.getObject() != null) {
                            entityCollection.getEntities().add(navigationEntity(child.getObject(), selectedProperties, baseUri));
                        }
                    }
                }
                if (list == null) {
                    page.complete = true;
                } else {
                    page.hasMoreItems = list.hasMoreItems();
                    page.numItems = list.getNumItems();
                }
            } else {
                List<ObjectParentData> list = navigationService.getObjectParents(callContext, repositoryId, key, "*",
                        Boolean.TRUE, IncludeRelationships.NONE, null, Boolean.FALSE, null);
                List<ObjectData> all = new ArrayList<>();
                if (list != null) {
                    for (ObjectParentData parent : list) {
                        if (parent.getObject() != null) {
                            all.add(parent.getObject());
                        }
                    }
                }
                long end = Math.min((long) all.size(), (long) skip + top);
                for (int i = skip; i < end; i++) {
                    entityCollection.getEntities().add(navigationEntity(all.get(i), selectedProperties, baseUri));
                }
                page.numItems = BigInteger.valueOf(all.size());
                page.hasMoreItems = end < all.size();
            }
        } catch (Exception e) {
            throw ODataExceptions.map("Error fetching data", e);
        }
        finishPage(entityCollection, page, uriInfo, request, top, skip);

        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).keyPath(keyPredicate.getText())
                .navOrPropertyPath(navigationName).build();
        EntityCollectionSerializerOptions opts = EntityCollectionSerializerOptions.with()
                .id(baseUri + "/" + setName + "(" + keyPredicate.getText() + ")/" + navigationName)
                .contextURL(contextUrl)
                .count(uriInfo.getCountOption())
                .select(selectOption)
                .build();
        SerializerResult serializerResult = odata.createSerializer(responseFormat)
                .entityCollection(serviceMetadata, navigation.getProperty().getType(), entityCollection, opts);
        response.setContent(serializerResult.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }

    private static boolean isAbsentOrOwnType(EdmType cast, EdmType own) {
        return cast == null || own.getFullQualifiedName().equals(cast.getFullQualifiedName());
    }

    /** The declared type of what a path answers: its last entity set, navigation or function; null for anything else. */
    private static EdmType typeAnsweredBy(List<UriResource> resourcePaths) {
        UriResource last = resourcePaths.isEmpty() ? null : resourcePaths.get(resourcePaths.size() - 1);
        if (last instanceof UriResourceEntitySet) {
            return ((UriResourceEntitySet) last).getEntitySet().getEntityType();
        }
        if (last instanceof UriResourceNavigation) {
            return ((UriResourceNavigation) last).getProperty().getType();
        }
        if (last instanceof UriResourceFunction) {
            org.apache.olingo.commons.api.edm.EdmFunction function = ((UriResourceFunction) last).getFunction();
            return function != null && function.getReturnType() != null ? function.getReturnType().getType() : null;
        }
        return null;
    }

    /** The value of a string key, its quotes taken off and a doubled quote read as one. */
    private static String stringKey(UriParameter keyPredicate) throws ODataApplicationException {
        String text = keyPredicate.getText();
        if (text == null || text.length() < 2 || !text.startsWith("'") || !text.endsWith("'")) {
            throw new ODataApplicationException(NAVIGATION_REFUSED, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }
        return text.substring(1, text.length() - 1).replace("''", "'");
    }

    /** An entity of a navigation's answer, its id written under the entity set of its base type. */
    private Entity navigationEntity(ObjectData objectData, Set<String> selectedProperties, String baseUri) {
        String baseTypeId = getPropertyValue(objectData, "cmis:baseTypeId");
        String entitySetName;
        if ("cmis:folder".equals(baseTypeId)) {
            entitySetName = CmisEdmProvider.ES_FOLDERS_NAME;
        } else if ("cmis:document".equals(baseTypeId)) {
            entitySetName = CmisEdmProvider.ES_DOCUMENTS_NAME;
        } else {
            entitySetName = CmisEdmProvider.ES_OBJECTS_NAME;
        }
        return convertToEntity(objectData, selectedProperties, baseUri, entitySetName);
    }

    /**
     * Get data from CMIS based on the entity set name.
     */
    private EntityCollection getData(EdmEntitySet edmEntitySet, int maxItems, int skipCount, 
            String filterClause, String orderByClause, Set<String> selectedProperties, Set<String> expandProperties, String searchTerm, String baseUri,
            Page page) throws ODataApplicationException {
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
                    
                }
                if (objectList == null) {
                    page.complete = true;
                } else {
                    page.hasMoreItems = objectList.hasMoreItems();
                    page.numItems = objectList.getNumItems();
                }
            } else {
                page.complete = true;
            }
        } catch (Exception e) {
            // Map CMIS exceptions to correct HTTP status; a genuine 500 is logged
            // with a correlation id rather than echoing the internal message.
            throw ODataExceptions.map("Error fetching data", e);
        }

        return entityCollection;
    }

    private static final String SKIPTOKEN_NOT_TRANSLATED = "$skiptoken is not translated — page with $skip";
    private static final String COUNT_NOT_KNOWN = "the number of entities is not known, so $count=true is not answered";

    /** What the backend said of the page it answered: whether more follow and how many there are (R256, 3.4.2). */
    private static final class Page {
        final int asked;
        Boolean hasMoreItems;
        BigInteger numItems;
        /** The answer is the whole set whatever the backend reported — no query was made, or no list came back. */
        boolean complete;

        Page(int asked) {
            this.asked = asked;
        }
    }

    /**
     * Write the count only when it is known and say where an answer cut short continues (R256, 3.4.2). A listing cut at
     * the default page or at the backend's own limit used to read as the whole set; {@code $top=0} answered the backend's
     * default page; and a backend that did not know the total had the page's size written as the count.
     */
    private void finishPage(EntityCollection collection, Page page, UriInfo uriInfo, ODataRequest request, int top,
            int skip) throws ODataApplicationException {
        boolean topGiven = uriInfo.getTopOption() != null;
        int fetched = collection.getEntities().size();
        Integer count = null;
        if (page.complete) {
            count = fetched;
        } else if (page.numItems != null) {
            count = page.numItems.intValue();
        } else if (skip == 0 && Boolean.FALSE.equals(page.hasMoreItems)) {
            count = fetched;
        }
        if (count != null) {
            collection.setCount(count);
        } else if (getCountOption(uriInfo)) {
            throw new ODataApplicationException(COUNT_NOT_KNOWN, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }
        // $top=0 asks for no entity; the backend reads a page of 0 as its default page.
        if (topGiven && top == 0) {
            collection.getEntities().clear();
            return;
        }
        int returned = collection.getEntities().size();
        if (page.complete || (topGiven && returned >= top)) {
            return;
        }
        boolean more = Boolean.TRUE.equals(page.hasMoreItems)
                || (page.numItems != null && page.numItems.longValue() > (long) skip + returned)
                || (page.hasMoreItems == null && page.numItems == null && returned >= page.asked);
        if (more) {
            collection.setNext(nextPage(request, skip + returned, topGiven ? top - returned : null));
        }
    }

    /** The same request with $skip moved past what was answered, and $top lessened by it if one was given. */
    private static URI nextPage(ODataRequest request, int nextSkip, Integer remainingTop) throws ODataApplicationException {
        StringBuilder query = new StringBuilder();
        String raw = request.getRawQueryPath();
        if (raw != null) {
            for (String part : raw.split("&")) {
                if (part.isEmpty()) {
                    continue;
                }
                int eq = part.indexOf('=');
                String name = eq < 0 ? part : part.substring(0, eq);
                try {
                    name = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // left as written; it is not $skip or $top either way
                }
                if ("$skip".equals(name) || "$top".equals(name)) {
                    continue;
                }
                query.append(query.length() > 0 ? "&" : "").append(part);
            }
        }
        query.append(query.length() > 0 ? "&" : "").append("$skip=").append(nextSkip);
        if (remainingTop != null) {
            query.append("&$top=").append(remainingTop);
        }
        try {
            return URI.create(request.getRawBaseUri() + request.getRawODataPath() + "?" + query);
        } catch (IllegalArgumentException e) {
            throw new ODataApplicationException("the answer is cut short and where it continues cannot be written",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH, e);
        }
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
     * CONTAINS takes a text search expression, written inside a CMIS string literal, and both escape a backslash and a
     * single quote with a backslash — so each is escaped twice, the expression's level first (closing batch 5 review,
     * subagent P3-1: escaped once, {@code CONTAINS('it\'s')} is refused by OpenCMIS's text search lexer — a 400 —
     * and {@code CONTAINS('it\\\'s')} is answered, measured on bedroom). Colons, asterisks and question marks are
     * escaped as before. Double quotes are preserved for phrase matching.
     *
     * @param searchTerm The raw search term from OData $search
     * @return The escaped search term safe for CMIS CONTAINS()
     */
    private String escapeSearchTerm(String searchTerm) {
        if (searchTerm == null) {
            return null;
        }

        // The text search expression's own escapes: its backslash, then its quote.
        String escaped = searchTerm.replace("\\", "\\\\").replace("'", "\\'");

        // And the CMIS string literal's, over that: every backslash again, then the quote. Doubling the quote is SQL's
        // escape, which CMIS does not have: the literal ended at the second quote (closing batch 4 review, P3-3).
        escaped = escaped.replace("\\", "\\\\").replace("'", "\\'");
        
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
     *
     * @param entitySetType the type of the entity set the filter is asked of — a property cast to any other type is
     *                      refused (R249)
     */
    private String convertFilterToWhereClause(FilterOption filterOption, EdmEntityType entitySetType)
            throws ODataApplicationException {
        if (filterOption == null || filterOption.getExpression() == null) {
            return null;
        }
        
        try {
            Expression expression = filterOption.getExpression();
            return convertExpressionToCmis(expression, entitySetType);
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
    private String convertExpressionToCmis(Expression expression, EdmEntityType entitySetType)
            throws ODataApplicationException {
        if (expression == null) {
            return null;
        }
        
        String exprStr = expression.toString();
        
        // Handle binary expressions (e.g., name eq 'value')
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Binary) {
            org.apache.olingo.server.api.uri.queryoption.expression.Binary binary = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Binary) expression;
            
            String left = convertExpressionToCmis(binary.getLeftOperand(), entitySetType);
            String right = convertExpressionToCmis(binary.getRightOperand(), entitySetType);
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
                // A LIKE is written for a property and a string literal only (R248, 3.4.2). The second argument
                // used to go through the same conversion and lose its quotes if it had them: a property became its
                // name and a function Olingo's toString() — {tolower ['...']} — so the LIKE searched for that text,
                // and a function's raw literal closed the CMIS literal and wrote conditions of its own (measured:
                // every document for a filter that matches none). The decision is the expression's kind and the
                // literal's EDM type, not whether its text is quoted.
                if (!isLikeMethod(methodKind)) {
                    throw new ODataApplicationException(
                            "Unsupported method: " + methodKind,
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
                }
                if (parameters.size() != 2
                        || !(parameters.get(0) instanceof Member) || !isStringLiteral(parameters.get(1))) {
                    throw new ODataApplicationException(
                            methodKind + " is translated only with a property first and a string literal second",
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
                }
                String property = convertExpressionToCmis(parameters.get(0), entitySetType);
                String value = convertExpressionToCmis(parameters.get(1), entitySetType);
                // A string literal is written quoted by cmisLiteral; the quotes come off for the pattern. One that
                // is not is refused rather than trimmed of two characters it does not have.
                if (value == null || value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) {
                    throw new ODataApplicationException(
                            methodKind + " got a string literal that is not written quoted",
                            HttpStatusCode.BAD_REQUEST.getStatusCode(),
                            Locale.ENGLISH
                    );
                }
                String unquotedValue = value.substring(1, value.length() - 1);
                
                // The value's own % and _ are characters, not wildcards: only the ones this method adds are.
                String likeValue = unquotedValue.replace("%", "\\%").replace("_", "\\_");
                switch (methodKind) {
                    case STARTSWITH:
                        return property + " LIKE '" + likeValue + "%'";
                    case ENDSWITH:
                        return property + " LIKE '%" + likeValue + "'";
                    case CONTAINS:
                        // A substring of the property, by the same rule as startswith and endswith (3.4.2). It was
                        // written CONTAINS(property, 'value'), which CMIS does not have — CONTAINS takes a full-text
                        // expression, not a property — so OpenCMIS could not parse the WHERE clause: the server
                        // answered such a clause without its conditions up to 3.4.0 and refuses it since R188 (3.4.1).
                        return property + " LIKE '%" + likeValue + "%'";
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
            // Olingo keeps a leading type cast (NemakiWare.CMIS.Policy/name) here, not in the resource path, which
            // starts with the property. Read the path alone, the cast was dropped and the condition asked of every
            // entity, of the cast's type or not — every document for a filter that matches none (R249, 3.4.2). A
            // cast is translated only when it is the entity set's own type, where it changes nothing; Olingo refuses
            // one the entity set's type is not a base of, so it is a type derived from it that is refused here.
            org.apache.olingo.commons.api.edm.EdmType cast = member.getStartTypeFilter();
            if (cast != null && (entitySetType == null
                    || !entitySetType.getFullQualifiedName().equals(cast.getFullQualifiedName()))) {
                throw new ODataApplicationException(
                        "a type cast is translated only to the entity set's own type, not to "
                                + cast.getFullQualifiedName().getFullQualifiedNameAsString(),
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                        Locale.ENGLISH
                );
            }
            String propertyName = member.getResourcePath().getUriResourceParts().get(0).toString();
            String cmisProperty = mapODataPropertyToCmis(propertyName);
            return cmisProperty != null ? cmisProperty : propertyName;
        }
        
        // Handle literal expressions
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Literal) {
            org.apache.olingo.server.api.uri.queryoption.expression.Literal literal = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Literal) expression;
            return cmisLiteral(literal.getText());
        }
        
        // Handle unary expressions (e.g., not)
        if (expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Unary) {
            org.apache.olingo.server.api.uri.queryoption.expression.Unary unary = 
                    (org.apache.olingo.server.api.uri.queryoption.expression.Unary) expression;
            
            String operand = convertExpressionToCmis(unary.getOperand(), entitySetType);
            UnaryOperatorKind operator = unary.getOperator();
            
            if (operator == UnaryOperatorKind.NOT) {
                return "NOT (" + operand + ")";
            }
        }
        
        // Fallback: return the expression as string
        return exprStr;
    }
    
    /** The three methods written as a LIKE (R248). */
    private static boolean isLikeMethod(MethodKind methodKind) {
        return methodKind == MethodKind.STARTSWITH || methodKind == MethodKind.ENDSWITH
                || methodKind == MethodKind.CONTAINS;
    }

    /**
     * Whether the expression is a literal whose EDM type is Edm.String — the type Olingo gives a quoted literal;
     * a {@code null} literal has no type (R248).
     */
    static boolean isStringLiteral(Expression expression) {
        if (!(expression instanceof org.apache.olingo.server.api.uri.queryoption.expression.Literal)) {
            return false;
        }
        org.apache.olingo.commons.api.edm.EdmType type =
                ((org.apache.olingo.server.api.uri.queryoption.expression.Literal) expression).getType();
        return type != null && type.getKind() == org.apache.olingo.commons.api.edm.constants.EdmTypeKind.PRIMITIVE
                && org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.String.getFullQualifiedName()
                        .equals(type.getFullQualifiedName());
    }

    /**
     * An OData string literal's text as a CMIS string literal. OData writes a quote inside a string by doubling it
     * ({@code 'O''Neil'}); CMIS escapes a quote and a backslash with a backslash ({@code 'O\'Neil'}). The text went into
     * the CMIS statement as OData wrote it, so a doubled quote ended the CMIS literal and a backslash escaped the
     * character after it — {@code 'a\b'} was searched as {@code ab} (closing batch 4 review, subagent P3-3).
     * Anything not quoted — a number, a boolean, a date — is returned as it is.
     */
    static String cmisLiteral(String odataText) {
        if (odataText == null || odataText.length() < 2 || !odataText.startsWith("'") || !odataText.endsWith("'")) {
            return odataText;
        }
        String value = odataText.substring(1, odataText.length() - 1).replace("''", "'");
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
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
            // Extract the bare property name. A simple $orderby=name is a Member
            // expression whose toString() is NOT the property name (it renders the
            // Olingo AST), so mapODataPropertyToCmis would miss it and the ORDER BY
            // would be silently dropped (falling back to the repository default
            // order). Pull the property name from the resource path exactly as the
            // $filter conversion does above.
            Expression expr = item.getExpression();
            String property;
            if (expr instanceof Member) {
                property = ((Member) expr).getResourcePath().getUriResourceParts().get(0).toString();
            } else {
                property = expr.toString();
            }
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
            // Parent not accessible or doesn't exist - intentionally ignored
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
                    new Holder<org.apache.chemistry.opencmis.commons.data.ObjectData>(),  // parentObjectData (out; required non-null)
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
            // Related object not accessible - intentionally ignored
        }
    }
}
