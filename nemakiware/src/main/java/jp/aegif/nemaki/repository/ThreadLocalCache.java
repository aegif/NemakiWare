/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Takeshi Totani(https://github.com/to2y) 
 ******************************************************************************/
package jp.aegif.nemaki.repository;

import java.util.HashMap;
import java.util.Map;

public class ThreadLocalCache<K,V> {

	private static class LocalCache<K,V> {
		final Map<K,V> map = new HashMap<K,V>();

		V get(K key) {
			V val = map.get(key);
			return val;
		}

		void set(K key, V value) {
			map.put(key, value);
		}

		void clear() {
			map.clear();
		}
	}

	//private static ThreadLocal
    private final ThreadLocal<LocalCache<K,V>> localCaches = new ThreadLocal<LocalCache<K,V>>() {
        protected LocalCache<K,V> initialValue() {
            return new LocalCache<K, V>();
        }
    };
    
    public V get(K key) {
    	return localCaches.get().get(key);
    }
    
    public void set(K key, V value) {
    	localCaches.get().set(key, value);
    }
    
    public void clear() {
    	localCaches.get().clear();
    }

}
