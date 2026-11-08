package jp.aegif.nemaki.odata;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;

import jp.aegif.nemaki.cmis.service.ObjectService;

/**
 * What a write or an action is aimed at: the entity set the URL starts with and its key, with nothing after them but the
 * entity set's own type as a cast and, for an action, the action (R261, R262, R270 — 3.4.2); an entity set whose entities are
 * CMIS objects (R272); and an entity that entity set holds (R271).
 *
 * <p>The writes and the actions read the key of the first segment and nothing else. {@code DELETE Folders('F')/children('D')}
 * deleted F, {@code PATCH Folders('F')/children('D')} updated F, {@code POST Folders('F')/children} created a folder under the
 * root rather than under F (R261); {@code POST Documents('D')/parent/NemakiWare.CMIS.ApplyAcl} replaced D's ACL and
 * {@code POST Folders('F')/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CheckOut} checked F out (R262);
 * {@code DELETE Objects('D')/NemakiWare.CMIS.Folder} deleted the document D (R270). A navigation after the key is not
 * translated, and a cast is accepted only when it is the entity set's own type; the rest is refused with a 400 before the
 * body is read or a CMIS service is called. The type of the entity the key names is not read: a cast is decided by the URL
 * alone (the owner's rule, as for R254).
 *
 * <p>The order is the URL's: the first segment has to be an entity set, a cast on it has to be its own type, and then
 * nothing may follow but the action. Olingo 5.0.0 reports a cast on that segment in {@code getTypeFilterOnCollection()}
 * (before the key) and {@code getTypeFilterOnEntry()} (after it); for these paths {@code UriInfo.getEntityTypeCast()} repeats
 * the last of the two, is null once another segment follows, and {@code $entity} is refused by Olingo for a write — so the
 * two filters are what is read (measured, 2026-11-08).
 *
 * <p>After the URL, the entity set: {@code DELETE Users('admin')} called {@code deleteObject("admin")}, and a create or an
 * update of {@code Types}, {@code Users} or {@code Groups} answered a 500 (R272). Their entities are not CMIS objects and are
 * not translated, so a create, an update or a delete of them is refused with a 400 before the body, the entity or the
 * repository is read (no action is bound to their types — Olingo refuses one before any processor).
 *
 * <p>Then the entity: {@code DELETE Folders('<document>')} deleted the document, and an update or an action took the object
 * the key names whatever its base type (R271). The entity is read with the request's call context before the body or the
 * action's parameters, and a base type its entity set does not hold is answered 404 — the URL is translated, but there is no
 * such entity in that entity set. A read that fails, or an entity whose base type cannot be read, is an error rather than a
 * 404, and nothing is written.
 */
final class WriteTarget {

    static final String NOT_AN_ENTITY_SET =
            "a write or an action is aimed only at an entity set and its key — the URL does not start with an entity set";
    static final String NAVIGATION_REFUSED =
            "a write or an action is aimed only at the entity set the URL starts with and its key — a navigation after them is not read";
    static final String CAST_REFUSED =
            "a type cast on a write or an action is accepted only for its entity set's own type, not for ";
    static final String NOT_TRANSLATED =
            "a write is refused for an entity set whose entities are not CMIS objects — it is not translated: ";
    static final String NOT_HELD =
            "a write or an action reaches only an entity its entity set holds — there is no such entity in ";
    static final String UNREADABLE = "the entity a write or an action is aimed at could not be read";

    /** The entity sets whose entities are not CMIS objects (R272). */
    private static final Set<String> NOT_TRANSLATED_SETS = Set.of(
            CmisEdmProvider.ES_TYPES_NAME, CmisEdmProvider.ES_USERS_NAME, CmisEdmProvider.ES_GROUPS_NAME);

    /** The base types of the CMIS objects, which Objects holds all of. */
    private static final Set<String> OBJECT_BASE_TYPES = Set.of(BaseTypeId.CMIS_DOCUMENT.value(),
            BaseTypeId.CMIS_FOLDER.value(), BaseTypeId.CMIS_RELATIONSHIP.value(), BaseTypeId.CMIS_POLICY.value(),
            BaseTypeId.CMIS_ITEM.value());

