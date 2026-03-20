package jp.aegif.nemaki.rest.purview;

public interface PurviewApiClient {

    PurviewProbeResult probeConnection(PurviewConnectionRequest request) throws PurviewClientException;
}
