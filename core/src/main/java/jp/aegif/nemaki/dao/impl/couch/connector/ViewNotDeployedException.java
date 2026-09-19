package jp.aegif.nemaki.dao.impl.couch.connector;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

/**
 * The view a query needs is not deployed, so the query COULD NOT BE SERVED.
 *
 * <p>A {@code CmisRuntimeException} subtype, so every caller that already treats an unserved
 * query as a failure keeps doing exactly that — this adds no new class of failure. What it adds
 * is a name, for the one caller that can say something better than "our bug".
 *
 * <p>Why the name is needed: the principal lookup answers a TRI-STATE
 * ({@code FOUND / NOT_FOUND / UNAVAILABLE}) precisely so that "could not ask" never reads as
 * "no such principal". Its contract is that an unservable query comes back as UNAVAILABLE, and
 * the strict caller ({@code AclSemantics.readerTokens}) then raises
 * {@code PrincipalUnavailableException} NAMING the principal. When the missing view began
 * throwing an untyped {@code CmisRuntimeException} instead, that name was lost: the ACL path
 * failed with a message about a design document rather than about alice, and the tri-state had
 * nothing left to express. {@code PrincipalLookupTriStateIT} measures both halves.
 */
public class ViewNotDeployedException extends CmisRuntimeException {
	private static final long serialVersionUID = 1L;

	public ViewNotDeployedException(String message, Throwable cause) {
		super(message, cause);
	}
}
