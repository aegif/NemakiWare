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
package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.VersionSeries;

import org.codehaus.jackson.annotate.JsonProperty;

public class CouchVersionSeries extends CouchNodeBase{
	private static final long serialVersionUID = 3137549058772266715L;
	private Boolean versionSeriesCheckedOut;
	private String versionSeriesCheckedOutBy;
	private String versionSeriesCheckedOutId;
	
	public CouchVersionSeries(){
		super();
	}
	
	public CouchVersionSeries(VersionSeries vs){
		super(vs);
		setVersionSeriesCheckedOut(vs.isVersionSeriesCheckedOut());
		setVersionSeriesCheckedOutBy(vs.getVersionSeriesCheckedOutBy());
		setVersionSeriesCheckedOutId(vs.getVersionSeriesCheckedOutId());
	}
	
	@JsonProperty("versionSeriesCheckedOut")
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

	public VersionSeries convert(){
		VersionSeries vs = new VersionSeries(super.convert());
		vs.setVersionSeriesCheckedOut(isVersionSeriesCheckedOut());
		vs.setVersionSeriesCheckedOutBy(getVersionSeriesCheckedOutBy());
		vs.setVersionSeriesCheckedOutId(getVersionSeriesCheckedOutId());
		return vs;
	}
	
}
