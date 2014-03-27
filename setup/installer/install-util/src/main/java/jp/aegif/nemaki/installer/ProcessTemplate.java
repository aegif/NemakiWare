/*******************************************************************************
 * Copyright (c) 2014 aegif.
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
package jp.aegif.nemaki.installer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProcessTemplate {
	public static void main(String[] args) throws IOException {

		if (args.length < 2) {
			System.out.println("Wrong number of arguments. Abort");
			return;
		}

		String templateFilePath = args[0];
		File templateFile = new File(templateFilePath);

		List<File> propertiesFiles = new ArrayList<File>();
		for (int i = 1; i < args.length; i++) {
			String propertiesFilePath = args[i];
			File propertiesFile = new File(propertiesFilePath);
			if (propertiesFile.exists()) {
				System.out.println(propertiesFile);
				propertiesFiles.add(propertiesFile);
			}
		}

		FileUtil.replaceAllKeys(templateFile,
				propertiesFiles, StringPool.PATTERN_USER_INPUT_SPEC);
	}
}
