package jp.aegif.nemaki.cmis.factory.info;

public interface RepositoryInfo extends org.apache.chemistry.opencmis.commons.data.RepositoryInfo{
	public String getNameSpace();
	public void setLatestChangeLogToken(String latestChangeLogToken);
}
