package jp.aegif.nemaki.odata;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlAbstractEdmProvider;
import org.apache.olingo.commons.api.edm.provider.CsdlAction;
import org.apache.olingo.commons.api.edm.provider.CsdlActionImport;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainer;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainerInfo;
import org.apache.olingo.commons.api.edm.provider.CsdlEntitySet;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlFunction;
import org.apache.olingo.commons.api.edm.provider.CsdlFunctionImport;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationPropertyBinding;
import org.apache.olingo.commons.api.edm.provider.CsdlParameter;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.apache.olingo.commons.api.edm.provider.CsdlReturnType;
import org.apache.olingo.commons.api.edm.provider.CsdlSchema;
import org.apache.olingo.commons.api.ex.ODataException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OData EDM (Entity Data Model) Provider for CMIS objects.
 * 
 * This provider defines the OData metadata schema that maps CMIS types
 * to OData entity types. It supports:
 * - Documents (cmis:document)
 * - Folders (cmis:folder)
 * - Relationships (cmis:relationship)
 * - Policies (cmis:policy)
 * - Items (cmis:item)
 * 
 * The schema is dynamically generated based on CMIS type definitions.
 */
public class CmisEdmProvider extends CsdlAbstractEdmProvider {
    
    // Service Namespace
    public static final String NAMESPACE = "NemakiWare.CMIS";
    
    // Entity Container
    public static final String CONTAINER_NAME = "Container";
    public static final FullQualifiedName CONTAINER = new FullQualifiedName(NAMESPACE, CONTAINER_NAME);
    
    // Entity Types
    public static final String ET_OBJECT_NAME = "Object";
    public static final FullQualifiedName ET_OBJECT_FQN = new FullQualifiedName(NAMESPACE, ET_OBJECT_NAME);
    
    public static final String ET_DOCUMENT_NAME = "Document";
    public static final FullQualifiedName ET_DOCUMENT_FQN = new FullQualifiedName(NAMESPACE, ET_DOCUMENT_NAME);
    
    public static final String ET_FOLDER_NAME = "Folder";
    public static final FullQualifiedName ET_FOLDER_FQN = new FullQualifiedName(NAMESPACE, ET_FOLDER_NAME);
    
    public static final String ET_RELATIONSHIP_NAME = "Relationship";
    public static final FullQualifiedName ET_RELATIONSHIP_FQN = new FullQualifiedName(NAMESPACE, ET_RELATIONSHIP_NAME);
    
    public static final String ET_POLICY_NAME = "Policy";
    public static final FullQualifiedName ET_POLICY_FQN = new FullQualifiedName(NAMESPACE, ET_POLICY_NAME);
    
    public static final String ET_ITEM_NAME = "Item";
    public static final FullQualifiedName ET_ITEM_FQN = new FullQualifiedName(NAMESPACE, ET_ITEM_NAME);
    
    // Entity Sets
    public static final String ES_OBJECTS_NAME = "Objects";
    public static final String ES_DOCUMENTS_NAME = "Documents";
    public static final String ES_FOLDERS_NAME = "Folders";
    public static final String ES_RELATIONSHIPS_NAME = "Relationships";
    public static final String ES_POLICIES_NAME = "Policies";
    public static final String ES_ITEMS_NAME = "Items";
    
    // Actions
    public static final String ACTION_CHECKOUT = "CheckOut";
    public static final FullQualifiedName ACTION_CHECKOUT_FQN = new FullQualifiedName(NAMESPACE, ACTION_CHECKOUT);
    
    public static final String ACTION_CANCEL_CHECKOUT = "CancelCheckOut";
    public static final FullQualifiedName ACTION_CANCEL_CHECKOUT_FQN = new FullQualifiedName(NAMESPACE, ACTION_CANCEL_CHECKOUT);
    
    public static final String ACTION_CHECKIN = "CheckIn";
    public static final FullQualifiedName ACTION_CHECKIN_FQN = new FullQualifiedName(NAMESPACE, ACTION_CHECKIN);
    
