package jp.aegif.nemaki.patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.NemakiObjectType;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;

public class Patch_20160815 extends AbstractNemakiPatch{
	private static Logger logger = LoggerFactory.getLogger(Patch_20160815.class);

	public String getName() {
    	return "patch_20160815";
	}

	@Override
	protected void applySystemPatch() {
		addNemakiConfDb();
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		addConfigurationView(repositoryId);

		createSystemFolder(repositoryId);

		addPrincipalView(repositoryId);
		addPrincipalTypeDefinition(repositoryId);

		migrateUsers(repositoryId);
		migrateGroups(repositoryId);
	}

	private void addNemakiConfDb(){
		final String dbName = SystemConst.NEMAKI_CONF_DB;
		patchUtil.addDb(dbName);
		addConfigurationView(dbName);
	}

	private void addConfigurationView(String repositoryId){
		patchUtil.addView(repositoryId, "configuration", "function(doc) { if (doc.type == 'configuration')  emit(doc._id, doc) }");
	}

	private void createSystemFolder(String repositoryId){

		Configuration configuration = patchUtil.getContentDaoService().getConfiguration(repositoryId);
		if(configuration == null || configuration.getConfiguration().get(PropertyKey.SYSTEM_FOLDER) == null){
			PropertiesImpl properties = new PropertiesImpl();
			properties.addProperty(new PropertyStringImpl("cmis:name", ".system"));
			properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
			properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));

			final String rootFolderId = patchUtil.getRepositoryInfoMap().get(repositoryId).getRootFolderId();
			Folder root = patchUtil.getContentDaoService().getFolder(repositoryId, rootFolderId);

			// acl
			final String ANYONE = patchUtil.repositoryInfoMap.get(repositoryId).getPrincipalIdAnyone();
			AccessControlListImpl acl = new AccessControlListImpl(
				new ArrayList<org.apache.chemistry.opencmis.commons.data.Ace>(
					Arrays.asList(
						new AccessControlEntryImpl(
							new AccessControlPrincipalDataImpl(ANYONE),
							Arrays.asList("cmis:none")
						)
					)
				)
			);

			// create
			Folder systemFolder = patchUtil.getContentService().createFolder(new SystemCallContext(repositoryId), repositoryId, properties, root, null, acl, null, null);

