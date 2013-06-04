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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model;


public class VersionSeries extends NodeBase{
	public static final String TYPE = "versionSeries";
	
	private Boolean versionSeriesCheckedOut;
	private String versionSeriesCheckedOutBy;
	private String versionSeriesCheckedOutId;
	
	public VersionSeries(){
		super();
	}
	
	public VersionSeries(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public Boolean isVersionSeriesCheckedOut() {
		return versionSeriesCheckedOut;
	}
	public void setVersionSeriesCheckedOut(Boolean versionSeriesCheckedOut) {
		this.versionSeriesCheckedOut = versionSeriesCheckedOut;
	}
	public String getVersionSeriesCheckedOutBy() {
		return versionSeriesCheckedOutBy;
	}
	public void setVersionSeriesCheckedOutBy(String versionSeriesCheckedOutBy) {
		this.versionSeriesCheckedOutBy = versionSeriesCheckedOutBy;
	}
	public String getVersionSeriesCheckedOutId() {
		return versionSeriesCheckedOutId;
	}
	public void setVersionSeriesCheckedOutId(String versionSeriesCheckedOutId) {
		this.versionSeriesCheckedOutId = versionSeriesCheckedOutId;
	}
}
