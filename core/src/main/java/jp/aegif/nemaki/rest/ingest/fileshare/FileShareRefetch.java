package jp.aegif.nemaki.rest.ingest.fileshare;

import java.io.InputStream;
import java.util.function.Function;

import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ExternalIngestRequest;
import jp.aegif.nemaki.rest.ingest.SourceArchetype;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter;

/**
 * The bytes of a dead-lettered FILE_SHARE item, fetched again from its source.
 *
 * <p>A dead-letter row written before the item was read carries no bytes. Replayed through the
 * plain import it created a content-less document, reported success, and the row — the only
 * record of the item — was deleted (review, P1). The DLQ controller asks here first: for the
 * systems this can fetch by the row's own identifiers the bytes come back from the source; for
 * the others the replay is refused with the row kept.
 *
 * <p>By the item's IDENTITY, never by a path: a path names whatever sits there now, and a file
 * that moved away and was replaced would come back as another file's bytes under this row's
 * id (review, P1). Box by the file id the request names; Dropbox by its {@code id:…} file id,
 * which its download API accepts in place of a path. A Slack attachment by the download URL
 * the orchestrator put in the request's metadata ({@code slackFileUrl}). Google Drive and
 * OneDrive files, and the attachments of the other chat and mail connectors, are not fetched
 * again here (R111) — their rows are refused rather than replayed as empty documents.
 */
public class FileShareRefetch {

    /** The adapters, by token; tests swap these for stubs. */
    Function<String, BoxConnectorAdapter> boxFactory = BoxConnectorAdapter::new;
    Function<String, DropboxConnectorAdapter> dropboxFactory = DropboxConnectorAdapter::new;
    Function<String, SlackConnectorAdapter> slackFactory = SlackConnectorAdapter::new;

    /** Whether the row's item is one this can fetch again: a Box / Dropbox file, or a Slack attachment. */
    public boolean canRefetch(ConnectorDefinition connector, ExternalIngestRequest request) {
        String system = connector.getSourceSystem();
        if (connector.getSourceArchetype() == SourceArchetype.FILE_SHARE) {
            return "box".equals(system) || "dropbox".equals(system);
        }
        return "slack".equals(system) && "attachment".equals(request.getSourceObjectType());
    }

    /**
     * The item's bytes, fetched again. {@link IllegalArgumentException} when the row does not name
     * the item (the caller refuses the replay); any other exception is the source's answer.
     */
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
            String fileId = request.getSourceObjectId();
            if (fileId == null || !fileId.startsWith("id:")) {
                throw new IllegalArgumentException("the row names no Dropbox file id (an 'id:…' value; a path would"
                        + " fetch whatever sits at it now)");
            }
            return dropboxFactory.apply(token).downloadFile(fileId);
        }
        if ("slack".equals(system) && "attachment".equals(request.getSourceObjectType())) {
            Object url = request.getMetadata() == null ? null : request.getMetadata().get("slackFileUrl");
            if (url == null || url.toString().isBlank()) {
                throw new IllegalArgumentException("the row names no Slack download URL (rows written before this "
                        + "connector recorded one cannot be fetched again)");
            }
            return slackFactory.apply(token).downloadFile(url.toString());
        }
        throw new IllegalArgumentException(system + " items are not fetched again here");
    }
}
