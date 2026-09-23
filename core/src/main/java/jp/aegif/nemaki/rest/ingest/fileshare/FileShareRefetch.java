package jp.aegif.nemaki.rest.ingest.fileshare;

import java.io.InputStream;
import java.util.function.Function;

import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;

/**
 * The bytes of a dead-lettered FILE_SHARE item, fetched again from its source.
 *
 * <p>A dead-letter row written before the item was read carries no bytes. Replayed through the
 * plain import it created a content-less document, reported success, and the row — the only
 * record of the item — was deleted (review, P1). The DLQ controller asks here first: for the
 * systems this can fetch by the row's own identifiers the bytes come back from the source; for
 * the others the replay is refused with the row kept.
 *
 * <p>Box by the file id the request names; Dropbox by the {@code dropboxPath} the orchestrator
 * put in the request's metadata. Google Drive and OneDrive are not fetched again here (R111).
 */
public class FileShareRefetch {

    /** The adapters, by token; tests swap these for stubs. */
    Function<String, BoxConnectorAdapter> boxFactory = BoxConnectorAdapter::new;
    Function<String, DropboxConnectorAdapter> dropboxFactory = DropboxConnectorAdapter::new;

    public boolean canRefetch(ConnectorDefinition connector) {
        String system = connector.getSourceSystem();
        return "box".equals(system) || "dropbox".equals(system);
    }

    /** The item's bytes, fetched again; throws when the source will not give them or the row does not name the item. */
    public InputStream refetch(ConnectorDefinition connector, String token, ExternalIngestRequest request) throws Exception {
        String system = connector.getSourceSystem();
        if ("box".equals(system)) {
            String fileId = request.getSourceObjectId();
            if (fileId == null || fileId.isBlank()) {
                throw new IllegalArgumentException("the row names no Box file id");
            }
            return boxFactory.apply(token).downloadFile(fileId);
        }
        if ("dropbox".equals(system)) {
            Object path = request.getMetadata() == null ? null : request.getMetadata().get("dropboxPath");
            if (path == null || path.toString().isBlank()) {
                throw new IllegalArgumentException("the row names no Dropbox path");
            }
            return dropboxFactory.apply(token).downloadFile(path.toString());
        }
        throw new IllegalArgumentException(system + " items are not fetched again here");
    }
}
