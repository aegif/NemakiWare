package jp.aegif.nemaki.util.cache;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

public class CustomCache {
	private Cache cache;
	private final boolean cacheEnabled;
	
	public CustomCache(boolean cacheEnabled){
		this.cacheEnabled = cacheEnabled;
	}
	
	public Element get(String key){
		if(cacheEnabled){
			return this.cache.get(key);
		}else{
			return null;
		}
	}

	public void put(Element element){
		if(cacheEnabled){
			this.cache.put(element);
		}
	}
	
	public void remove(String key){
		if(cacheEnabled){
			this.cache.remove(key);
		}
	}
	
	public void removeAll(){
		if(cacheEnabled){
			this.cache.removeAll();
		}
	}
	
	public void setCache(Cache cache){
		this.cache = cache;
	}
}
