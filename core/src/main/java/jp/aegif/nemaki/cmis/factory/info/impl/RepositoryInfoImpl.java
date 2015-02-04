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
package jp.aegif.nemaki.cmis.factory.info.impl;

import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * Information about the CMIS repository, the optional capabilities it supports
 * and its access control information.
 */
public class RepositoryInfoImpl extends org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl implements RepositoryInfo{

	private static final long serialVersionUID = -8027732136814092210L;
	//Custom info property
	private String nameSpace;
	
	public void setup(){
		//Set changesOnType property
		List<BaseTypeId> baseTypes = new ArrayList<BaseTypeId>();
		baseTypes.add(BaseTypeId.CMIS_DOCUMENT);
		baseTypes.add(BaseTypeId.CMIS_FOLDER);
		setChangesOnType(baseTypes);
	}

	@Override
	public String getNameSpace() {
		return nameSpace;
	}

	public void setNameSpace(String nameSpace) {
		this.nameSpace = nameSpace;
	}
}
