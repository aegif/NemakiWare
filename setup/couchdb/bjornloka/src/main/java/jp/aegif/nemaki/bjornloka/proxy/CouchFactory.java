package jp.aegif.nemaki.bjornloka.proxy;

public interface CouchFactory {
	CouchProxy createProxy(String url, String repositoryId);
	boolean initRepository(String url, String repositoryId, boolean force);
}
