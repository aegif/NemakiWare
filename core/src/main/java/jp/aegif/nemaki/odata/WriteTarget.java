package jp.aegif.nemaki.odata;

import java.util.List;
import java.util.Locale;

import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;

/**
 * What a write or an action is aimed at: the entity set the URL starts with and its key, with nothing after them but the
 * entity set's own type as a cast and, for an action, the action (R261, R262, R270 — 3.4.2).
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
 */
final class WriteTarget {

    static final String NOT_AN_ENTITY_SET =
            "a write or an action is aimed only at an entity set and its key — the URL does not start with an entity set";
    static final String NAVIGATION_REFUSED =
            "a write or an action is aimed only at the entity set the URL starts with and its key — a navigation after them is not read";
    static final String CAST_REFUSED =
            "a type cast on a write or an action is accepted only for its entity set's own type, not for ";

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

    private static ODataApplicationException refused(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
    }
}
