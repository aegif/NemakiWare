package jp.aegif.nemaki.rest.purview.client;

import java.util.Map;

public interface PurviewEntityRegistryClient {

    PurviewEntityPublishResult bulkCreateOrUpdateEntities(PurviewConnectionRequest request, Map<String, Object> payload)
            throws PurviewClientException;

    Map<String, Object> getEntityByUniqueAttribute(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) throws PurviewClientException;

    PurviewEntityPublishResult deleteByUniqueAttribute(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) throws PurviewClientException;

    PurviewEntityPublishResult createRelationship(
            PurviewConnectionRequest request,
            java.util.Map<String, Object> payload) throws PurviewClientException;

    PurviewEntityPublishResult deleteRelationshipByGuid(
            PurviewConnectionRequest request,
            String relationshipGuid) throws PurviewClientException;
}
