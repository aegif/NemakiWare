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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Content-Disposition header-injection regression tests
 ******************************************************************************/
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link ImportExportUtils#contentDispositionAttachment} —
 * guards against the filename-spoofing / header-injection where a document name
 * containing a double-quote (or CR/LF) could break out of the
 * {@code filename="..."} parameter and inject a spoofed extension.
 */
public class ContentDispositionTest {

	@Test
	public void quoteIsNeutralizedInLegacyFilenameParam() {
		// Attacker name attempting to break out and inject a safe-looking extension.
		String malicious = "evil\".pdf;filename=\"safe.txt";
		String header = ImportExportUtils.contentDispositionAttachment(malicious);

		// The legacy filename="..." parameter must contain no unescaped double quote.
		// With the quotes removed there is exactly one opening and one closing quote
		// for the parameter, so the spoofed ';filename="safe.txt' can no longer break
		// out — its quote becomes part of the (harmless) quoted value as '_'.
		int legacyStart = header.indexOf("filename=\"") + "filename=\"".length();
		String legacy = header.substring(legacyStart);
		legacy = legacy.substring(0, legacy.indexOf('"'));
		assertFalse(legacy.contains("\""), "no stray quote inside filename param (quote-breakout neutralized)");
		assertTrue(legacy.contains("_"), "dangerous chars replaced with underscore");
	}

	@Test
	public void crlfIsStrippedFromLegacyParam() {
		String header = ImportExportUtils.contentDispositionAttachment("a\r\nSet-Cookie: x=y.pdf");
		assertFalse(header.contains("\r"), "no CR in header value");
		assertFalse(header.contains("\n"), "no LF in header value");
	}

	@Test
	public void nonAsciiPreservedViaFilenameStar() {
		String header = ImportExportUtils.contentDispositionAttachment("請求書.pdf");
		assertTrue(header.contains("filename*=UTF-8''"), "RFC 5987 filename* present");
		// '請' (U+8ACB) UTF-8 = E8 AB 8B -> %E8%AB%8B
		assertTrue(header.contains("%E8%AB%8B"), "non-ASCII percent-encoded in filename*");
	}

	@Test
	public void nullAndEmptyFallBackToDownload() {
		assertTrue(ImportExportUtils.contentDispositionAttachment(null).contains("filename=\"download\""));
		assertTrue(ImportExportUtils.contentDispositionAttachment("").contains("filename=\"download\""));
		assertEquals("download", ImportExportUtils.sanitizeHeaderFileName(null));
	}

	@Test
	public void benignNameIsPreserved() {
		String header = ImportExportUtils.contentDispositionAttachment("report.pdf");
		assertTrue(header.contains("filename=\"report.pdf\""));
	}
}
