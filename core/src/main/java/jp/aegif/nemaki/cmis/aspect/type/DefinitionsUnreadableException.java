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
package jp.aegif.nemaki.cmis.aspect.type;

/**
 * The type definitions — the type system of a repository, or the definition of a property — could not be READ.
 *
 * <p>Not a finding that a type or a property is undefined. A caller that answers a client must report this as its own
 * failure, never as the client's mistake: the query path turned it into «this WHERE clause cannot be translated», a 400,
 * through three catch-alls (closing batch 4 review, subagent P2-1). An {@link IllegalStateException}, as the reads it
 * marks always threw, so every existing catcher sees the same type it saw before.
 */
public class DefinitionsUnreadableException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public DefinitionsUnreadableException(String message) {
		super(message);
	}

	public DefinitionsUnreadableException(String message, Throwable cause) {
		super(message, cause);
	}

	/** Whether {@code failure} or any cause of it is this — a read that failed, not an answer. */
	public static boolean isIn(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof DefinitionsUnreadableException) {
				return true;
			}
		}
		return false;
	}
}
