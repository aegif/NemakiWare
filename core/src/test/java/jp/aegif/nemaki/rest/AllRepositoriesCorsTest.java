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
package jp.aegif.nemaki.rest;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The repository-list endpoint must not decide the CORS policy for itself.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code api.cors.allowedOrigins} is the documented way to restrict cross-origin access, and
 * {@link SimpleCorsFilter} implements it for {@code /rest/*} — including this servlet's path. But
 * the servlet then called {@code setHeader("Access-Control-Allow-Origin", "*")} on its way out,
 * which overwrites what the filter decided. An operator who set the property to their own origin
 * still got {@code *} back from {@code /rest/all/repositories}: verified against a running server
 * before the fix, which answered {@code Access-Control-Allow-Origin: *} for
 * {@code Origin: https://evil.example}.
 *
 * <h2>What this test pins</h2>
 *
 * <p>That the servlet sets NO CORS header at all — not that it sets a better one. Asserting a
 * particular value would leave the door open to re-introducing a second, independent policy here;
 * the point is that there is exactly one place that decides.
 */
public class AllRepositoriesCorsTest {

	private static HttpServletResponse respondingTo(HttpServletRequest request) throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
		return response;
	}

	/**
	 * With no Spring context this takes the 503 branch — which is fine, because the headers were
	 * set before any of that and would still be set on the way out.
	 */
	@Test
	public void theServletDoesNotSetItsOwnAllowOrigin() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = respondingTo(request);

		new AllRepositoriesServlet().doGet(request, response);

		verify(response, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
		verify(response, never()).setHeader(eq("Access-Control-Allow-Methods"), anyString());
		verify(response, never()).setHeader(eq("Access-Control-Allow-Headers"), anyString());
	}

	/** The preflight path had its own copy of the same headers. */
	@Test
	public void thePreflightPathDoesNotEither() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = respondingTo(request);

		new AllRepositoriesServlet().doOptions(request, response);

		verify(response, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
		verify(response, never()).setHeader(eq("Access-Control-Max-Age"), anyString());
		verify(response).setStatus(HttpServletResponse.SC_OK);
	}
}
