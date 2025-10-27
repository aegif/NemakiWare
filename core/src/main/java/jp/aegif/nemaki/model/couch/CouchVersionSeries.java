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
package jp.aegif.nemaki.model.couch;

import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.model.VersionSeries;

public class CouchVersionSeries extends CouchNodeBase{
	private static final long serialVersionUID = 3137549058772266715L;
	@JsonProperty("versionSeriesCheckedOut")
	private Boolean versionSeriesCheckedOut;
	@JsonProperty("versionSeriesCheckedOutBy")
	private String versionSeriesCheckedOutBy;
	@JsonProperty("versionSeriesCheckedOutId")
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
		// TCK FIX: Return false when null to prevent NullPointerException
		return versionSeriesCheckedOut != null ? versionSeriesCheckedOut : false;
	}
	public void setVersionSeriesCheckedOut(Boolean versionSeriesCheckedOut) {
		// TCK DEBUG: Log setter call
		System.err.println("[TCK DEBUG] setVersionSeriesCheckedOut CALLED with value: " + versionSeriesCheckedOut);
		this.versionSeriesCheckedOut = versionSeriesCheckedOut;
	}
	public String getVersionSeriesCheckedOutBy() {
		return versionSeriesCheckedOutBy;
	}
	public void setVersionSeriesCheckedOutBy(String versionSeriesCheckedOutBy) {
		// TCK DEBUG: Log setter call
		System.err.println("[TCK DEBUG] setVersionSeriesCheckedOutBy CALLED with value: " + versionSeriesCheckedOutBy);
		this.versionSeriesCheckedOutBy = versionSeriesCheckedOutBy;
	}
	public String getVersionSeriesCheckedOutId() {
		return versionSeriesCheckedOutId;
	}
	public void setVersionSeriesCheckedOutId(String versionSeriesCheckedOutId) {
		// TCK DEBUG: Log setter call
		System.err.println("[TCK DEBUG] setVersionSeriesCheckedOutId CALLED with value: " + versionSeriesCheckedOutId);
		this.versionSeriesCheckedOutId = versionSeriesCheckedOutId;
	}

	// TCK FIX: Override to include versionSeries* fields as explicit fields
	// Without this, @JsonAnySetter captures them into additionalProperties instead
	@Override
	protected boolean isExplicitField(String fieldName) {
		return super.isExplicitField(fieldName) ||
		       "versionSeriesCheckedOut".equals(fieldName) ||
		       "versionSeriesCheckedOutBy".equals(fieldName) ||
		       "versionSeriesCheckedOutId".equals(fieldName);
	}

	public VersionSeries convert(){
		VersionSeries vs = new VersionSeries(super.convert());
		// TCK DEBUG: Log the values being converted
		System.err.println("[TCK DEBUG] CouchVersionSeries.convert() - ID: " + getId() + 
			", versionSeriesCheckedOut field=" + versionSeriesCheckedOut + 
			", isVersionSeriesCheckedOut()=" + isVersionSeriesCheckedOut() +
			", versionSeriesCheckedOutBy=" + versionSeriesCheckedOutBy +
			", versionSeriesCheckedOutId=" + versionSeriesCheckedOutId);
		vs.setVersionSeriesCheckedOut(isVersionSeriesCheckedOut());
		vs.setVersionSeriesCheckedOutBy(getVersionSeriesCheckedOutBy());
		vs.setVersionSeriesCheckedOutId(getVersionSeriesCheckedOutId());
		return vs;
	}
	
}
