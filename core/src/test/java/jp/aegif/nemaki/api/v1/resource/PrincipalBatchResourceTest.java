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
package jp.aegif.nemaki.api.v1.resource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.v1.exception.ApiException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch;
import org.slf4j.LoggerFactory;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.audit.AuditOperation;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PasswordPolicyService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gates of design §20 C-1, measured at the product's entry: the JAX-RS methods of
 * {@link PrincipalBatchResource} with the real planner and applier behind them and a mocked
 * {@link ContentService} that answers what the store would.
 *
 * <p>Nothing here calls a collaborator class directly. A lock on the planner alone would not
 * notice a resource that forgot to consult it (the entry-point rule of this branch).
 */
class PrincipalBatchResourceTest {

    private static final String REPO = "bedroom";
    private static final String ACTOR = "root";

    /** The seven canonical write methods and the raw update — preview must touch none of them. */
    private static void verifyNoWrites(ContentService cs) {
        verify(cs, never()).buildAndCreateUser(anyString(), anyString(), any(), any(), any(), any(), any(), any());
        verify(cs, never()).applyUserUpdate(anyString(), any(), any());
        verify(cs, never()).deleteUser(anyString(), anyString());
        verify(cs, never()).buildAndCreateGroup(anyString(), anyString(), any(), any(), any(), any());
        verify(cs, never()).applyGroupUpdate(anyString(), any(), any());
        verify(cs, never()).deleteGroup(anyString(), anyString());
        verify(cs, never()).update(any(), anyString(), any());
    }

