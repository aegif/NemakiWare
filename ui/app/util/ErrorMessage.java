package util;

import jp.aegif.nemaki.common.ErrorCode;
import play.i18n.Messages;

public class ErrorMessage {

	public static String getMessage(String code){
		if(code.equals(ErrorCode.ERR_WRONGPASSWORD )){
		  return Messages.get("view.message.password-change.wrong-old-password");
		}else if(code.equals(ErrorCode.ERR_RESTORE)){
			return Messages.get("view.message.restore.failure");
		}else if(code.equals(ErrorCode.ERR_RESTORE_BECAUSE_PARENT_NO_LONGER_EXISTS)){
			return Messages.get("view.message.parent-no-longer-exists");
		}else if(code.equals(ErrorCode.ERR_DESTROY)){
			return Messages.get("view.message.destroy.failure");
		}else{
			return code;
		}
	}
}