    public static final String ACTION_MOVE = "Move";
    public static final FullQualifiedName ACTION_MOVE_FQN = new FullQualifiedName(NAMESPACE, ACTION_MOVE);
    
    public static final String ACTION_COPY = "Copy";
    public static final FullQualifiedName ACTION_COPY_FQN = new FullQualifiedName(NAMESPACE, ACTION_COPY);
    
    public static final String ACTION_APPLY_ACL = "ApplyAcl";
    public static final FullQualifiedName ACTION_APPLY_ACL_FQN = new FullQualifiedName(NAMESPACE, ACTION_APPLY_ACL);
    
    // Functions
    public static final String FUNCTION_GET_ALL_VERSIONS = "GetAllVersions";
    public static final FullQualifiedName FUNCTION_GET_ALL_VERSIONS_FQN = new FullQualifiedName(NAMESPACE, FUNCTION_GET_ALL_VERSIONS);
    
    public static final String FUNCTION_GET_OBJECT_BY_PATH = "GetObjectByPath";
    public static final FullQualifiedName FUNCTION_GET_OBJECT_BY_PATH_FQN = new FullQualifiedName(NAMESPACE, FUNCTION_GET_OBJECT_BY_PATH);
    
    public static final String FUNCTION_QUERY = "Query";
    public static final FullQualifiedName FUNCTION_QUERY_FQN = new FullQualifiedName(NAMESPACE, FUNCTION_QUERY);
    
    public static final String FUNCTION_GET_CONTENT_CHANGES = "GetContentChanges";
    public static final FullQualifiedName FUNCTION_GET_CONTENT_CHANGES_FQN = new FullQualifiedName(NAMESPACE, FUNCTION_GET_CONTENT_CHANGES);
    
