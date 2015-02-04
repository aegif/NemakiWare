package jp.aegif.nemaki.util.cache;

import net.sf.ehcache.Cache;

public interface NemakiCache {
	public CustomCache getObjectDataCache();
	public Cache getPropertiesCache();
	public Cache getTypeCache();
	public Cache getContentCache();
	public Cache getVersionSeriesCache();
	public Cache getAttachmentCache();
	public Cache getChangeEventCache();
	public Cache getLatestChangeTokenCache();
	public Cache getUserCache();
	public Cache getUsersCache();
	public Cache getGroupCache();
	public Cache getGroupsCache();
	
	public void removeCmisCache(String objectId);
}
