package jp.aegif.nemaki.rest.purview.client;

import java.util.Map;

public interface PurviewSchemaRegistryClient {

    PurviewSchemaPublishResult applySchema(PurviewConnectionRequest request, Map<String, Object> payload)
            throws PurviewClientException;
}