    @Override
    public CsdlEntityType getEntityType(FullQualifiedName entityTypeName) throws ODataException {
        if (entityTypeName.equals(ET_OBJECT_FQN)) {
            return createObjectEntityType();
        } else if (entityTypeName.equals(ET_DOCUMENT_FQN)) {
            return createDocumentEntityType();
        } else if (entityTypeName.equals(ET_FOLDER_FQN)) {
            return createFolderEntityType();
        } else if (entityTypeName.equals(ET_RELATIONSHIP_FQN)) {
            return createRelationshipEntityType();
        } else if (entityTypeName.equals(ET_POLICY_FQN)) {
            return createPolicyEntityType();
        } else if (entityTypeName.equals(ET_ITEM_FQN)) {
            return createItemEntityType();
        }
        return null;
    }
    
    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) throws ODataException {
        if (entityContainer.equals(CONTAINER)) {
            if (entitySetName.equals(ES_OBJECTS_NAME)) {
                return createEntitySet(ES_OBJECTS_NAME, ET_OBJECT_FQN);
            } else if (entitySetName.equals(ES_DOCUMENTS_NAME)) {
                return createEntitySet(ES_DOCUMENTS_NAME, ET_DOCUMENT_FQN);
            } else if (entitySetName.equals(ES_FOLDERS_NAME)) {
                return createEntitySet(ES_FOLDERS_NAME, ET_FOLDER_FQN);
            } else if (entitySetName.equals(ES_RELATIONSHIPS_NAME)) {
                return createEntitySet(ES_RELATIONSHIPS_NAME, ET_RELATIONSHIP_FQN);
            } else if (entitySetName.equals(ES_POLICIES_NAME)) {
                return createEntitySet(ES_POLICIES_NAME, ET_POLICY_FQN);
            } else if (entitySetName.equals(ES_ITEMS_NAME)) {
                return createEntitySet(ES_ITEMS_NAME, ET_ITEM_FQN);
            }
        }
        return null;
    }
    
    @Override
    public CsdlEntityContainer getEntityContainer() throws ODataException {
        List<CsdlEntitySet> entitySets = new ArrayList<>();
        entitySets.add(getEntitySet(CONTAINER, ES_OBJECTS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_DOCUMENTS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_FOLDERS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_RELATIONSHIPS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_POLICIES_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_ITEMS_NAME));
        
        CsdlEntityContainer entityContainer = new CsdlEntityContainer();
        entityContainer.setName(CONTAINER_NAME);
        entityContainer.setEntitySets(entitySets);
        
        return entityContainer;
    }
    
    @Override
    public CsdlEntityContainerInfo getEntityContainerInfo(FullQualifiedName entityContainerName) throws ODataException {
        if (entityContainerName == null || entityContainerName.equals(CONTAINER)) {
            CsdlEntityContainerInfo entityContainerInfo = new CsdlEntityContainerInfo();
            entityContainerInfo.setContainerName(CONTAINER);
            return entityContainerInfo;
        }
        return null;
    }
    
    @Override
    public List<CsdlSchema> getSchemas() throws ODataException {
        CsdlSchema schema = new CsdlSchema();
        schema.setNamespace(NAMESPACE);
        
        List<CsdlEntityType> entityTypes = new ArrayList<>();
        entityTypes.add(getEntityType(ET_OBJECT_FQN));
        entityTypes.add(getEntityType(ET_DOCUMENT_FQN));
        entityTypes.add(getEntityType(ET_FOLDER_FQN));
        entityTypes.add(getEntityType(ET_RELATIONSHIP_FQN));
        entityTypes.add(getEntityType(ET_POLICY_FQN));
        entityTypes.add(getEntityType(ET_ITEM_FQN));
        schema.setEntityTypes(entityTypes);
        
        // Add actions
        List<CsdlAction> actions = getActions();
        schema.setActions(actions);
        
        // Add functions
        List<CsdlFunction> functions = getFunctions();
        schema.setFunctions(functions);
        
        schema.setEntityContainer(getEntityContainer());
        
        return Collections.singletonList(schema);
    }
    
    @Override
    public List<CsdlFunction> getFunctions(FullQualifiedName functionName) throws ODataException {
        if (functionName.equals(FUNCTION_GET_ALL_VERSIONS_FQN)) {
            return Collections.singletonList(createGetAllVersionsFunction());
        } else if (functionName.equals(FUNCTION_GET_OBJECT_BY_PATH_FQN)) {
            return Collections.singletonList(createGetObjectByPathFunction());
        } else if (functionName.equals(FUNCTION_QUERY_FQN)) {
            return Collections.singletonList(createQueryFunction());
        } else if (functionName.equals(FUNCTION_GET_CONTENT_CHANGES_FQN)) {
            return Collections.singletonList(createGetContentChangesFunction());
        }
        return null;
    }
    
    /**
     * Get all functions for the schema.
     */
    private List<CsdlFunction> getFunctions() {
        List<CsdlFunction> functions = new ArrayList<>();
        functions.add(createGetAllVersionsFunction());
        functions.add(createGetObjectByPathFunction());
        functions.add(createQueryFunction());
        functions.add(createGetContentChangesFunction());
        return functions;
    }
    
    @Override
    public List<CsdlAction> getActions(FullQualifiedName actionName) throws ODataException {
        if (actionName.equals(ACTION_CHECKOUT_FQN)) {
            return Collections.singletonList(createCheckOutAction());
        } else if (actionName.equals(ACTION_CANCEL_CHECKOUT_FQN)) {
            return Collections.singletonList(createCancelCheckOutAction());
        } else if (actionName.equals(ACTION_CHECKIN_FQN)) {
            return Collections.singletonList(createCheckInAction());
        } else if (actionName.equals(ACTION_MOVE_FQN)) {
            return Collections.singletonList(createMoveAction());
        } else if (actionName.equals(ACTION_COPY_FQN)) {
            return Collections.singletonList(createCopyAction());
        } else if (actionName.equals(ACTION_APPLY_ACL_FQN)) {
            return Collections.singletonList(createApplyAclAction());
        }
        return null;
    }
    
    /**
     * Get all actions for the schema.
     */
    private List<CsdlAction> getActions() {
        List<CsdlAction> actions = new ArrayList<>();
        actions.add(createCheckOutAction());
        actions.add(createCancelCheckOutAction());
        actions.add(createCheckInAction());
        actions.add(createMoveAction());
        actions.add(createCopyAction());
        actions.add(createApplyAclAction());
        return actions;
    }
    
    /**
     * Create the CheckOut action bound to Document.
     * POST /odata/{repoId}/Documents('objectId')/NemakiWare.CMIS.CheckOut
     * Returns the Private Working Copy (PWC) document.
     */
    private CsdlAction createCheckOutAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_CHECKOUT);
        action.setBound(true);
        
        // Binding parameter (the document to check out)
        List<CsdlParameter> parameters = new ArrayList<>();
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_DOCUMENT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        action.setParameters(parameters);
        
        // Return type (the PWC document)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_DOCUMENT_FQN);
        returnType.setNullable(false);
        action.setReturnType(returnType);
        
        return action;
    }
    
    /**
     * Create the CancelCheckOut action bound to Document.
     * POST /odata/{repoId}/Documents('objectId')/NemakiWare.CMIS.CancelCheckOut
     * Cancels the check out and deletes the PWC.
     */
    private CsdlAction createCancelCheckOutAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_CANCEL_CHECKOUT);
        action.setBound(true);
        
        // Binding parameter (the PWC to cancel)
        List<CsdlParameter> parameters = new ArrayList<>();
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_DOCUMENT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        action.setParameters(parameters);
        
        // No return type (void action)
        return action;
    }
    
    /**
     * Create the CheckIn action bound to Document.
     * POST /odata/{repoId}/Documents('objectId')/NemakiWare.CMIS.CheckIn
     * Parameters: major (boolean), checkinComment (string)
     * Returns the new version of the document.
     */
    private CsdlAction createCheckInAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_CHECKIN);
        action.setBound(true);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // Binding parameter (the PWC to check in)
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_DOCUMENT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        
        // major parameter
        CsdlParameter majorParam = new CsdlParameter();
        majorParam.setName("major");
        majorParam.setType(EdmPrimitiveTypeKind.Boolean.getFullQualifiedName());
        majorParam.setNullable(true);
        parameters.add(majorParam);
        
        // checkinComment parameter
        CsdlParameter commentParam = new CsdlParameter();
        commentParam.setName("checkinComment");
        commentParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        commentParam.setNullable(true);
        parameters.add(commentParam);
        
        action.setParameters(parameters);
        
        // Return type (the new version)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_DOCUMENT_FQN);
        returnType.setNullable(false);
        action.setReturnType(returnType);
        
        return action;
    }
    
    /**
     * Create the Move action bound to Object.
     * POST /odata/{repoId}/Objects('objectId')/NemakiWare.CMIS.Move
     * Parameters: targetFolderId (string), sourceFolderId (string, optional)
     * Returns the moved object.
     */
    private CsdlAction createMoveAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_MOVE);
        action.setBound(true);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // Binding parameter (the object to move)
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_OBJECT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        
        // targetFolderId parameter (required)
        CsdlParameter targetParam = new CsdlParameter();
        targetParam.setName("targetFolderId");
        targetParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        targetParam.setNullable(false);
        parameters.add(targetParam);
        
        // sourceFolderId parameter (optional)
        CsdlParameter sourceParam = new CsdlParameter();
        sourceParam.setName("sourceFolderId");
        sourceParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        sourceParam.setNullable(true);
        parameters.add(sourceParam);
        
        action.setParameters(parameters);
        
        // Return type (the moved object)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_OBJECT_FQN);
        returnType.setNullable(false);
        action.setReturnType(returnType);
        
        return action;
    }
    
    /**
     * Create the Copy action bound to Document.
     * POST /odata/{repoId}/Documents('objectId')/NemakiWare.CMIS.Copy
     * Parameters: targetFolderId (string), versioningState (string, optional)
     * Returns the copied document.
     */
    private CsdlAction createCopyAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_COPY);
        action.setBound(true);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // Binding parameter (the document to copy)
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_DOCUMENT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        
        // targetFolderId parameter (required)
        CsdlParameter targetParam = new CsdlParameter();
        targetParam.setName("targetFolderId");
        targetParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        targetParam.setNullable(false);
        parameters.add(targetParam);
        
        // versioningState parameter (optional: none, major, minor, checkedout)
        CsdlParameter versioningParam = new CsdlParameter();
        versioningParam.setName("versioningState");
        versioningParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        versioningParam.setNullable(true);
        parameters.add(versioningParam);
        
        action.setParameters(parameters);
        
        // Return type (the copied document)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_DOCUMENT_FQN);
        returnType.setNullable(false);
        action.setReturnType(returnType);
        
        return action;
    }
    
    /**
     * Create the ApplyAcl action bound to Object.
     * POST /odata/{repoId}/Objects('objectId')/NemakiWare.CMIS.ApplyAcl
     * Parameters: addAces (string JSON), removeAces (string JSON), aclPropagation (string)
     * Returns the updated object.
     */
    private CsdlAction createApplyAclAction() {
        CsdlAction action = new CsdlAction();
        action.setName(ACTION_APPLY_ACL);
        action.setBound(true);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // Binding parameter (the object to apply ACL to)
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_OBJECT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        
        // addAces parameter (JSON array of ACEs to add)
        CsdlParameter addAcesParam = new CsdlParameter();
        addAcesParam.setName("addAces");
        addAcesParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        addAcesParam.setNullable(true);
        parameters.add(addAcesParam);
        
        // removeAces parameter (JSON array of ACEs to remove)
        CsdlParameter removeAcesParam = new CsdlParameter();
        removeAcesParam.setName("removeAces");
        removeAcesParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        removeAcesParam.setNullable(true);
        parameters.add(removeAcesParam);
        
        // aclPropagation parameter (repositorydetermined, objectonly, propagate)
        CsdlParameter propagationParam = new CsdlParameter();
        propagationParam.setName("aclPropagation");
        propagationParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        propagationParam.setNullable(true);
        parameters.add(propagationParam);
        
        action.setParameters(parameters);
        
        // Return type (the updated object)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_OBJECT_FQN);
        returnType.setNullable(false);
        action.setReturnType(returnType);
        
        return action;
    }
    
    /**
     * Create the GetAllVersions function bound to Document.
     * GET /odata/{repoId}/Documents('objectId')/NemakiWare.CMIS.GetAllVersions()
     * Returns a collection of all versions of the document.
     */
    private CsdlFunction createGetAllVersionsFunction() {
        CsdlFunction function = new CsdlFunction();
        function.setName(FUNCTION_GET_ALL_VERSIONS);
        function.setBound(true);
        
        // Binding parameter (the document to get versions for)
        List<CsdlParameter> parameters = new ArrayList<>();
        CsdlParameter bindingParam = new CsdlParameter();
        bindingParam.setName("bindingParameter");
        bindingParam.setType(ET_DOCUMENT_FQN);
        bindingParam.setNullable(false);
        parameters.add(bindingParam);
        function.setParameters(parameters);
        
        // Return type (collection of documents)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_DOCUMENT_FQN);
        returnType.setCollection(true);
        returnType.setNullable(false);
        function.setReturnType(returnType);
        
        return function;
    }
    
    /**
     * Create the GetObjectByPath function (unbound).
     * GET /odata/{repoId}/GetObjectByPath(path='/folder/document.pdf')
     * Returns the object at the specified path.
     */
    private CsdlFunction createGetObjectByPathFunction() {
        CsdlFunction function = new CsdlFunction();
        function.setName(FUNCTION_GET_OBJECT_BY_PATH);
        function.setBound(false);
        
        // path parameter (required)
        List<CsdlParameter> parameters = new ArrayList<>();
        CsdlParameter pathParam = new CsdlParameter();
        pathParam.setName("path");
        pathParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        pathParam.setNullable(false);
        parameters.add(pathParam);
        function.setParameters(parameters);
        
        // Return type (the object at the path)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_OBJECT_FQN);
        returnType.setNullable(true);
        function.setReturnType(returnType);
        
        return function;
    }
    
    /**
     * Create the Query function (unbound).
     * GET /odata/{repoId}/Query(statement='SELECT * FROM cmis:document',maxItems=100)
     * Executes a CMIS query and returns the results.
     */
    private CsdlFunction createQueryFunction() {
        CsdlFunction function = new CsdlFunction();
        function.setName(FUNCTION_QUERY);
        function.setBound(false);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // statement parameter (required)
        CsdlParameter statementParam = new CsdlParameter();
        statementParam.setName("statement");
        statementParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        statementParam.setNullable(false);
        parameters.add(statementParam);
        
        // searchAllVersions parameter (optional)
        CsdlParameter searchAllVersionsParam = new CsdlParameter();
        searchAllVersionsParam.setName("searchAllVersions");
        searchAllVersionsParam.setType(EdmPrimitiveTypeKind.Boolean.getFullQualifiedName());
        searchAllVersionsParam.setNullable(true);
        parameters.add(searchAllVersionsParam);
        
        // maxItems parameter (optional)
        CsdlParameter maxItemsParam = new CsdlParameter();
        maxItemsParam.setName("maxItems");
        maxItemsParam.setType(EdmPrimitiveTypeKind.Int32.getFullQualifiedName());
        maxItemsParam.setNullable(true);
        parameters.add(maxItemsParam);
        
        // skipCount parameter (optional)
        CsdlParameter skipCountParam = new CsdlParameter();
        skipCountParam.setName("skipCount");
        skipCountParam.setType(EdmPrimitiveTypeKind.Int32.getFullQualifiedName());
        skipCountParam.setNullable(true);
        parameters.add(skipCountParam);
        
        function.setParameters(parameters);
        
        // Return type (collection of objects)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_OBJECT_FQN);
        returnType.setCollection(true);
        returnType.setNullable(false);
        function.setReturnType(returnType);
        
        return function;
    }
    
    /**
     * Create the GetContentChanges function.
     * GET /odata/{repoId}/GetContentChanges(changeLogToken='token',maxItems=100)
     * Returns a list of objects that have changed since a given point in time.
     */
    private CsdlFunction createGetContentChangesFunction() {
        CsdlFunction function = new CsdlFunction();
        function.setName(FUNCTION_GET_CONTENT_CHANGES);
        function.setBound(false);
        
        // Parameters
        List<CsdlParameter> parameters = new ArrayList<>();
        
        // changeLogToken parameter (optional - null for first call)
        CsdlParameter changeLogTokenParam = new CsdlParameter();
        changeLogTokenParam.setName("changeLogToken");
        changeLogTokenParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        changeLogTokenParam.setNullable(true);
        parameters.add(changeLogTokenParam);
        
        // includeProperties parameter (optional)
        CsdlParameter includePropertiesParam = new CsdlParameter();
        includePropertiesParam.setName("includeProperties");
        includePropertiesParam.setType(EdmPrimitiveTypeKind.Boolean.getFullQualifiedName());
        includePropertiesParam.setNullable(true);
        parameters.add(includePropertiesParam);
        
        // filter parameter (optional)
        CsdlParameter filterParam = new CsdlParameter();
        filterParam.setName("filter");
        filterParam.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        filterParam.setNullable(true);
        parameters.add(filterParam);
        
        // includePolicyIds parameter (optional)
        CsdlParameter includePolicyIdsParam = new CsdlParameter();
        includePolicyIdsParam.setName("includePolicyIds");
        includePolicyIdsParam.setType(EdmPrimitiveTypeKind.Boolean.getFullQualifiedName());
        includePolicyIdsParam.setNullable(true);
        parameters.add(includePolicyIdsParam);
        
        // includeAcl parameter (optional)
        CsdlParameter includeAclParam = new CsdlParameter();
        includeAclParam.setName("includeAcl");
        includeAclParam.setType(EdmPrimitiveTypeKind.Boolean.getFullQualifiedName());
        includeAclParam.setNullable(true);
        parameters.add(includeAclParam);
        
        // maxItems parameter (optional)
        CsdlParameter maxItemsParam = new CsdlParameter();
        maxItemsParam.setName("maxItems");
        maxItemsParam.setType(EdmPrimitiveTypeKind.Int32.getFullQualifiedName());
        maxItemsParam.setNullable(true);
        parameters.add(maxItemsParam);
        
        function.setParameters(parameters);
        
        // Return type (collection of objects)
        CsdlReturnType returnType = new CsdlReturnType();
        returnType.setType(ET_OBJECT_FQN);
        returnType.setCollection(true);
        returnType.setNullable(false);
        function.setReturnType(returnType);
        
        return function;
    }
    
    /**
     * Create the base Object entity type with common CMIS properties.
     */
    private CsdlEntityType createObjectEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_OBJECT_NAME);
        
        // Key property
        CsdlPropertyRef propertyRef = new CsdlPropertyRef();
        propertyRef.setName("objectId");
        entityType.setKey(Collections.singletonList(propertyRef));
        
        // Properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("objectId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("objectTypeId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("baseTypeId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("name", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("description", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("createdBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("creationDate", EdmPrimitiveTypeKind.DateTimeOffset, true));
        properties.add(createProperty("lastModifiedBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("lastModificationDate", EdmPrimitiveTypeKind.DateTimeOffset, true));
        properties.add(createProperty("changeToken", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        return entityType;
    }
    
    /**
     * Create the Document entity type extending Object with document-specific properties.
     */
    private CsdlEntityType createDocumentEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_DOCUMENT_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Document-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("isImmutable", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isLatestVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isMajorVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isLatestMajorVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isPrivateWorkingCopy", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("versionLabel", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("versionSeriesId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("isVersionSeriesCheckedOut", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("versionSeriesCheckedOutBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("versionSeriesCheckedOutId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("checkinComment", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamLength", EdmPrimitiveTypeKind.Int64, true));
        properties.add(createProperty("contentStreamMimeType", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamFileName", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamId", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("parent", ET_FOLDER_FQN, false));
        navProperties.add(createNavigationProperty("parents", ET_FOLDER_FQN, true));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Folder entity type extending Object with folder-specific properties.
     */
    private CsdlEntityType createFolderEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_FOLDER_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Folder-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("parentId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("path", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("parent", ET_FOLDER_FQN, false));
        navProperties.add(createNavigationProperty("children", ET_OBJECT_FQN, true));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Relationship entity type with source/target references.
     */
    private CsdlEntityType createRelationshipEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_RELATIONSHIP_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Relationship-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("sourceId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("targetId", EdmPrimitiveTypeKind.String, false));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("source", ET_OBJECT_FQN, false));
        navProperties.add(createNavigationProperty("target", ET_OBJECT_FQN, false));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Policy entity type with policy-specific properties.
     */
    private CsdlEntityType createPolicyEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_POLICY_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Policy-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("policyText", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        return entityType;
    }
    
    /**
     * Create the Item entity type (generic CMIS item).
     */
    private CsdlEntityType createItemEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_ITEM_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        return entityType;
    }
    
    /**
     * Helper method to create a property.
     */
    private CsdlProperty createProperty(String name, EdmPrimitiveTypeKind type, boolean nullable) {
        return new CsdlProperty()
                .setName(name)
                .setType(type.getFullQualifiedName())
                .setNullable(nullable);
    }
    
    /**
     * Helper method to create a navigation property.
     */
    private CsdlNavigationProperty createNavigationProperty(String name, FullQualifiedName type, boolean isCollection) {
        return new CsdlNavigationProperty()
                .setName(name)
                .setType(type)
                .setCollection(isCollection)
                .setNullable(true);
    }
    
    /**
     * Helper method to create an entity set.
     */
    private CsdlEntitySet createEntitySet(String name, FullQualifiedName entityType) {
        CsdlEntitySet entitySet = new CsdlEntitySet();
        entitySet.setName(name);
        entitySet.setType(entityType);
        return entitySet;
    }
}
