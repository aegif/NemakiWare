package jp.aegif.nemaki.bjornloka.load;

import java.io.File;

import jp.aegif.nemaki.bjornloka.proxy.CloudantFactory;
import jp.aegif.nemaki.bjornloka.proxy.CloudantProxy;

public class LoadCloudant extends LoadAction{
	private String url;
	private String repositoryId;
	private File file;
	private boolean force;

	protected LoadCloudant(String url, String repositoryId, File file, boolean force) {
		super(url, repositoryId, file, force);
	}

	@Override
	public boolean load() {
		boolean initResult = initRepository();
		CloudantProxy proxy = CloudantFactory.getInstance().createProxy(url, repositoryId);
		boolean actionResult = action(proxy, file, initResult);
		return actionResult;
	}

	@Override
	public boolean initRepository() {
		return CloudantFactory.getInstance().initRepository(url, repositoryId, force);
	}

}
