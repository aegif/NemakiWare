package jp.aegif.nemaki.api.setup.filter;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates URLs to prevent SSRF attacks.
 *
 * <p>Two validation modes:</p>
 * <ul>
 *   <li><b>Strict</b> ({@code allowPrivateNetworks=false}): blocks private/internal networks,
 *       loopback, link-local, cloud metadata. For Normal Mode features.</li>
 *   <li><b>Setup</b> ({@code allowPrivateNetworks=true}): allows private/loopback addresses
 *       (localhost, Docker 172.x, etc.) but still blocks cloud metadata endpoints
 *       and restricts to service-expected ports.</li>
 * </ul>
 *
 * <p>Even in Setup Mode, SSRF surface is minimised by:</p>
 * <ol>
 *   <li>Cloud metadata always blocked (host + IP)</li>
 *   <li>Port restricted to known service ports (80, 443, 5984, 6984, 8080, 8443)</li>
 *   <li>Resource endpoints sanitise error responses to not leak internal topology</li>
 * </ol>
 */
public final class UrlValidator {

    private UrlValidator() {}

    /**
     * Ports that are expected for Setup Mode service connections.
     * CouchDB: 5984 (HTTP), 6984 (HTTPS)
     * OIDC / Vector / general HTTPS: 80, 443, 8080, 8443
     */
    static final Set<Integer> SETUP_ALLOWED_PORTS = Set.of(
            80, 443, 5984, 6984, 8080, 8443
    );

    /**
     * Strict validation — blocks all private/internal addresses, no port restriction.
     *
     * @return null if safe, error message if blocked
     */
    public static String validate(String url) {
        return validate(url, false);
    }

    /**
     * Validates a URL for server-side requests.
     *
     * @param url the URL to validate
     * @param allowPrivateNetworks if true, private/loopback IPs are permitted
     *        but port is restricted to {@link #SETUP_ALLOWED_PORTS}
     * @return null if safe, error message if blocked
     */
    public static String validate(String url, boolean allowPrivateNetworks) {
        if (url == null || url.trim().isEmpty()) {
            return "URL is required";
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return "Invalid URL syntax";
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            return "Only http and https schemes are allowed";
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "URL must contain a host";
        }

        // Always block cloud metadata endpoints — dangerous regardless of mode
        if (isCloudMetadataHost(host)) {
            return "Cloud metadata endpoints are blocked";
        }

        // In setup mode, restrict to known service ports to limit scanning surface
        if (allowPrivateNetworks) {
            int port = resolvePort(uri);
            if (!SETUP_ALLOWED_PORTS.contains(port)) {
                return "Port " + port + " is not allowed. Permitted ports: " + SETUP_ALLOWED_PORTS;
            }
        }

        // Resolve hostname to IP and check blocked ranges
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                // Cloud metadata IPs are always blocked
                if (isCloudMetadataAddress(addr)) {
                    return "Cloud metadata endpoints are blocked";
                }
                // In strict mode, also block private/internal ranges
                if (!allowPrivateNetworks && isPrivateAddress(addr)) {
                    return "Connections to private/internal network addresses are blocked";
                }
            }
        } catch (UnknownHostException e) {
            // Allow unresolvable hosts — the actual connection will fail with a clear error.
            // Intentional: during setup, Docker-internal names (e.g. "couchdb") may not
            // resolve from the validation context but will work inside the Docker network.
        }

        return null; // safe
    }

    // ---------------------------------------------------------------
    // Port resolution
    // ---------------------------------------------------------------

    private static int resolvePort(URI uri) {
        int port = uri.getPort();
        if (port > 0) return port;
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    // ---------------------------------------------------------------
    // Cloud metadata detection
    // ---------------------------------------------------------------

    private static boolean isCloudMetadataHost(String host) {
        return "169.254.169.254".equals(host)
                || "metadata.google.internal".equals(host);
    }

    private static boolean isCloudMetadataAddress(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) return false;
        return (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254
                && (bytes[2] & 0xFF) == 169 && (bytes[3] & 0xFF) == 254;
    }

    // ---------------------------------------------------------------
    // Private address detection
    // ---------------------------------------------------------------

    static boolean isPrivateAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()      // 10.x, 172.16-31.x, 192.168.x
                || addr.isAnyLocalAddress()        // 0.0.0.0
                || addr.isMulticastAddress()
                || isCarrierGradeNat(addr);        // 100.64.0.0/10
    }

    private static boolean isCarrierGradeNat(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}
