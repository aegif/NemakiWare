package util;

import org.apache.commons.lang3.StringUtils;

public class TabUtil {
	public static boolean isDefaultTab(String activateTabName){
		return isPropertyTab(activateTabName) || StringUtils.isEmpty(activateTabName);
	}

	public static boolean isPropertyTab(String activateTabName){
		return "property".equals(activateTabName);
	}

	public static boolean isFileTab(String activateTabName){
		return "file".equals(activateTabName);
	}

	public static boolean isVersionTab(String activateTabName){
		return "version".equals(activateTabName);
	}

	public static boolean isPreviewTab(String activateTabName){
		return "preview".equals(activateTabName);
	}

	public static boolean isPermissionTab(String activateTabName){
		return "permission".equals(activateTabName);
	}

	public static boolean isRelationshipTab(String activateTabName){
		return "relationship".equals(activateTabName);
	}

	public static String ACTION_PREFIX = "action-";
	public static boolean isActionTab(String activateTabName, String actionId){
		return (ACTION_PREFIX + actionId).equals(activateTabName);
	}

	public static String getActionIdFrom(String activateTabName){
		if(activateTabName.startsWith(ACTION_PREFIX)){
			return activateTabName.substring(ACTION_PREFIX.length());
		}
		return null;
	}


}
