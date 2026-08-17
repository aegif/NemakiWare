/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.util.lock;

/**
 * A set of object locks could not be taken within the allowed time, and was given up rather than
 * waited on.
 *
 * <h2>Why a failure is the better outcome</h2>
 *
 * <p>Waiting forever is what the alternative looks like. On 2026-08-10 a repository stopped
 * answering because two threads held one object each and wanted the other's; a queued writer then
 * blocked every later reader of both, and nothing broke the cycle. The repository stayed down
 * until the process was restarted, and no log line said why.
 *
 * <p>Giving up turns that into a request that fails and can be retried, while everything holding
 * locks lets go. It is a worse outcome than the operation succeeding and a much better one than
 * the server stopping.
 *
 * <p>It extends {@code CmisServiceUnavailableException} so clients are told 503 — temporarily
 * unavailable, try again — rather than 500. A plain runtime exception would reach the bindings as
 * an undifferentiated server error, which a client cannot distinguish from a real fault and will
 * not retry; and retrying is precisely the right response here.
 *
 * <p>{@link #getObjects()} names the locks that could not be taken. A dump captured afterwards
 * cannot recover them, and inferring them from the code is what went wrong the first time.
 */
public class LockAcquisitionTimeoutException
		extends org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException {

	private static final long serialVersionUID = 1L;

	private final java.util.List<String> objects;

	public LockAcquisitionTimeoutException(String message, java.util.List<String> objects) {
		super(message);
		this.objects = objects == null ? java.util.List.of()
				: java.util.List.copyOf(objects);
	}

	/** The (repository, object) keys of the locks involved, in acquisition order. */
	public java.util.List<String> getObjects() {
		return objects;
	}
}
