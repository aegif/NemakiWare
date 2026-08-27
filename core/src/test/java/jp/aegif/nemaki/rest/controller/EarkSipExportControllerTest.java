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
package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.rest.eark.EarkSipExporter;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The door to the E-ARK export, and what it refuses to do without being asked.
 *
 * <h2>Why the default matters more here than on other endpoints</h2>
 *
 * <p>A SIP goes to another organisation and cannot be recalled. {@code includeInternalOnly=true}
 * puts personal data in it. A caller who omits the parameter must get the withholding
 * behaviour — an endpoint whose default leaked would be a disclosure nobody chose.
 */
class EarkSipExportControllerTest {

    private static EarkSipExportController controllerFor(boolean admin) {
        EarkSipExportController controller = new EarkSipExportController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        return controller;
    }

    private static List<Method> mappedEndpoints() {
        List<Method> endpoints = new ArrayList<>();
        for (Method method : EarkSipExportController.class.getDeclaredMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(
                    GetMapping.class, PostMapping.class,
                    org.springframework.web.bind.annotation.PutMapping.class,
                    org.springframework.web.bind.annotation.DeleteMapping.class,
                    org.springframework.web.bind.annotation.PatchMapping.class,
                    org.springframework.web.bind.annotation.RequestMapping.class)) {
                if (method.getAnnotation(mapping) != null) {
                    endpoints.add(method);
                    break;
                }
            }
        }
        return endpoints;
    }

    private static Object[] argumentsFor(Method method) {
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) {
                args[i] = "bedroom";
            } else if (type == boolean.class) {
                args[i] = false;
            }
        }
        return args;
    }

    @Test
    @DisplayName("EVERY mapped endpoint refuses a non-admin")
    void everyEndpointIsAdminOnly() throws Exception {
        EarkSipExportController controller = controllerFor(false);
        List<Method> endpoints = mappedEndpoints();

        // Not a fixed number. The count was a literal, so adding an endpoint failed this test
        // for the one reason that is not a defect — and the fix for a failing count is to bump
        // the number, which is how a guard quietly stops guarding. What matters is that there
        // ARE endpoints and that every one of them refuses; the loop below is the rule.
        assertFalse(endpoints.isEmpty(),
                "no mapped endpoint was found, so the loop below asserts nothing — the "
                        + "reflection that finds them has probably stopped matching");

        for (Method endpoint : endpoints) {
            Object response = endpoint.invoke(controller, argumentsFor(endpoint));
            HttpStatus status = (HttpStatus) response.getClass()
                    .getMethod("getStatusCode").invoke(response);
            assertEquals(HttpStatus.FORBIDDEN, status,
                    endpoint.getName() + " served a non-admin. This endpoint BUILDS A FILE "
                            + "containing a record's content and metadata, for handing to "
                            + "another organisation.");
        }
    }

    @Test
    @DisplayName("omitting the disclosure parameter withholds personal data")
    void theDefaultWithholds() throws Exception {
        // Spring supplies the default from @RequestParam(defaultValue), which a direct call
        // never exercises — so this reads the ANNOTATION. A default flipped to "true" would
        // otherwise ship personal data to every caller who did not name the parameter, and no
        // test invoking the method directly could see it.
        Method export = EarkSipExportController.class.getDeclaredMethod("export",
                String.class, String.class, boolean.class, String.class);
        java.lang.annotation.Annotation[][] parameterAnnotations = export.getParameterAnnotations();

        String disclosureDefault = null;
        for (java.lang.annotation.Annotation annotation : parameterAnnotations[2]) {
            if (annotation instanceof org.springframework.web.bind.annotation.RequestParam param) {
                disclosureDefault = param.defaultValue();
            }
        }

        assertNotNull(disclosureDefault, "includeInternalOnly is not a @RequestParam");
        assertEquals("false", disclosureDefault,
                "the default for includeInternalOnly is '" + disclosureDefault + "'. A caller "
                        + "who omits it would receive a package containing personal data, and "
                        + "a SIP cannot be recalled once handed over");
    }

    @Test
    @DisplayName("a refusal is a 409 with the reason, not a 500")
    void aRefusalIsReportedAsSuch() throws Exception {
        // ExportRefusedException is the DESIGNED outcome for "we would have had to ship
        // something incomplete". Letting it become a 500 would tell an operator the server is
        // broken when it is doing exactly what it should.
        EarkSipExportController controller = controllerFor(true);
        EarkSipExporter exporter = mock(EarkSipExporter.class);
        when(exporter.export(anyString(), anyString(), any(), any()))
                .thenThrow(new EarkSipExporter.ExportRefusedException(
                        "document doc-1 has no attachment, so there is nothing to package"));
        setField(controller, "exporter", exporter);

        Object response = EarkSipExportController.class.getDeclaredMethod("export",
                        String.class, String.class, boolean.class, String.class)
                .invoke(controller, "bedroom", "doc-1", false, "");
        HttpStatus status = (HttpStatus) response.getClass()
                .getMethod("getStatusCode").invoke(response);
        Object body = response.getClass().getMethod("getBody").invoke(response);

        assertEquals(HttpStatus.CONFLICT, status,
                "a designed refusal was reported as something else");
        assertTrue(String.valueOf(body).contains("no attachment"),
                "the caller was not told why: " + body);
        assertTrue(String.valueOf(body).contains("NOT a claim of E-ARK certification"),
                "the response does not carry what this endpoint does not establish: " + body);
    }

    @Test
    @DisplayName("the omissions travel in headers, because the body is a zip")
    void theOmissionsAreInTheHeaders(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // A caller streaming the response to disk never reads a JSON note. If the withheld
        // count is not in the headers it is nowhere, and the package reads as a complete
        // record of what was captured.
        EarkSipExportController controller = controllerFor(true);
        Path sip = Files.writeString(tmp.resolve("x.zip"), "not really a zip");
        EarkSipExporter exporter = mock(EarkSipExporter.class);
        when(exporter.export(anyString(), anyString(), any(), any()))
                .thenReturn(new EarkSipExporter.Exported(sip, 3,
                        List.of("3 propert(y/ies) ... are NOT in this package."),
                        new EarkSipExporter.Validation(true, true, 0, 0, "12 check(s) passed")));
        setField(controller, "exporter", exporter);

        Object response = EarkSipExportController.class.getDeclaredMethod("export",
                        String.class, String.class, boolean.class, String.class)
                .invoke(controller, "bedroom", "doc-1", false, "");
        org.springframework.http.HttpHeaders headers =
                (org.springframework.http.HttpHeaders) response.getClass()
                        .getMethod("getHeaders").invoke(response);

        assertEquals("3", headers.getFirst("X-Nemaki-Withheld-Property-Count"),
                "the number of withheld properties is not in the response headers: " + headers);
        assertEquals("false", headers.getFirst("X-Nemaki-Includes-Personal-Data"));
        assertNotNull(headers.getFirst("X-Nemaki-Export-Note"),
                "the exporter's notes did not reach the caller");
        assertNotNull(headers.getFirst("X-Nemaki-Export-Limits"));
    }

    @Test
    @DisplayName("the bag's External-Description is the SHA-256 of the payload actually in data/")
    void theBagsStatedDigestIsThePayloadsDigest(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // manifest-sha256.txt carries the same digest as a verified path -> digest binding, so
        // this line is not the only copy. It is still the one a receipt is discussed against:
        // it names the PACKAGE, not a path inside the bag, which is what a receiver reconciling
        // against this product's chain quotes back. A controller that stated the digest of
        // something other than the bytes it ships would produce a mismatch nobody could explain
        // -- and the manifest would not catch it, because the manifest would be self-consistent.
        EarkSipExportController controller = controllerFor(true);
        Path sip = Files.write(tmp.resolve("payload.zip"),
                "the exact bytes that must be described".getBytes(StandardCharsets.UTF_8));
        EarkSipExporter exporter = mock(EarkSipExporter.class);
        when(exporter.export(anyString(), anyString(), any(), any()))
                .thenReturn(new EarkSipExporter.Exported(sip, 0, List.of(),
                        new EarkSipExporter.Validation(true, true, 0, 0, "ok")));
        setField(controller, "exporter", exporter);

        Object response = EarkSipExportController.class.getDeclaredMethod("bag",
                        String.class, String.class, boolean.class, String.class)
                .invoke(controller, "bedroom", "doc-1", false, "sub-1");
        Object body = response.getClass().getMethod("getBody").invoke(response);
        assertTrue(body instanceof org.springframework.core.io.Resource,
                "the bag endpoint did not return a downloadable body: " + body);

        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                ((org.springframework.core.io.Resource) body).getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        byte[] payload = entries.get("data/payload.zip");
        assertNotNull(payload, "the payload is not under data/: " + entries.keySet());

        StringBuilder expected = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(payload)) {
            expected.append(String.format("%02x", b));
        }
        // On the External-Description LINE, not merely somewhere in the file: a receiver looking
        // for the package digest reads that field. A bare substring check would stay green if the
        // value drifted onto some other bag-info key, where nobody looks for it.
        String info = new String(entries.get("bag-info.txt"), StandardCharsets.UTF_8);
        String described = info.lines()
                .filter(line -> line.startsWith("External-Description:"))
                .findFirst()
                .orElse("");
        assertTrue(described.contains(expected.toString()),
                "External-Description does not state the SHA-256 of the bytes under data/, so "
                        + "the line a receipt is discussed against names a package this bag "
                        + "does not carry. (manifest-sha256.txt holds the same value as a "
                        + "verified path->digest binding, but it names a PATH, not the package "
                        + "-- a receipt quotes this line.) bag-info.txt was: " + info);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the export limits do not claim a check that may not have run")
    void theLimitsDoNotAssertAPass() {
        // These limits go out in X-Nemaki-Export-Limits on EVERY 200, and that header is what a
        // caller streaming the zip to disk actually reads. Saying "passes the reference
        // validator" there told them a package had been checked even when the body and the
        // X-Nemaki-Csip-Validated header both said it had not.
        String limits = EarkSipExportController.EXPORT_LIMITS;

        assertFalse(limits.contains("passes the reference validator"),
                "the limits assert a pass on every response, including unchecked ones: "
                        + limits);
        assertTrue(limits.contains("X-Nemaki-Csip-Validated"),
                "the limits do not say where the actual verdict is: " + limits);
        assertTrue(limits.contains("could not check"), limits);
    }
}
