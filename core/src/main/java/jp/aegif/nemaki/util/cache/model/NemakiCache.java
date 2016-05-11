package jp.aegif.nemaki.util.cache.model;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

public class NemakiCache<T> {
	private Cache cache;
	private final boolean cacheEnabled;
	private static final Log log = LogFactory.getLog(NemakiCache.class);
	
	public NemakiCache(boolean cacheEnabled, Cache cache){
		this.cacheEnabled = cacheEnabled;
		this.cache = cache;
	}
	
	public T get(String key){
		if(cacheEnabled){
			Element element = cache.get(key);
			if(element == null || element.getObjectValue() == null){
				return null;
			}else{
				return (T)element.getObjectValue();
			}
		}else{
			return null;
		}
	}

	public void put(String key, T data){
		if(cacheEnabled){
			Element element = new Element(key, data);
			cache.put(element);
		}
	}
	
	public void put(Element element){
		if(cacheEnabled){
			cache.put(element);
		}
	}
	
	public void remove(String key){
		if(cacheEnabled){
			cache.remove(key);
		}
	}
	
	public void removeAll(){
		if(cacheEnabled){
			cache.removeAll();
		}
	}
	
	public Cache getCache(){
		return this.cache;
	}
	
	public void setCache(Cache cache){
		this.cache = cache;
	}
}
