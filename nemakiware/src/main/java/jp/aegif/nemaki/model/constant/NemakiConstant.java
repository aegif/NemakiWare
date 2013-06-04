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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model.constant;

public interface NemakiConstant {
	final String NAMESPACE_ASPECTS = "http://www.aegif.jp/Nemaki/feature/aspects/";
	final String NAMESPACE_ACL_INHERITANCE = "http://www.aegif.jp/Nemaki/feature/aclInheritance/";
	
	final String EXTNAME_ASPECTS = "aspects";
	final String EXTNAME_ASPECT = "aspect";
	final String EXTNAME_ASPECT_ATTRIBUTES = "attributes";
	final String EXTATTR_ASPECT_ID = "id";
	final String EXTNAME_ASPECT_PROPERTIES = "properties";
	final String EXTNAME_ASPECT_PROPERTY = "property";
	final String EXTATTR_ASPECT_PROPERTY_ID = "id";
	
	final String EXTNAME_ACL_INHERITED = "inherited";
	
	final String PATH_SEPARATOR = "/";
}
