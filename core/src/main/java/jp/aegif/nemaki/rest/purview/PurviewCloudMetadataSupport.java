package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;

final class PurviewCloudMetadataSupport {

    private static final String CLOUD_METADATA_ASPECT = "nemaki:cloudDriveMetadata";
    private static final String CLOUD_PROVIDER = "nemaki:cloudProvider";
    private static final String CLOUD_FILE_ID = "nemaki:cloudFileId";
    private static final String CLOUD_FILE_URL = "nemaki:cloudFileUrl";
    private static final String CLOUD_LAST_SYNCED_AT = "nemaki:cloudLastSyncedAt";

    private PurviewCloudMetadataSupport() {
    }

    static boolean hasCloudMetadata(Content content) {
        return !isBlank(getCloudProvider(content))
                || !isBlank(getExternalFileId(content))
                || !isBlank(getCloudFileUrl(content))
                || !isBlank(getCloudLastSyncedAt(content));
    }

    static String getCloudProvider(Content content) {
        return nullIfBlank(readProperty(content, CLOUD_PROVIDER));
    }

    static String getExternalFileId(Content content) {
        return nullIfBlank(readProperty(content, CLOUD_FILE_ID));
    }

    static String getCloudFileUrl(Content content) {
        return nullIfBlank(readProperty(content, CLOUD_FILE_URL));
    }

    static String getCloudLastSyncedAt(Content content) {
        return nullIfBlank(readProperty(content, CLOUD_LAST_SYNCED_AT));
    }

    private static String readProperty(Content content, String propertyKey) {
        if (content == null || propertyKey == null || propertyKey.isBlank()) {
            return null;
        }

        String fromAspect = readAspectProperty(content.getAspects(), propertyKey);
        if (!isBlank(fromAspect)) {
            return fromAspect;
        }
        return readProperty(content.getSubTypeProperties(), propertyKey);
    }

    private static String readAspectProperty(List<Aspect> aspects, String propertyKey) {
        if (aspects == null || aspects.isEmpty()) {
            return null;
        }

        for (Aspect aspect : aspects) {
            if (aspect == null || !CLOUD_METADATA_ASPECT.equals(aspect.getName())) {
                continue;
            }
            String value = readProperty(aspect.getProperties(), propertyKey);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String readProperty(List<Property> properties, String propertyKey) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }

        for (Property property : properties) {
            if (property == null || !propertyKey.equals(property.getKey()) || property.getValue() == null) {
                continue;
            }
            String value = property.getValue().toString();
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String nullIfBlank(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
