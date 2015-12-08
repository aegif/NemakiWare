package util;

import jp.aegif.nemaki.common.ErrorCode;
import play.i18n.Messages;

public class ErrorMessage {

	public static String getMessage(String code){
		if(code.equals(ErrorCode.ERR_WRONGPASSWORD )){
		  return Messages.get("view.message.password-change.wrong-old-password");
		}else{
			return code;
		}
	}
}