    /** The base types each entity set of CMIS objects holds (R271). */
    private static final Map<String, Set<String>> HELD = Map.of(
            CmisEdmProvider.ES_OBJECTS_NAME, OBJECT_BASE_TYPES,
            CmisEdmProvider.ES_DOCUMENTS_NAME, Set.of(BaseTypeId.CMIS_DOCUMENT.value()),
            CmisEdmProvider.ES_FOLDERS_NAME, Set.of(BaseTypeId.CMIS_FOLDER.value()),
            CmisEdmProvider.ES_RELATIONSHIPS_NAME, Set.of(BaseTypeId.CMIS_RELATIONSHIP.value()),
            CmisEdmProvider.ES_POLICIES_NAME, Set.of(BaseTypeId.CMIS_POLICY.value()),
            CmisEdmProvider.ES_ITEMS_NAME, Set.of(BaseTypeId.CMIS_ITEM.value()));

    private WriteTarget() {
    }

    /**
     * The entity set segment a write ({@code anActionFollows} false) or a bound action ({@code anActionFollows} true) is
     * aimed at, or a 400.
     */
    static UriResourceEntitySet aimedAt(UriInfo uriInfo, boolean anActionFollows) throws ODataApplicationException {
        List<UriResource> parts = uriInfo.getUriResourceParts();
        if (parts.isEmpty() || !(parts.get(0) instanceof UriResourceEntitySet entitySet)) {
            throw refused(NOT_AN_ENTITY_SET);
        }
        FullQualifiedName own = entitySet.getEntitySet().getEntityType().getFullQualifiedName();
        for (EdmType cast : new EdmType[] {entitySet.getTypeFilterOnCollection(), entitySet.getTypeFilterOnEntry()}) {
            if (cast != null && !own.equals(cast.getFullQualifiedName())) {
                throw refused(CAST_REFUSED + cast.getFullQualifiedName().getFullQualifiedNameAsString());
            }
        }
        if (parts.size() != (anActionFollows ? 2 : 1)) {
            throw refused(NAVIGATION_REFUSED);
        }
        return entitySet;
    }

    /** A 400 when a write is aimed at {@code Types}, {@code Users} or {@code Groups} (R272). */
    static void translated(UriResourceEntitySet target) throws ODataApplicationException {
        String name = target.getEntitySet().getName();
        if (NOT_TRANSLATED_SETS.contains(name)) {
            throw refused(NOT_TRANSLATED + name);
        }
    }

    /**
     * Reads the entity {@code objectId} names with {@code callContext} and answers a 404 when its base type is not one
     * {@code target}'s entity set holds (R271). A missing entity is the 404 {@code notFound} + {@code objectId}, the message
     * of the entry point's own not-found arm (a DELETE of a missing entity answered 204 before: {@code deleteObject} skips
     * it); a read that fails or a base type that cannot be read is an error, never a 404. The read needs the right to read
     * the entity, so a principal that may write it but not read it is refused (R274).
     */
    static void holds(UriResourceEntitySet target, String objectId, ObjectService objects, CallContext callContext,
            String repositoryId, String notFound) throws ODataApplicationException {
        String name = target.getEntitySet().getName();
        Set<String> held = HELD.get(name);
        // Types, Users and Groups do not come here: a write to them is refused by translated() and no action is bound to
        // their types. An entity set added without an entry is an error, not a write.
        if (held == null) {
            throw ODataExceptions.map(UNREADABLE,
                    new IllegalStateException("no base type is known for the entity set " + name));
        }
        ObjectData entity;
        try {
            entity = objects.getObject(callContext, repositoryId, objectId, "*", Boolean.FALSE, IncludeRelationships.NONE,
                    null, Boolean.FALSE, Boolean.FALSE, null);
        } catch (CmisObjectNotFoundException e) {
            throw new ODataApplicationException(notFound + objectId, HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH);
        } catch (RuntimeException e) {
            throw ODataExceptions.map(UNREADABLE, e);
        }
        String baseType = baseTypeOf(entity);
        if (baseType == null || !OBJECT_BASE_TYPES.contains(baseType)) {
            throw ODataExceptions.map(UNREADABLE,
                    new IllegalStateException("the base type of " + objectId + " cannot be read: " + baseType));
        }
        if (!held.contains(baseType)) {
            throw new ODataApplicationException(NOT_HELD + name + ": " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(), Locale.ENGLISH);
        }
    }

    private static String baseTypeOf(ObjectData entity) {
        if (entity == null || entity.getProperties() == null || entity.getProperties().getProperties() == null) {
            return null;
        }
        PropertyData<?> baseType = entity.getProperties().getProperties().get(PropertyIds.BASE_TYPE_ID);
        return baseType != null && baseType.getFirstValue() instanceof String value ? value : null;
    }

    private static ODataApplicationException refused(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
    }
}
