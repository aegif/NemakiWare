package util;

import java.text.DateFormat;
import java.util.Date;
import java.util.GregorianCalendar;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

public class Formatter {

	public static String calToString(GregorianCalendar gc){
		Date date = gc.getTime();
		DateFormat df = DateFormat.getInstance();
		String result = df.format(date);
		return result;
	}

	public static Document objectToDocument(CmisObject obj){
		if(obj.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)){
			Document d = (Document)obj;
			return d;
		}

		return null;
	}
}
