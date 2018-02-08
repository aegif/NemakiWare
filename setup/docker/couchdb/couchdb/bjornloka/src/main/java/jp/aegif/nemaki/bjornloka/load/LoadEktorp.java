package jp.aegif.nemaki.bjornloka.load;

import java.io.File;

import jp.aegif.nemaki.bjornloka.proxy.EktorpFactory;
import jp.aegif.nemaki.bjornloka.proxy.EktorpProxy;

public class LoadEktorp extends LoadAction{
	
	protected LoadEktorp(String url, String repositoryId, File file, boolean force) {
		super(url, repositoryId, file, force);
	}

	@Override
	public boolean load(){
		boolean initResult = initRepository();
		EktorpProxy proxy = EktorpFactory.getInstance().createProxy(url, repositoryId);
		boolean actionResult = action(proxy, file, initResult);
		return actionResult;
	}

	@Override
	public boolean initRepository() {
		boolean initResult = EktorpFactory.getInstance().initRepository(url, repositoryId, force);
		return initResult;
	}
}
