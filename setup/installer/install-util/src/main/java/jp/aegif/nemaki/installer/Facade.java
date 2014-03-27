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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Facade {
	public static void main(String[] args) throws IOException {
		final String ACTION_PROCESS_TEMPLATE = "process-template";
		final String ACTION_CONFIG_TOMCAT = "config-tomcat";
		final String ACTION_CONFIG_SHARE = "config-share";
		final String ACTION_DELETE_TMP_FILES = "delete-tmp-files";

		//Check arguments
		if (args.length < 1) {
			String msg = "At least specify an action:";
			msg += ACTION_PROCESS_TEMPLATE + " | ";
			msg += ACTION_CONFIG_TOMCAT + " | ";
			msg += ACTION_DELETE_TMP_FILES;
			System.out.println(msg);
			return;
		}

		String action = args[0];

		//Dispatch
		String[] _args = shiftArray(args);
		if(ACTION_PROCESS_TEMPLATE.equals(action)){
			ProcessTemplate.main(_args);
		}else if(ACTION_CONFIG_TOMCAT.equals(action)){
			ConfigTomcat.main(_args);
		}else if(ACTION_CONFIG_SHARE.equals(action)){
			ConfigShare.main(_args);
		}else if(ACTION_DELETE_TMP_FILES.equals(action)){
			DeleteTmpFiles.main(_args);
		}else{
			System.out.println("Such action does not exist");
			return;
		}
	}

	public static String[] shiftArray(String[] array){
		if(array.length < 2){
			return null;
		}else{
			List<String> list = new ArrayList<String>();
			for(int i=1; i < array.length; i++){
				list.add(array[i]);
			}
			String[] result = list.toArray(new String[list.size()]);
			return result;
		}
	}
}
