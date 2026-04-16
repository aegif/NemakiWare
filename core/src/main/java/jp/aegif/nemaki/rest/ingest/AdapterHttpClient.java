/*****************************************************************************
 Copyright (c) 2026 aegif.

 This file is part of NemakiWare.

 NemakiWare is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 *****************************************************************************/
package jp.aegif.nemaki.rest.ingest;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared {@link HttpClient} for ingest connector adapters.
 *
 * <p>The scheduler creates a fresh adapter instance on every fetch cycle
 * (e.g. {@code new SlackConnectorAdapter(token)} in
 * {@code IngestSchedulerService}).  If each adapter constructor allocated
 * its own {@link HttpClient}, every cycle would leak the client's
 * internal selector / I/O threads until the next GC.  Under sustained
 * scheduling this manifested as connection-pool exhaustion and rising
 * thread counts.</p>
 *
 * <p>Sharing a single static {@link HttpClient} across all adapters is
 * safe: {@link HttpClient} is documented as thread-safe and pools
 * connections per origin.  The 10s connect timeout matches the previous
 * per-adapter setting, and HTTP/2 is enabled by default.</p>
 *
 * <p>Tests that need a custom client (e.g. WireMock) should keep using
 * the constructor that accepts an {@link HttpClient}; only adapters that
 * previously instantiated their own client should switch to
 * {@link #shared()}.</p>
 */
public final class AdapterHttpClient {

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Some adapters (e.g. Box file download) rely on 302 redirects
            // to a CDN.  Following redirects by default is safe for the
            // ingest flows we currently use.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AdapterHttpClient() { /* utility */ }

    /** Returns the shared HttpClient instance. */
    public static HttpClient shared() {
        return SHARED;
    }
}
