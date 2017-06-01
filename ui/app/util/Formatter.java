package util;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

public class Formatter {

	public static Document objectToDocument(CmisObject obj){
		if(obj.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)){
			Document d = (Document)obj;
			return d;
		}

		return null;
	}
	
	
	
	
}
