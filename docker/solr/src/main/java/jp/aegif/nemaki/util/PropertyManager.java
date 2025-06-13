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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.util;

import java.util.List;

public interface PropertyManager {
	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key);

	/**
	 *
	 * @param key
	 * @return
	 */
	public List<String>readValues(String key);

	/**
	 * Modify a value of the property
	 * @param key
	 * @param value: new value
	 * @throws Exception
	 */
	public void modifyValue(String key, String value);

	/**
	 * Add a value to the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void addValue(String key, String value);

	/**
	 * Remove a value from the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void removeValue(String key, String value);


	/**
	 *
	 * @param key
	 * @return
	 */
	public String readHeadValue(String key);
}
