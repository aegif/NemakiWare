package jp.aegif.nemaki.rest.purview.client;

public interface PurviewApiClient {

    PurviewProbeResult probeConnection(PurviewConnectionRequest request) throws PurviewClientException;
}