			// log to configuration
			updateConfiguration(repositoryId, PropertyKey.SYSTEM_FOLDER, systemFolder.getId());
		}
	}

	private void updateConfiguration(String repositoryId, String key, String value){
		Configuration configuration = patchUtil.getContentDaoService().getConfiguration(repositoryId);
		if(configuration == null){
			configuration = patchUtil.getContentDaoService().create(repositoryId, new Configuration());
		}

		configuration.getConfiguration().put(key, value);
		patchUtil.getContentDaoService().update(repositoryId, configuration);
	}

	private void addPrincipalView(String repositoryId){
		patchUtil.addView(repositoryId, "userItemsById", "function(doc) { if (doc.type == 'cmis:item' && doc.userId)  emit(doc.userId, doc) }");
		patchUtil.addView(repositoryId, "groupItemsById", "function(doc) { if (doc.type == 'cmis:item' && doc.groupId)  emit(doc.groupId, doc) }");
	}

	private void addPrincipalTypeDefinition(String repositoryId){
		addUserTypeDefinition(repositoryId);
		addGroupTypeDefinition(repositoryId);
	}

	private void addUserTypeDefinition(String repositoryId){
		final CallContext context = new SystemCallContext(repositoryId);
		try{
			TypeDefinition _type = patchUtil.getRepositoryService().getTypeDefinition(context, repositoryId, "nemaki:user", null);
		}catch(CmisObjectNotFoundException e){
				ItemTypeDefinitionImpl tdf = new ItemTypeDefinitionImpl();
				tdf.setId(NemakiObjectType.nemakiUser);
				tdf.setLocalName(NemakiObjectType.nemakiUser);
				tdf.setQueryName(NemakiObjectType.nemakiUser);
				tdf.setDisplayName(NemakiObjectType.nemakiUser);
				tdf.setBaseTypeId(BaseTypeId.CMIS_ITEM);
				tdf.setParentTypeId("cmis:item");
				tdf.setDescription(NemakiObjectType.nemakiUser);
				tdf.setIsCreatable(true);
				tdf.setIsFileable(true);
				tdf.setIsQueryable(true);
				tdf.setIsControllablePolicy(false);
				tdf.setIsControllableAcl(true);
				tdf.setIsFulltextIndexed(false);
				tdf.setIsIncludedInSupertypeQuery(true);
				TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
				typeMutability.setCanCreate(true);
				typeMutability.setCanUpdate(false);
				typeMutability.setCanDelete(false);
				tdf.setTypeMutability(typeMutability);

				Map<String, PropertyDefinition<?>> props = new HashMap<>();
				patchUtil.addSimpleProperty(props, "nemaki:userId", Cardinality.SINGLE, Updatability.ONCREATE, true, true);
				patchUtil.addSimpleProperty(props, "nemaki:firstName", Cardinality.SINGLE, Updatability.READWRITE, false, true);
				patchUtil.addSimpleProperty(props, "nemaki:lastName", Cardinality.SINGLE, Updatability.READWRITE, false, true);
				patchUtil.addSimpleProperty(props, "nemaki:email", Cardinality.SINGLE, Updatability.READWRITE, false, true);
				patchUtil.addSimpleProperty(props, "nemaki:favorites", Cardinality.MULTI, Updatability.READWRITE, false, false);
				//TODO admin flag property
				tdf.setPropertyDefinitions(props);

				patchUtil.getRepositoryService().createType(context, repositoryId, tdf, null);
		}
	}

	private void addGroupTypeDefinition(String repositoryId){
		final CallContext context = new SystemCallContext(repositoryId);
		try{
			TypeDefinition _type = patchUtil.getRepositoryService().getTypeDefinition(context, repositoryId, "nemaki:group", null);
		}catch(CmisObjectNotFoundException e){
				ItemTypeDefinitionImpl tdf = new ItemTypeDefinitionImpl();
				tdf.setId(NemakiObjectType.nemakiGroup);
				tdf.setLocalName(NemakiObjectType.nemakiGroup);
				tdf.setQueryName(NemakiObjectType.nemakiGroup);
				tdf.setDisplayName(NemakiObjectType.nemakiGroup);
				tdf.setBaseTypeId(BaseTypeId.CMIS_ITEM);
				tdf.setParentTypeId("cmis:item");
				tdf.setDescription(NemakiObjectType.nemakiGroup);
				tdf.setIsCreatable(true);
				tdf.setIsFileable(true);
				tdf.setIsQueryable(true);
				tdf.setIsControllablePolicy(false);
				tdf.setIsControllableAcl(true);
				tdf.setIsFulltextIndexed(false);
				tdf.setIsIncludedInSupertypeQuery(true);
				TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
				typeMutability.setCanCreate(true);
				typeMutability.setCanUpdate(false);
				typeMutability.setCanDelete(false);
				tdf.setTypeMutability(typeMutability);

				Map<String, PropertyDefinition<?>> props = new HashMap<>();
				patchUtil.addSimpleProperty(props, "nemaki:groupId", Cardinality.SINGLE, Updatability.ONCREATE, true, true);
				patchUtil.addSimpleProperty(props, "nemaki:users", Cardinality.MULTI, Updatability.READWRITE, false, false);
				patchUtil.addSimpleProperty(props, "nemaki:groups", Cardinality.MULTI, Updatability.READWRITE, false, false);

				//TODO admin flag property
				tdf.setPropertyDefinitions(props);

				patchUtil.getRepositoryService().createType(context, repositoryId, tdf, null);
		}
	}

	private void migrateUsers(String repositoryId){
		Folder usersFolder = patchUtil.getOrCreateSystemSubFolder(repositoryId, "users");

		List<User> users = principalService.getUsers(repositoryId);
		for(User user : users){
			UserItem userItem = convert(repositoryId, user, usersFolder);
			patchUtil.getContentService().update(new SystemCallContext(repositoryId), repositoryId, userItem);
		}
	}

	private void migrateGroups(String repositoryId){
		Folder groupsFolder = patchUtil.getOrCreateSystemSubFolder(repositoryId, "groups");

		List<Group> groups = principalService.getGroups(repositoryId);
		for(Group group : groups){
			GroupItem groupItem = convert(repositoryId, group, groupsFolder);
			patchUtil.getContentService().update(new SystemCallContext(repositoryId), repositoryId, groupItem);
		}
	}

	private UserItem convert(String repositoryId, User user, Folder parent){
		UserItem userItem = new UserItem();
		userItem.setName(user.getName());
		userItem.setCreator(user.getCreator());
		userItem.setCreated(user.getCreated());
		userItem.setModifier(user.getModifier());
		userItem.setModified(user.getModified());

		// type
		userItem.setObjectType("nemaki:user");

		// id
		userItem.setId(user.getId());

		// parent id
		//final String systemFolder = propertyManager.readValue(repositoryId, PropertyKey.SYSTEM_FOLDER);
		userItem.setParentId(parent.getId());

		// subtype properties
		List<Property> properties = new ArrayList<>();
		properties.add(new Property("nemaki:userId", user.getUserId()));
		properties.add(new Property("nemaki:firstName", user.getFirstName()));
		properties.add(new Property("nemaki:lastName", user.getLastName()));
		properties.add(new Property("nemaki:favorites", user.getFavorites()));
		userItem.setSubTypeProperties(properties);

		// change token: skip
		// acl
		Acl acl = new Acl();
		acl.setLocalAces(Arrays.asList(
			new Ace(
				user.getUserId(),
				Arrays.asList("cmis:read", "cmis:write"),
				true)
			)
		);
		userItem.setAcl(acl);

		// password
		userItem.setPassowrd(user.getPasswordHash());
		// userId (INTENTIONALLY DUPLICATE of nemaki:userId for CouchDB view)
		userItem.setUserId(user.getUserId());
		// admin flag
		userItem.setAdmin(user.isAdmin());

		return userItem;
	}

	private GroupItem convert(String repositoryId, Group group, Folder parent){
		GroupItem groupItem = new GroupItem();
		groupItem.setName(group.getName());
		groupItem.setCreator(group.getCreator());
		groupItem.setCreated(group.getCreated());
		groupItem.setModifier(group.getModifier());
		groupItem.setModified(group.getModified());

		// type
		groupItem.setObjectType("nemaki:group");

		// id
		groupItem.setId(group.getId());

		// parent id
		groupItem.setParentId(parent.getId());

		// subtype properties
		List<Property> properties = new ArrayList<>();
		properties.add(new Property("nemaki:groupId", group.getGroupId()));
		properties.add(new Property("nemaki:users", group.getUsers()));
		properties.add(new Property("nemaki:groups", group.getGroups()));
		groupItem.setSubTypeProperties(properties);

		// change token: skip
		// acl: skip(empty)
		groupItem.setAcl(new Acl());

		// userId (INTENTIONALLY DUPLICATE of nemaki:userId for CouchDB view)
		groupItem.setGroupId(group.getGroupId());

		return groupItem;
	}


}
