package jp.aegif.nemaki.api.v1.filter;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

/**
 * Tests for {@link ApiCsrfFilter} — CSRF protection on the Jersey
 * {@code /api/v1/cmis/*} application. The detailed Origin/Referer/
 * header-bypass logic lives in {@code CsrfValidator} (covered by
 * {@code CsrfInterceptorTest}); these tests verify the filter's own
 * behaviour: safe methods skip, unsafe methods are validated and
 * aborted with 403 when ambient-only, valid headers pass, and a
 * missing servlet request fails closed.
 */
public class ApiCsrfFilterTest {

    private ApiCsrfFilter filter;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    public void setUp() {
        filter = new ApiCsrfFilter();
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getPath()).thenReturn("repositories/bedroom/objects");
    }

    private void injectRequest(MockHttpServletRequest req) throws Exception {
        Field f = ApiCsrfFilter.class.getDeclaredField("httpServletRequest");
        f.setAccessible(true);
        f.set(filter, req);
    }

    private MockHttpServletRequest req(String method) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/v1/cmis/repositories/bedroom/objects");
        r.setScheme("http");
        r.setServerName("localhost");
        r.setServerPort(8080);
        return r;
    }

    @Test
    public void getIsSkipped() throws Exception {
        when(requestContext.getMethod()).thenReturn("GET");
        injectRequest(req("GET"));
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    public void headOptionsAndTraceAreSkipped() throws Exception {
        for (String m : new String[]{"HEAD", "OPTIONS", "TRACE"}) {
            ContainerRequestContext ctx = mock(ContainerRequestContext.class);
            when(ctx.getMethod()).thenReturn(m);
            ApiCsrfFilter f = new ApiCsrfFilter();
            injectInto(f, req(m));
            f.filter(ctx);
            verify(ctx, never()).abortWith(any());
        }
    }

    @Test
    public void unsafePostWithoutHeaders_isRejected403() throws Exception {
        when(requestContext.getMethod()).thenReturn("POST");
        injectRequest(req("POST")); // no Origin/Referer/X-Requested-With, ambient only
        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
    }

    @Test
    public void unsafePostWithXRequestedWith_passes() throws Exception {
        when(requestContext.getMethod()).thenReturn("POST");
        MockHttpServletRequest r = req("POST");
        r.addHeader("X-Requested-With", "XMLHttpRequest");
        injectRequest(r);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    public void unsafePostWithBearer_passes() throws Exception {
        when(requestContext.getMethod()).thenReturn("POST");
        MockHttpServletRequest r = req("POST");
        r.addHeader("Authorization", "Bearer abc123");
        injectRequest(r);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    public void unsafePutWithMatchingOrigin_passes() throws Exception {
        when(requestContext.getMethod()).thenReturn("PUT");
        MockHttpServletRequest r = req("PUT");
        r.addHeader("Origin", "http://localhost:8080");
        injectRequest(r);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    public void unsafeDeleteCrossOrigin_isRejected403() throws Exception {
        when(requestContext.getMethod()).thenReturn("DELETE");
        MockHttpServletRequest r = req("DELETE");
        r.addHeader("Origin", "http://evil.example.com");
        injectRequest(r);
        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
    }

    @Test
    public void nullServletRequest_failsClosedOnUnsafeMethod() throws Exception {
        when(requestContext.getMethod()).thenReturn("POST");
        injectRequest(null); // servlet request unavailable
        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(resp -> resp.getStatus() == 403));
    }

    private static void injectInto(ApiCsrfFilter f, MockHttpServletRequest req) throws Exception {
        Field fld = ApiCsrfFilter.class.getDeclaredField("httpServletRequest");
        fld.setAccessible(true);
        fld.set(f, req);
    }
}
