package jp.aegif.nemaki.rest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.RelationshipService;

@Path("/repo/{repositoryId}/bulkCheckIn")
public class BulkCheckInResource extends ResourceBase {
	private static final Log log = LogFactory.getLog(BulkCheckInResource.class);

	private ContentService contentService;
	private VersioningService versioningService;
	private RelationshipService relationshipService;
	private TypeService typeService;
	private TypeManager typeManager;
	private CompileService compileService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
	public void setVersioningService(VersioningService versioningService) {
		this.versioningService = versioningService;
	}
	public void setRelationshipService(RelationshipService relationshipService) {
		this.relationshipService = relationshipService;
	}
	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}
	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}


	@SuppressWarnings("unchecked")
	@POST
	@Path("/execute")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String execute(MultivaluedMap<String,String> form, @Context HttpServletRequest httpRequest) {
		JSONObject result = new JSONObject();
//		JSONArray errMsg = new JSONArray();
		
		String repositoryId = form.get("repositoryId").get(0);
		String comment = form.get("comment").get(0);
		Boolean force = form.get("force").get(0).equals("true");
		Boolean copyRelations = form.get("copyRelations").get(0).equals("true");
		List<String> propertyIds = form.get("propertyId");
		List<String> propertyValues = form.get("propertyValue");
		List<String> objectIds = form.get("objectId");
		List<String> changeTokens = form.get("changeToken");
		//declare properties variable
		PropertiesImpl properties = new PropertiesImpl();
		
		Document firstdoc = contentService.getDocument(repositoryId, objectIds.get(0));
		String typeId = firstdoc.getObjectType();
		TypeDefinition typeDef = typeManager.getTypeByQueryName(repositoryId, typeId);
		
		CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
		// TODO: error checks
		if (propertyIds.size() != propertyValues.size()){
			//
		}
		if (objectIds.size() != changeTokens.size()){
			//
		}
		//Properties newProperties = new Properties();
		
		for(int i = 0; i < propertyIds.size(); ++i){
			String propertyName = propertyIds.get(i);
			String propertyValue = propertyValues.get(i);
			PropertyType propertyType = typeDef.getPropertyDefinitions().get(propertyName).getPropertyType();
			
			if(propertyType.equals(PropertyType.STRING) || propertyType.equals(PropertyType.ID) ||propertyType.equals(PropertyType.URI) ||propertyType.equals(PropertyType.HTML)){
				properties.addProperty(new PropertyStringImpl (propertyName,propertyValue));
			}
			else if(propertyType.equals(PropertyType.BOOLEAN)){
				properties.addProperty(new PropertyBooleanImpl (propertyName,propertyValue.equals("true")));
			}
			else if(propertyType.equals(PropertyType.DECIMAL)){
				properties.addProperty(new PropertyDecimalImpl (propertyName, new BigDecimal(propertyValue)));				
			}
			else if(propertyType.equals(PropertyType.INTEGER)){
				properties.addProperty(new PropertyIntegerImpl (propertyName, new BigInteger(propertyValue)));								
			}
			else if(propertyType.equals(PropertyType.DATETIME)){
				GregorianCalendar cal = new GregorianCalendar();
				DateFormat df = new SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss.sssXXX");
				ParsePosition pos = new ParsePosition(0);
				Date dt = df.parse(propertyValue, pos);
				if(pos.getErrorIndex() != -1){
					//  parse error -> skip this value
					continue;
				}
				cal.setTime(dt);
				properties.addProperty(new PropertyDateTimeImpl (propertyName, cal));								
			}
			else{
				continue;
			}
		}

		for(int i = 0; i < objectIds.size() ;++i){
			String objectId = objectIds.get(i);
			Document doc = contentService.getDocument(repositoryId, objectId);
			VersionSeries vs = contentService.getVersionSeries(repositoryId, doc);
			Holder<String> docIdHolder = new Holder<String>(objectId);
			typeId = doc.getObjectType();
			if (force && !vs.isVersionSeriesCheckedOut()){
				versioningService.checkOut(callContext, repositoryId, docIdHolder , new Holder<Boolean>(true), null);
			}
			// Check in with the property set and comment
			versioningService.checkIn(callContext, repositoryId, docIdHolder, true, properties, null, comment, null, null, null, null);
			Document newDoc = contentService.getDocumentOfLatestVersion(repositoryId, doc.getVersionSeriesId());
			// get relationships if exists
			if (copyRelations){
				List<Relationship> relList = contentService.getRelationsipsOfObject(repositoryId, objectId, RelationshipDirection.SOURCE);
			// copy relationships
				for(Relationship rel:relList){
					PropertiesImpl newProps = new PropertiesImpl();
					newProps.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID,rel.getObjectType()));
					newProps.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID,rel.getTargetId()));
					newProps.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID,newDoc.getId()));	
					newProps.addProperty(new PropertyStringImpl(PropertyIds.NAME,rel.getName()));	
					Relationship newRel = contentService.createRelationship(callContext, repositoryId, newProps, null, null, null, null);
					newRel.setModifier("bulkCheckInService");
				}
			}
		}
		// todo set return messages
		return result.toJSONString();
	}
}
