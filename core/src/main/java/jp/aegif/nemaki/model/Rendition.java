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
package jp.aegif.nemaki.model;

import java.io.InputStream;

import jp.aegif.nemaki.util.constant.NodeType;


public class Rendition extends NodeBase{
	
	private String mimetype;
	private long length;
	private String title;
	private String kind;
	private long height;
	private long width;
	private String renditionDocumentId;
	private InputStream inputStream;

	/**
	 * What veraPDF found about this copy, or null when nothing was checked.
	 *
	 * <h2>In the clear, and NOT covered by the evidence chain</h2>
	 *
	 * <p>The duplication entry commits to this value — it is an input to the entry's digest —
	 * but the entry carries a digest and not the value, so a report built from the chain alone
	 * cannot tell a reader what was found. That left the product checking PDF/A and nobody
	 * being able to read the answer.
	 *
	 * <p>So the answer lives here too, in the clear, on a row that anything with database
	 * access can edit. That is exactly why the report says where it came from: a reader holding
	 * both can recompute the entry digest and see whether the two agree, and a reader holding
	 * only this row has a claim rather than evidence. Putting it INTO the chain instead would
	 * change what the entry hash covers and invalidate every stored hash.
	 */
	private String pdfaOutcome;

	/** The profile it was checked against, e.g. {@code 1b}. Null when nothing was checked. */
	private String pdfaFlavour;


	public Rendition(){
		super();
		setType(NodeType.RENDITION.value());
	}

	public Rendition(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	public String getMimetype() {
		return mimetype;
	}

	public void setMimetype(String mimetype) {
		this.mimetype = mimetype;
	}

	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public long getHeight() {
		return height;
	}

	public void setHeight(long height) {
		this.height = height;
	}

	public long getWidth() {
		return width;
	}

	public void setWidth(long width) {
		this.width = width;
	}

	public String getRenditionDocumentId() {
		return renditionDocumentId;
	}

	public void setRenditionDocumentId(String renditionDocumentId) {
		this.renditionDocumentId = renditionDocumentId;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
	}

	public String getPdfaOutcome() {
		return pdfaOutcome;
	}

	public void setPdfaOutcome(String pdfaOutcome) {
		this.pdfaOutcome = pdfaOutcome;
	}

	public String getPdfaFlavour() {
		return pdfaFlavour;
	}

	public void setPdfaFlavour(String pdfaFlavour) {
		this.pdfaFlavour = pdfaFlavour;
	}

}
