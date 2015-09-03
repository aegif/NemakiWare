package jp.aegif.nemaki.bjornloka.dump;

import java.io.File;

import jp.aegif.nemaki.bjornloka.proxy.CloudantFactory;
import jp.aegif.nemaki.bjornloka.proxy.CloudantProxy;

public class DumpCloudant extends DumpAction{

	public DumpCloudant(String url, String repositoryId, File file, boolean omitTimestamp) {
		super(url, repositoryId, file, omitTimestamp);
	}

	@Override
	public String dump() {
		CloudantProxy proxy = CloudantFactory.getInstance().createProxy(url, repositoryId);
		String actionResult = action(proxy, file, omitTimestamp);
		return actionResult;
	}
}