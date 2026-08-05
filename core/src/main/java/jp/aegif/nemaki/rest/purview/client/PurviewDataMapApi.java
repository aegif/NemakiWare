package jp.aegif.nemaki.rest.purview.client;

/**
 * The one place the Data Map surface's API version lives.
 *
 * <p>The GA Data Map REST API requires {@code api-version} on every operation under
 * {@code /datamap}. Three clients speak that surface, and each carrying its own copy of the
 * version is how they came to disagree: the entity client sent it, the connection probe and the
 * typedef apply did not — so on the GA surface half the product worked and the half that runs
 * <em>first</em> (probe, then schema apply) failed with a request-shape error that looks like a
 * credentials problem.
 *
 * <p>The classic {@code catalog/} surface and Atlas OSS take no version parameter, so the append
 * is a no-op for them by way of {@link PurviewConnectionRequest#isPurviewDataMap()}.
 */
final class PurviewDataMapApi {

    static final String API_VERSION = "2023-09-01";

    private PurviewDataMapApi() {
    }

    /** The URI with {@code api-version} appended iff the request targets the Data Map surface. */
    static String withApiVersion(String uri, PurviewConnectionRequest request) {
        if (request == null || !request.isPurviewDataMap()) {
            return uri;
        }
        return uri + (uri.contains("?") ? "&" : "?") + "api-version=" + API_VERSION;
    }
}
