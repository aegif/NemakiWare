package jp.aegif.nemaki.repository.info;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;

public interface NemakiRepositoryInfo extends RepositoryInfo{
	public String getNameSpace();
	public void setLatestChangeLogToken(String latestChangeLogToken);
}
