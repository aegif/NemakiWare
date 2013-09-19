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

import jp.aegif.nemaki.model.Change;

public class RequestDurationCacheBean {

	private ThreadLocalCache<String, Change> latestChangeCache = new ThreadLocalCache<String, Change>();
	
	public ThreadLocalCache<String, Change> getLatestChangeCache() {
		return latestChangeCache;
	}
}
