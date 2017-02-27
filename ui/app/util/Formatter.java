package util;

import java.text.DateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

public class Formatter {

	public static String calToString(GregorianCalendar gc, Locale locale){
		if(gc == null) return "";
		Date date = gc.getTime();
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM,  locale);
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