    private static PrincipalBatchResource resourceWith(ContentService cs, boolean admin, AuditLogger audit)
            throws Exception {
        PrincipalBatchResource resource = new PrincipalBatchResource();
        set(resource, "contentService", cs);
        PasswordPolicyService policy = mock(PasswordPolicyService.class);
        when(policy.validate(anyString(), anyString())).thenReturn(PasswordPolicyService.PasswordPolicyResult.ok());
        set(resource, "passwordPolicyService", policy);
        PropertyManager props = mock(PropertyManager.class);
        when(props.readValue(PropertyKey.SOLR_NEMAKI_USERID)).thenReturn("solr");
        when(props.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_PREFIX)).thenReturn("ldap_");
        when(props.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX)).thenReturn("");
        set(resource, "propertyManager", props);
        set(resource, "auditLogger", audit);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(ctx.getUsername()).thenReturn(ACTOR);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        set(resource, "httpRequest", request);
        return resource;
    }

    private static PrincipalBatchResource resourceWith(ContentService cs) throws Exception {
        return resourceWith(cs, true, null);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static UserItem user(String id, String revision) {
        UserItem item = new UserItem();
        item.setUserId(id);
        item.setName(id);
        item.setRevision(revision);
        item.setSubTypeProperties(new ArrayList<>());
        return item;
    }

    private static GroupItem group(String id, List<String> users, List<String> groups) {
        GroupItem item = new GroupItem();
        item.setGroupId(id);
        item.setName(id);
        item.setRevision("1-g");
        item.setUsers(new ArrayList<>(users));
        item.setGroups(new ArrayList<>(groups));
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    private static String json(String kind, String operation, String extra, String rows) {
        return "{\"kind\":\"" + kind + "\",\"operation\":\"" + operation + "\"" + extra + ",\"rows\":" + rows + "}";
    }

    // ---- gate: preview writes nothing ----

    @Test
    @DisplayName("preview calls none of the canonical write methods")
    void previewWritesNothing() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("g1"))).thenReturn(group("g1", List.of(), List.of()));
        PrincipalBatchResource resource = resourceWith(cs);

        Response users = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u2\",\"name\":\"Two\",\"password\":\"S3cret!!\"},{\"userId\":\"u1\",\"password\":\"x\"}]"));
        Response memberships = resource.previewJson(REPO, json("memberships", "add", "",
                "[{\"groupId\":\"g1\",\"memberId\":\"u1\",\"memberType\":\"user\"}]"));

        assertEquals(200, users.getStatus(), String.valueOf(users.getEntity()));
        assertEquals(200, memberships.getStatus(), String.valueOf(memberships.getEntity()));
        verifyNoWrites(cs);
        Map<String, Object> counts = (Map<String, Object>) body(users).get("counts");
        assertEquals(1L, counts.get("expected"));
        assertEquals(1L, counts.get("unexpected"), "u1 exists, so creating it is unexpected: " + body(users));
    }

    // ---- gate: abort + unexpected → 409, nothing written ----

    @Test
    @DisplayName("abort with one unexpected row writes nothing and answers 409")
    void abortRefusesWhenAnyRowIsUnexpected() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getUserItemById(eq(REPO), eq("ghost"))).thenReturn(null);
        PrincipalBatchResource resource = resourceWith(cs);

        Response response = resource.executeJson(REPO, json("users", "update", "",
                "[{\"userId\":\"u1\",\"name\":\"One\"},{\"userId\":\"ghost\",\"name\":\"X\"}]"));

        assertEquals(409, response.getStatus(), String.valueOf(response.getEntity()));
        assertEquals("refused", body(response).get("status"));
        assertEquals("UNEXPECTED_ROWS", body(response).get("reason"));
        verifyNoWrites(cs);
    }

    // ---- gate: forbidden is not applied even with skip ----

    @Test
    @DisplayName("the built-in admin is never deleted, not even with onUnexpected=skip")
    void forbiddenIsNotAppliedEvenWithSkip() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("admin"))).thenReturn(user("admin", "1-a"));
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-b"));
        when(cs.deleteUser(eq(REPO), anyString())).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);

        Response response = resource.executeJson(REPO, json("users", "delete", ",\"onUnexpected\":\"skip\"",
                "[{\"userId\":\"admin\"},{\"userId\":\"u1\"}]"));

        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        verify(cs, never()).deleteUser(REPO, "admin");
        verify(cs, times(1)).deleteUser(REPO, "u1");
        Map<String, Object> counts = (Map<String, Object>) body(response).get("counts");
        assertEquals(1L, counts.get("forbidden"), String.valueOf(body(response)));
        assertEquals(1L, counts.get("applied"));
    }

    // ---- gate: a changed snapshot is 409 and writes nothing ----

    @Test
    @DisplayName("a plan whose targets changed since the preview is 409 and writes nothing")
    void aChangedSnapshotIs409AndWritesNothing() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.deleteUser(eq(REPO), anyString())).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);
        Response preview = resource.previewJson(REPO, json("users", "delete", "", "[{\"userId\":\"u1\"}]"));
        String planId = (String) body(preview).get("planId");

        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "2-b")); // someone wrote it
        Response execute = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");

        assertEquals(409, execute.getStatus(), String.valueOf(execute.getEntity()));
        assertEquals("SNAPSHOT_CHANGED", body(execute).get("reason"));
        verifyNoWrites(cs);
    }

    @Test
    @DisplayName("an unchanged snapshot lets the plan apply, once; the plan is then gone")
    void anUnchangedSnapshotAppliesThePlanOnce() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.deleteUser(eq(REPO), eq("u1"))).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO,
                json("users", "delete", "", "[{\"userId\":\"u1\"}]"))).get("planId");

        Response first = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");
        Response second = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");

        assertEquals(200, first.getStatus(), String.valueOf(first.getEntity()));
        verify(cs, times(1)).deleteUser(REPO, "u1");
        assertEquals(409, second.getStatus(), "a plan is applied once: " + second.getEntity());
        assertEquals("PLAN_UNKNOWN", body(second).get("reason"));
    }

    // ---- gate: the password leaves through no exit ----

    @Test
    @DisplayName("a password appears in no response, is not kept in the plan, and is not audited")
    void thePasswordLeavesThroughNoExit() throws Exception {
        String secret = "S3cret-Passw0rd!";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        AuditLogger audit = mock(AuditLogger.class);
        PrincipalBatchResource resource = resourceWith(cs, true, audit);

        Response preview = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));
        assertEquals(200, preview.getStatus(), String.valueOf(preview.getEntity()));
        assertFalse(String.valueOf(preview.getEntity()).contains(secret), "the preview echoed the password");
        assertEquals(Boolean.TRUE, body(preview).get("passwordPresent"));
        String planId = (String) body(preview).get("planId");

        // The plan does not hold the password: execute with the planId ALONE cannot apply it.
        Response alone = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");
        assertEquals(400, alone.getStatus(), String.valueOf(alone.getEntity()));
        assertFalse(String.valueOf(alone.getEntity()).contains(secret));
        verifyNoWrites(cs);

        // A DIFFERENT file with the plan is refused and the plan is kept for the right one.
        Response wrongFile = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\","
                + "\"kind\":\"users\",\"operation\":\"create\",\"rows\":[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"other\"}]}");
        assertEquals(409, wrongFile.getStatus(), String.valueOf(wrongFile.getEntity()));
        assertEquals("FILE_DIGEST_CHANGED", body(wrongFile).get("reason"));
        verifyNoWrites(cs);

        // The same rows again apply — the password came from THIS request, not from the plan —
        // and the audit line carries counts, not cells.
        Response execute = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\","
                + "\"kind\":\"users\",\"operation\":\"create\",\"rows\":[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\""
                + secret + "\"}]}");
        assertEquals(200, execute.getStatus(), String.valueOf(execute.getEntity()));
        assertFalse(String.valueOf(execute.getEntity()).contains(secret));
        verify(cs).buildAndCreateUser(eq(REPO), eq("u9"), eq("Nine"), eq(secret), any(), any(), any(), eq(ACTOR));
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).logOperation(eq(AuditOperation.PRINCIPAL_BATCH), eq(REPO), eq(ACTOR), anyString(), eq(true),
                any(), details.capture());
        assertFalse(String.valueOf(details.getValue()).contains(secret), "the audit line carried the password");
    }

    // ---- gate: the log is an exit too (9-6 review of area C, P1: nothing measured it) ----

    /** The applier is package-private to its own package; its logger is reached by name. */
    private static final String APPLIER_LOGGER = "jp.aegif.nemaki.api.v1.principals.PrincipalBatchApplier";
    private final List<ListAppender<ILoggingEvent>> attached = new ArrayList<>();

    @AfterEach
    void detachAppenders() {
        for (Logger logger : List.of((Logger) LoggerFactory.getLogger(APPLIER_LOGGER),
                (Logger) LoggerFactory.getLogger(PrincipalBatchResource.class))) {
            for (ListAppender<ILoggingEvent> appender : attached) {
                logger.detachAppender(appender);
            }
        }
        attached.clear();
    }

    /** Everything the applier and the resource log, messages and throwables rendered. */
    private ListAppender<ILoggingEvent> capturingTheLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Logger logger : List.of((Logger) LoggerFactory.getLogger(APPLIER_LOGGER),
                (Logger) LoggerFactory.getLogger(PrincipalBatchResource.class))) {
            logger.addAppender(appender);
        }
        attached.add(appender);
        return appender;
    }

    private static String rendered(ListAppender<ILoggingEvent> appender) {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            out.append(event.getFormattedMessage()).append('\n');
            for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                out.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
                for (var frame : proxy.getStackTraceElementProxyArray()) {
                    out.append("  at ").append(frame.getSTEAsString()).append('\n');
                }
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("a row that fails inside the store is logged under an incident id — without its cells")
    void thePasswordIsNotLoggedWhenARowFails() throws Exception {
        String secret = "S3cret-Passw0rd!";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        when(cs.buildAndCreateUser(eq(REPO), eq("u9"), eq("Nine"), eq(secret), any(), any(), any(), eq(ACTOR)))
                .thenThrow(new IllegalStateException("couchdb answered 503"));
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response execute = resource.executeJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));

        assertEquals(500, execute.getStatus(), String.valueOf(execute.getEntity()));
        assertFalse(String.valueOf(execute.getEntity()).contains(secret));
        String logged = rendered(log);
        assertTrue(logged.contains("stopped at line 2") && logged.contains("couchdb answered 503"),
                "the incident line is the one measured here, and it was not written: " + logged);
        assertFalse(logged.contains(secret),
                "the password reached the log. The design (principal-batch.md, the C-1 gate) says the "
                        + "value is in no response, plan, audit line or LOG, and until this lock the log "
                        + "was the one exit no test read (9-6 review of area C, P1): " + logged);
    }

    @Test
    @DisplayName("a lower layer that echoes the password in its exception is redacted in the log — message, throwable and cause")
    void aLowerLayerThatEchoesThePasswordIsRedactedInTheLog() throws Exception {
        String secret = "S3cret-Passw0rd!";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        when(cs.buildAndCreateUser(eq(REPO), eq("u9"), eq("Nine"), eq(secret), any(), any(), any(), eq(ACTOR)))
                .thenThrow(new IllegalStateException("rejected the credential " + secret,
                        new IllegalArgumentException("weak: " + secret)));
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response execute = resource.executeJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));

        assertEquals(500, execute.getStatus(), String.valueOf(execute.getEntity()));
        String logged = rendered(log);
        assertTrue(logged.contains("rejected the credential [password redacted]"),
                "the failing layer's text is kept, with the password's value taken out: " + logged);
        assertFalse(logged.contains(secret),
                "the password reached the log through the exception the store threw — its message, "
                        + "or the cause chain the throwable carries (9-6 review of area C, P1): " + logged);
        // The cause chain is kept — class, frames, redacted message — because the cause is where
        // the failure actually happened (confirmation review, round 3, P2: the first fix dropped it).
        assertTrue(logged.contains("java.lang.IllegalArgumentException: weak: [password redacted]"),
                "the cause's class and redacted message are not in the log: " + logged);
    }

    @Test
    @DisplayName("a password equal to the placeholder is withheld, not 'redacted' into itself")
    void aPasswordEqualToThePlaceholderIsWithheld() throws Exception {
        String secret = "[password redacted]";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        when(cs.buildAndCreateUser(eq(REPO), eq("u9"), eq("Nine"), eq(secret), any(), any(), any(), eq(ACTOR)))
                .thenThrow(new IllegalStateException("rejected " + secret));
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response execute = resource.executeJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret.replace("\"", "") + "\"}]"));

        assertEquals(500, execute.getStatus(), String.valueOf(execute.getEntity()));
        String logged = rendered(log);
        assertTrue(logged.contains("stopped at line 2"), logged);
        assertFalse(logged.contains(secret),
                "the password IS the placeholder, so replacing it with the placeholder changed nothing "
                        + "and the value went to the log (confirmation review, round 3, P1): " + logged);
        assertTrue(logged.contains("withheld"), logged);
    }

    @Test
    @DisplayName("a password too short to replace withholds the message instead of mangling it")
    void aShortPasswordWithholdsTheMessage() throws Exception {
        String secret = "e";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        when(cs.buildAndCreateUser(eq(REPO), eq("u9"), eq("Nine"), eq(secret), any(), any(), any(), eq(ACTOR)))
                .thenThrow(new IllegalStateException("couchdb answered 503"));
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response execute = resource.executeJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));

        assertEquals(500, execute.getStatus(), String.valueOf(execute.getEntity()));
        String logged = rendered(log);
        assertTrue(logged.contains("stopped at line 2"), logged);
        assertTrue(logged.contains("too short to replace"),
                "a one-letter password cannot be replaced out of a message without mangling it "
                        + "(confirmation review, round 3, P3). The echo arm's withholding would read "
                        + "the same to a looser assertion, so the reason is named: " + logged);
        assertFalse(logged.contains("couchdb answ"), "the text was mangled rather than withheld: " + logged);
    }

    @Test
    @DisplayName("a password that is a prefix of another row's password does not leave the longer one's tail in the log")
    void aNestedPasswordLeavesNoTailInTheLog() throws Exception {
        String shorter = "Welcome1";
        String longer = "Welcome1-Tokyo";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        PasswordPolicyService policy = mock(PasswordPolicyService.class);
        when(policy.validate(eq(shorter), anyString())).thenReturn(PasswordPolicyService.PasswordPolicyResult.ok());
        when(policy.validate(eq(longer), anyString()))
                .thenThrow(new IllegalStateException("cannot validate " + longer));
        set(resource, "passwordPolicyService", policy);
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response preview = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u8\",\"name\":\"Eight\",\"password\":\"" + shorter + "\"},"
                        + "{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + longer + "\"}]"));

        assertEquals(500, preview.getStatus(), String.valueOf(preview.getEntity()));
        String logged = rendered(log);
        assertTrue(logged.contains("cannot validate [password redacted]\n"),
                "the whole of the longer password is one placeholder, to the end of the line: " + logged);
        assertFalse(logged.contains("Tokyo") || logged.contains(shorter),
                "the shorter password was replaced first and the tail of the longer one stayed in "
                        + "the log (confirmation review, round 4, P2): " + logged);
    }

    @Test
    @DisplayName("a short password that occurs only inside the placeholder does not withhold the message")
    void aShortPasswordInsideThePlaceholderDoesNotWithhold() throws Exception {
        String shortOne = "sw";
        String longOne = "Qx9#Lm2v";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        PasswordPolicyService policy = mock(PasswordPolicyService.class);
        when(policy.validate(eq(shortOne), anyString())).thenReturn(PasswordPolicyService.PasswordPolicyResult.ok());
        when(policy.validate(eq(longOne), anyString())).thenThrow(new IllegalStateException("bad: " + longOne));
        set(resource, "passwordPolicyService", policy);
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response preview = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u8\",\"name\":\"Eight\",\"password\":\"" + shortOne + "\"},"
                        + "{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + longOne + "\"}]"));

        assertEquals(500, preview.getStatus(), String.valueOf(preview.getEntity()));
        String logged = rendered(log);
        assertTrue(logged.contains("bad: [password redacted]"),
                "the message echoed nothing short, and the placeholder's own letters were read as an "
                        + "echo of 'sw' (confirmation review, round 5, P3): " + logged);
        assertFalse(logged.contains("withheld"), logged);
    }

    @Test
    @DisplayName("a JSON body that is the literal null is a request error, not an incident")
    void aNullBodyIsARequestError() throws Exception {
        PrincipalBatchResource resource = resourceWith(mock(ContentService.class), true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response preview = resource.previewJson(REPO, "null");

        assertEquals(400, preview.getStatus(),
                "the literal null parsed to a null root and reading it was an NPE into the catch-all: "
                        + "a 500 with an incident for a request error, and the one body that reached "
                        + "the unread-rows arm (confirmation review, round 5, P3): "
                        + String.valueOf(preview.getEntity()));
        assertFalse(rendered(log).contains("incident"), "a request error is not logged as an incident: " + rendered(log));
    }

    /**
     * Discriminating only once the registry has a second column: with {@code SECRET_COLUMNS ==
     * {"password"}} an implementation that spells "password" passes this too. Recorded, not
     * pretended (confirmation review, round 4, P3).
     */
    @Test
    @DisplayName("every column the registry names as secret is redacted — the log does not spell 'password' itself")
    void everyRegisteredSecretColumnIsRedacted() {
        for (String column : PrincipalBatch.SECRET_COLUMNS) {
            String secret = "V4lue-of-" + column;
            jp.aegif.nemaki.api.v1.principals.Secrets secrets = new jp.aegif.nemaki.api.v1.principals.Secrets();
            secrets.learn(new PrincipalBatch.Row(2, "u9", Map.of(column, secret)));
            String redacted = secrets.redact("the store said no to " + secret);
            assertFalse(redacted.contains(secret),
                    "the column '" + column + "' is in PrincipalBatch.SECRET_COLUMNS and its value "
                            + "reached the log text — the log redaction read a spelt-out column, "
                            + "not the registry (confirmation review, round 3, P3): " + redacted);
        }
    }

    @Test
    @DisplayName("a failure before the applier — in planning — is logged by the resource without the password either")
    void thePasswordIsNotLoggedWhenPlanningFails() throws Exception {
        String secret = "S3cret-Passw0rd!";
        ContentService cs = mock(ContentService.class);
        // The planner reads the target to decide the row; a store that echoes what it was asked
        // with the request's cells in its message is the shape under test.
        when(cs.getUserItemById(eq(REPO), anyString()))
                .thenThrow(new IllegalStateException("cannot read u9 while validating " + secret,
                        new IllegalArgumentException("policy saw " + secret)));
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response preview = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));

        // The planner wraps a store that could not answer into the 503 the resource returns;
        // the store's own text goes to the log under the incident id.
        assertEquals(503, preview.getStatus(), String.valueOf(preview.getEntity()));
        assertFalse(String.valueOf(preview.getEntity()).contains(secret));
        String logged = rendered(log);
        assertTrue(logged.contains("could not read the store [incident"),
                "the resource's own incident line was not written: " + logged);
        assertTrue(logged.contains("cannot read u9 while validating [password redacted]")
                        && logged.contains("java.lang.IllegalArgumentException: policy saw [password redacted]"),
                "the message and the cause are kept, redacted: " + logged);
        assertFalse(logged.contains(secret),
                "the resource's catch logged the planning failure as the store wrote it — the "
                        + "applier's line was redacted and this one was not (confirmation review, "
                        + "round 3, P1): " + logged);
    }

    @Test
    @DisplayName("a failure in the password policy — before any store — is logged by the resource's catch-all without the password either")
    void thePasswordIsNotLoggedWhenThePolicyFails() throws Exception {
        String secret = "S3cret-Passw0rd!";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenReturn(null);
        PrincipalBatchResource resource = resourceWith(cs, true, mock(AuditLogger.class));
        // A policy layer that echoes what it was asked to validate: the shape under test.
        PasswordPolicyService policy = mock(PasswordPolicyService.class);
        when(policy.validate(anyString(), anyString()))
                .thenThrow(new IllegalStateException("cannot validate " + secret));
        set(resource, "passwordPolicyService", policy);
        ListAppender<ILoggingEvent> log = capturingTheLog();

        Response preview = resource.previewJson(REPO, json("users", "create", "",
                "[{\"userId\":\"u9\",\"name\":\"Nine\",\"password\":\"" + secret + "\"}]"));

        assertEquals(500, preview.getStatus(), String.valueOf(preview.getEntity()));
        assertFalse(String.valueOf(preview.getEntity()).contains(secret));
        String logged = rendered(log);
        assertTrue(logged.contains("failed [incident"), "the catch-all's incident line was not written: " + logged);
        assertTrue(logged.contains("cannot validate [password redacted]"), logged);
        assertFalse(logged.contains(secret),
                "the catch-all logged the policy's failure as the policy wrote it — the applier's line "
                        + "was redacted and this one was not (confirmation review, round 3, P1): " + logged);
    }

    // ---- gate: the empty groups column is 'do not touch'; replace's empty members is 'make empty' ----

    @Test
    @DisplayName("an update with a blank groups column leaves the memberships alone")
    void aBlankGroupsColumnLeavesMembershipsAlone() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        PrincipalBatchResource resource = resourceWith(cs);
        byte[] csv = "userId,name,groups\nu1,Renamed,\n".getBytes(StandardCharsets.UTF_8);

        Response response = resource.executeMultipart(REPO, new ByteArrayInputStream(csv), "users", "update",
                null, null);

        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        verify(cs).applyUserUpdate(eq(REPO), any(), eq(ACTOR));
        verify(cs, never()).getGroupItems(anyString());
        verify(cs, never()).applyGroupUpdate(anyString(), any(), any());
    }

    @Test
    @DisplayName("a groups or users cell that is only separators is 400, not 'in no group' — CSV ';', JSON [\"\", \"\"]")
    void aCellOfSeparatorsIsRefusedNotEmptied() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("g1"))).thenReturn(group("g1", List.of("u1", "u2"), List.of("g2")));
        PrincipalBatchResource resource = resourceWith(cs);

        // A spreadsheet's join of nothing. It read as an empty list, and the update applied it
        // as "in no group": every membership of the row gone, LOOKS_DIRECTORY_SYNCED never
        // asked, and the audit line keeping only a count (9-6 review, P1).
        Response csv = resource.executeMultipart(REPO,
                new ByteArrayInputStream("userId,groups\nu1,;\n".getBytes(StandardCharsets.UTF_8)),
                "users", "update", null, null);
        Response jsonUsers = resource.executeJson(REPO, json("users", "update", "",
                "[{\"userId\":\"u1\",\"groups\":[\"\",\"\"]}]"));
        Response groupMembers = resource.executeMultipart(REPO,
                new ByteArrayInputStream("groupId,users\ng1, ; \n".getBytes(StandardCharsets.UTF_8)),
                "groups", "update", null, null);
        Response replace = resource.executeMultipart(REPO,
                new ByteArrayInputStream("groupId,members\ng1,;\n".getBytes(StandardCharsets.UTF_8)),
                "memberships", "replace", null, null);

        for (Response refused : List.of(csv, jsonUsers, groupMembers, replace)) {
            assertEquals(400, refused.getStatus(), String.valueOf(refused.getEntity()));
            assertTrue(String.valueOf(refused.getEntity()).contains("names no id"),
                    String.valueOf(refused.getEntity()));
        }
        verify(cs, never()).applyUserUpdate(anyString(), any(), anyString());
        verify(cs, never()).applyGroupUpdate(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("memberships/replace with empty members empties the group")
    void replaceWithEmptyMembersEmptiesTheGroup() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("g1"))).thenReturn(group("g1", List.of("u1", "u2"), List.of("g2")));
        PrincipalBatchResource resource = resourceWith(cs);

        Response response = resource.executeJson(REPO, json("memberships", "replace", "",
                "[{\"groupId\":\"g1\",\"members\":[]}]"));

        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        ArgumentCaptor<GroupItem> written = ArgumentCaptor.forClass(GroupItem.class);
        verify(cs).applyGroupUpdate(eq(REPO), written.capture(), eq(ACTOR));
        assertTrue(written.getValue().getUsers().isEmpty(), "users: " + written.getValue().getUsers());
        assertTrue(written.getValue().getGroups().isEmpty(), "groups: " + written.getValue().getGroups());
    }

    @Test
    @DisplayName("a replace file that dropped its members column is 400, not 'empty every group' — CSV, JSON absent, JSON null")
    void aReplaceWithoutAMembersColumnIsRefused() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("g1"))).thenReturn(group("g1", List.of("u1", "u2"), List.of("g2")));
        PrincipalBatchResource resource = resourceWith(cs);

        Response csv = resource.executeMultipart(REPO,
                new ByteArrayInputStream("groupId\ng1\n".getBytes(StandardCharsets.UTF_8)),
                "memberships", "replace", null, null);
        Response absent = resource.executeJson(REPO, json("memberships", "replace", "",
                "[{\"groupId\":\"g1\"}]"));
        Response nul = resource.executeJson(REPO, json("memberships", "replace", "",
                "[{\"groupId\":\"g1\",\"members\":null}]"));

        for (Response refused : List.of(csv, absent, nul)) {
            assertEquals(400, refused.getStatus(), String.valueOf(refused.getEntity()));
            assertTrue(String.valueOf(refused.getEntity()).contains("members"), String.valueOf(refused.getEntity()));
        }
        verify(cs, never()).applyGroupUpdate(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("two confirmations of one plan at the same moment apply it once: one 200, one 409, one write")
    void aPlanConfirmedTwiceAtOnceIsAppliedOnce() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.deleteUser(eq(REPO), eq("u1"))).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO,
                json("users", "delete", "", "[{\"userId\":\"u1\"}]"))).get("planId");
        // From here every read of u1 waits until BOTH confirmations are inside their re-plan, so
        // both see the snapshot unchanged and race for the plan itself. A sequential pair would
        // pass through peek alone, so the overlap is asserted, not assumed.
        CountDownLatch bothArePlanning = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean(true);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenAnswer(invocation -> {
            bothArePlanning.countDown();
            if (!bothArePlanning.await(5, TimeUnit.SECONDS)) {
                overlapped.set(false);
            }
            return user("u1", "1-a");
        });
        String confirm = "{\"planId\":\"" + planId + "\"}";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Future<Response> first = pool.submit(() -> resource.executeJson(REPO, confirm));
            Future<Response> second = pool.submit(() -> resource.executeJson(REPO, confirm));
            statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS).getStatus(),
                    second.get(20, TimeUnit.SECONDS).getStatus()));
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(statuses);

        assertTrue(overlapped.get(), "the two confirmations did not overlap, so nothing was measured");
        assertEquals(List.of(200, 409), statuses, "one applies, the other is refused");
        verify(cs, times(1)).deleteUser(REPO, "u1");
    }

    // ---- gate: a confirmed plan is re-decided, and must come out as it was previewed (c39, both reviewers) ----

    @Test
    @DisplayName("a plan whose row names a member that vanished since the preview is 409 and writes nothing")
    void aPlanWhoseReferencedMemberVanishedIs409() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("g1"))).thenReturn(group("g1", List.of(), List.of()));
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO, json("memberships", "add", "",
                "[{\"groupId\":\"g1\",\"memberId\":\"u1\",\"memberType\":\"user\"}]"))).get("planId");

        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(null); // u1 was deleted; g1's revision did not move
        Response execute = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");

        assertEquals(409, execute.getStatus(), String.valueOf(execute.getEntity()));
        assertEquals("SNAPSHOT_CHANGED", body(execute).get("reason"));
        verify(cs, never()).applyGroupUpdate(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a plan previewed by one admin and confirmed by the user it deletes is re-decided FORBIDDEN: 409, no write")
    void aPlanConfirmedByItsOwnTargetIsReDecided() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("bob"))).thenReturn(user("bob", "1-a"));
        when(cs.deleteUser(eq(REPO), anyString())).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO,
                json("users", "delete", "", "[{\"userId\":\"bob\"}]"))).get("planId");

        // Now bob, also an admin, confirms root's plan against himself.
        CallContext bob = mock(CallContext.class);
        when(bob.get(CallContextKey.IS_ADMIN)).thenReturn(true);
        when(bob.getUsername()).thenReturn("bob");
        when(((HttpServletRequest) get(resource, "httpRequest")).getAttribute("CallContext")).thenReturn(bob);
        Response execute = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");

        assertEquals(409, execute.getStatus(), String.valueOf(execute.getEntity()));
        assertEquals("SNAPSHOT_CHANGED", body(execute).get("reason"));
        verify(cs, never()).deleteUser(anyString(), anyString());
    }

    // ---- gate: over the byte limit is 413 before anything is read (c39 subagent P2: the three arms) ----

    @Test
    @DisplayName("a file or a body over the limit is 413 before any row is planned — and the file is not read on past the limit")
    void aFileOverTheByteLimitIs413BeforeAnythingIsRead() throws Exception {
        ContentService cs = mock(ContentService.class);
        PrincipalBatchResource resource = resourceWith(cs);
        // The multipart arm must stop READING at the limit, not refuse after buffering the whole
        // file (the CSV parser's own limit would then produce the same 413 and hide a reader that
        // buffers everything). This stream has no end and trips once asked far past the limit.
        long[] served = {0};
        InputStream endless = new InputStream() {
            @Override
            public int read() throws IOException {
                return serve(1) ? 'u' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                serve(len);
                java.util.Arrays.fill(b, off, off + len, (byte) 'u');
                return len;
            }

            private boolean serve(int n) throws IOException {
                served[0] += n;
                if (served[0] > PrincipalBatch.MAX_BYTES + 64 * 1024) {
                    throw new IOException("the reader read on past the limit");
                }
                return true;
            }
        };

        Response file = resource.previewMultipart(REPO, endless, "users", "delete");
        Response jsonBody = resource.previewJson(REPO, json("users", "delete", "",
                "[{\"userId\":\"" + "u".repeat((int) PrincipalBatch.MAX_BYTES) + "\"}]"));

        assertEquals(413, file.getStatus(), String.valueOf(file.getEntity()));
        assertTrue(served[0] <= PrincipalBatch.MAX_BYTES + 8192, "read " + served[0] + " bytes past a 2 MiB limit");
        assertEquals(413, jsonBody.getStatus(), String.valueOf(jsonBody.getEntity()));
        verify(cs, never()).getUserItemById(anyString(), anyString());
    }

    // ---- gate: a plan belongs to the repository it was decided against (c39 subagent P3) ----

    @Test
    @DisplayName("a plan previewed against one repository is not confirmed under another: 409 and no write")
    void aPlanIsBoundToItsRepository() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.deleteUser(anyString(), anyString())).thenReturn(true);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getUserItemById(eq("canopy"), eq("u1"))).thenReturn(user("u1", "1-a")); // same id, same revision
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO,
                json("users", "delete", "", "[{\"userId\":\"u1\"}]"))).get("planId");

        Response elsewhere = resource.executeJson("canopy", "{\"planId\":\"" + planId + "\"}");

        assertEquals(409, elsewhere.getStatus(), String.valueOf(elsewhere.getEntity()));
        assertEquals("PLAN_UNKNOWN", body(elsewhere).get("reason"));
        verify(cs, never()).deleteUser(anyString(), anyString());
    }

    // ---- gate: the confirmed path's default is abort too; skipping is its own choice (design §8, C-2) ----

    @Test
    @DisplayName("a confirmed plan with an unexpected or forbidden row writes nothing unless onUnexpected=skip; with skip the expected row alone is applied")
    void aConfirmedPlanWithAnUnexpectedRowIsRefusedUnlessSkip() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getUserItemById(eq(REPO), eq("admin"))).thenReturn(user("admin", "1-b"));
        when(cs.deleteUser(eq(REPO), anyString())).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);
        String planId = (String) body(resource.previewJson(REPO, json("users", "delete", "",
                "[{\"userId\":\"u1\"},{\"userId\":\"u9\"},{\"userId\":\"admin\"}]"))).get("planId");

        Response confirmed = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\"}");

        assertEquals(409, confirmed.getStatus(), String.valueOf(confirmed.getEntity()));
        assertEquals("UNEXPECTED_ROWS", body(confirmed).get("reason"));
        verify(cs, never()).deleteUser(anyString(), anyString());

        // The plan was kept: the same planId, now with skip, applies u1 alone.
        Response skipped = resource.executeJson(REPO, "{\"planId\":\"" + planId + "\",\"onUnexpected\":\"skip\"}");

        assertEquals(200, skipped.getStatus(), String.valueOf(skipped.getEntity()));
        verify(cs, times(1)).deleteUser(REPO, "u1");
        verify(cs, never()).deleteUser(REPO, "u9");
        verify(cs, never()).deleteUser(REPO, "admin");
    }

    // ---- gate: design §5.1 says delete AND remove for the directory-sync guess (c39 subagent P3) ----

    @Test
    @DisplayName("removing a member from a group with the directory-sync prefix is unexpected, so abort refuses it")
    void anLdapPrefixedMembershipRemoveIsUnexpected() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("ldap_sales"))).thenReturn(group("ldap_sales", List.of("u1"), List.of()));
        PrincipalBatchResource resource = resourceWith(cs);
        String rows = "[{\"groupId\":\"ldap_sales\",\"memberId\":\"u1\",\"memberType\":\"user\"}]";

        Response preview = resource.previewJson(REPO, json("memberships", "remove", "", rows));
        Response execute = resource.executeJson(REPO, json("memberships", "remove", "", rows));

        List<Map<String, Object>> verdicts = (List<Map<String, Object>>) body(preview).get("rows");
        assertEquals("LOOKS_DIRECTORY_SYNCED", verdicts.get(0).get("reason"), String.valueOf(verdicts));
        assertEquals(409, execute.getStatus(), String.valueOf(execute.getEntity()));
        verify(cs, never()).applyGroupUpdate(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a new group without a name is the file's defect: 400, not a NOT_FOUND verdict")
    void aNewGroupWithoutANameIs400() throws Exception {
        ContentService cs = mock(ContentService.class);
        PrincipalBatchResource resource = resourceWith(cs);

        Response preview = resource.previewJson(REPO, json("groups", "create", "", "[{\"groupId\":\"g9\"}]"));

        assertEquals(400, preview.getStatus(), String.valueOf(preview.getEntity()));
        assertTrue(String.valueOf(preview.getEntity()).contains("name"), String.valueOf(preview.getEntity()));
        verify(cs, never()).buildAndCreateGroup(anyString(), anyString(), any(), any(), any(), any());
    }

    // ---- gate: 500 carries a fixed text and an incident id, never the exception's words ----

    @Test
    @DisplayName("a store failure while applying is a 500 with an incidentId and none of the exception's text")
    void aStoreFailureWhileApplyingIs500WithoutTheMessage() throws Exception {
        String marker = "jdbc://db.internal:5984/nemaki_conf rev 3-abc SECRET";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), eq("u1"))).thenReturn(user("u1", "1-a"));
        when(cs.getUserItemById(eq(REPO), eq("u2"))).thenReturn(user("u2", "1-b"));
        when(cs.deleteUser(eq(REPO), eq("u1"))).thenThrow(new IllegalStateException(marker));
        PrincipalBatchResource resource = resourceWith(cs);

        Response response = resource.executeJson(REPO, json("users", "delete", "",
                "[{\"userId\":\"u1\"},{\"userId\":\"u2\"}]"));

        assertEquals(500, response.getStatus(), String.valueOf(response.getEntity()));
        String text = String.valueOf(response.getEntity());
        assertFalse(text.contains(marker), "the store's exception text reached the client: " + text);
        assertEquals("partial", body(response).get("status"));
        assertNotNull(body(response).get("incidentId"));
        assertEquals(2, body(response).get("stoppedAt"));
        verify(cs, never()).deleteUser(REPO, "u2");
    }

    @Test
    @DisplayName("a store that cannot answer whether a user exists is 503, not a file of NOT_FOUND")
    void aStoreThatCannotAnswerIs503() throws Exception {
        String marker = "couchdb view timed out at http://couchdb:5984 SECRET";
        ContentService cs = mock(ContentService.class);
        when(cs.getUserItemById(eq(REPO), anyString())).thenThrow(new IllegalStateException(marker));
        PrincipalBatchResource resource = resourceWith(cs);

        Response response = resource.previewJson(REPO, json("users", "delete", "", "[{\"userId\":\"u1\"}]"));

        assertEquals(503, response.getStatus(), String.valueOf(response.getEntity()));
        assertEquals("STORE_UNAVAILABLE", body(response).get("reason"));
        assertFalse(String.valueOf(response.getEntity()).contains(marker));
        assertNotNull(body(response).get("incidentId"));
    }

    // ---- gate: admin only; the request's own errors are 400 / 413 ----

    @Test
    @DisplayName("a non-admin is refused with 403 before anything is read")
    void aNonAdminIs403() throws Exception {
        ContentService cs = mock(ContentService.class);
        PrincipalBatchResource resource = resourceWith(cs, false, null);

        ApiException refused = assertThrows(ApiException.class,
                () -> resource.previewJson(REPO, json("users", "delete", "", "[{\"userId\":\"u1\"}]")));

        assertEquals(403, refused.getStatus());
        verifyNoWrites(cs);
    }

    @Test
    @DisplayName("an unknown column, a duplicate id and one row over the limit are 400 / 400 / 413")
    void requestErrorsAreRefusedBeforePlanning() throws Exception {
        ContentService cs = mock(ContentService.class);
        PrincipalBatchResource resource = resourceWith(cs);

        Response unknown = resource.previewJson(REPO, json("users", "update", "", "[{\"userId\":\"u1\",\"grups\":\"g\"}]"));
        assertEquals(400, unknown.getStatus(), String.valueOf(unknown.getEntity()));
        assertTrue(String.valueOf(unknown.getEntity()).contains("grups"));

        Response duplicate = resource.previewJson(REPO, json("users", "delete", "", "[{\"userId\":\"u1\"},{\"userId\":\"u1\"}]"));
        assertEquals(400, duplicate.getStatus(), String.valueOf(duplicate.getEntity()));

        StringBuilder csv = new StringBuilder("userId\n");
        for (int i = 0; i <= 5000; i++) {
            csv.append('u').append(i).append('\n');
        }
        Response tooMany = resource.previewMultipart(REPO,
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), "users", "delete");
        assertEquals(413, tooMany.getStatus(), String.valueOf(tooMany.getEntity()));
        verify(cs, never()).getUserItemById(anyString(), anyString());
    }

    // ---- gate: a directory-synced-looking id is unexpected, so abort refuses it ----

    @Test
    @DisplayName("deleting a group with the directory-sync prefix is unexpected, so abort refuses the file")
    void anLdapPrefixedGroupDeleteIsUnexpected() throws Exception {
        ContentService cs = mock(ContentService.class);
        when(cs.getGroupItemByIdFresh(eq(REPO), eq("ldap_sales"))).thenReturn(group("ldap_sales", List.of(), List.of()));
        when(cs.deleteGroup(eq(REPO), anyString())).thenReturn(true);
        PrincipalBatchResource resource = resourceWith(cs);

        Response preview = resource.previewJson(REPO, json("groups", "delete", "", "[{\"groupId\":\"ldap_sales\"}]"));
        Response execute = resource.executeJson(REPO, json("groups", "delete", "", "[{\"groupId\":\"ldap_sales\"}]"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) body(preview).get("rows");
        assertEquals("unexpected", rows.get(0).get("verdict"), String.valueOf(rows));
        assertEquals("LOOKS_DIRECTORY_SYNCED", rows.get(0).get("reason"));
        assertEquals(409, execute.getStatus(), String.valueOf(execute.getEntity()));
        verify(cs, never()).deleteGroup(anyString(), anyString());
    }
}
