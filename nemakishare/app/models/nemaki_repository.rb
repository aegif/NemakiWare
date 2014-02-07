# encoding: utf-8

# *******************************************************************************
# Copyright (c) 2013 aegif.
#
# This file is part of NemakiWare.
#
# NemakiWare is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# NemakiWare is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with NemakiWare.
# If not, see <http://www.gnu.org/licenses/>.
#
# Contributors:
#     linzhixing(https://github.com/linzhixing) - initial API and implementation
# ******************************************************************************
require 'json'
require 'nokogiri'
require 'rest_client'
require 'active_cmis'
require 'active_cmis_custom'

class NemakiRepository
  def initialize(auth_info_param=nil, logger=nil)
    @auth_info = auth_info_param
    @server = ActiveCMIS::Server.new(CONFIG['repository']['server_url'], logger).authenticate(:basic, @auth_info[:id], @auth_info[:password])
    @repo = @server.repository(CONFIG['repository']['repository_id'], [:basic, @auth_info[:id], @auth_info[:password] ])
    @logger = logger
  end

  def reset_repository
    @repo = nil
    @server.clear_repositories
    @repo = @server.repository(CONFIG['repository']['repository_id'])
  end

  ########################################
  #Convert from ActiveCMIS to Rails model
  ########################################

  #
  #Property mapping
  #
  @@cmis_attr_dict = [
    {:cmis => 'cmis:objectId', :node => :id},
    {:cmis => 'cmis:name', :node => :name},
    {:cmis => 'cmis:description', :node => :description},
    {:cmis => 'cmis:path', :node => :path},
    {:cmis => 'cmis:parentId', :node => :parent_id},
    {:cmis => 'cmis:objectTypeId', :node => :object_type},
    {:cmis => 'cmis:baseTypeId', :node => :type},
    {:cmis => 'cmis:createdBy', :node => :creator},
    {:cmis => 'cmis:creationDate', :node => :created},
    {:cmis => 'cmis:lastModifiedBy', :node => :modifier},
    {:cmis => 'cmis:lastModificationDate', :node => :modified},
    {:cmis => 'cmis:contentStreamMimeType', :node => :mimetype},
    {:cmis => 'cmis:contentStreamLength', :node => :size},
    {:cmis => 'cmis:versionSeriesId', :node => :version_series_id},
    {:cmis => 'cmis:versionLabel', :node => :version_label}
  ]

  # Extract or get CMIS Object from node
  def get_cmis_object(node)
    if node.inner_cmis_obj != nil then
      node.inner_cmis_obj
    else
      @repo.object_by_id(node.id)
    end
  end

  #
  #Convert to a Node instance
  #
  def cast_from_cmis_object(object)
    #Set cmis properties and acl
    attr = extract_attr(object.attributes, object.acl)
    node = Node.new(attr)
    #Set custom properties
    node.aspects = get_aspects(object)
    #Set allowable actions
    node.allowable_actions = object.allowable_actions
    node.inner_cmis_obj = object

    return node
  end

  #
  #Copy CMIS properties and ACLs to a hash
  #
  def extract_attr(cmis_attr,cmis_acl=nil)
    attr = {}
    @@cmis_attr_dict.each do |hash|
      attr[hash[:node]] = cmis_attr[hash[:cmis]]
    end

    #Set acl to attr
    if cmis_acl != nil
      attr[:acl] = get_acl(cmis_acl)
      #Set ihneritance
      inherited = cmis_acl.data.xpath("inherited:inherited", "inherited" => CONFIG['repository']['acl_inheritance_namespace'])
      if !inherited.nil? && !inherited.text.blank?
        attr[:acl_inherited] = (inherited.text == "true")? true : false
      end
    end
    return attr
  end

  #
  #Convert CMIS ACL to a hash
  #
  def get_acl(cmis_acl)
    acl = Array.new
    cmis_acl.permissions.each do |cmis_ace|
      attr = Hash.new
      attr[:principal] = cmis_ace.principal
      attr[:permissions] = cmis_ace.permissions
      attr[:direct] = (cmis_ace.direct?) ? true : false

      ace = Ace.new(attr)
      acl << ace
    end
    return acl
  end

  #
  #Map ActiveCMIS::Collection to a list of node model
  #NOTE: Collection may includes Object or QueryResult class
  #
  def cast_from_cmis_collection(cmis_collection)
    nodes = []
    attr = {}

    #cmis_collection.each_with_index do |item, idx|
    cmis_collection.each do |item|
      if item.nil?
      next
      end

      if item.kind_of?(ActiveCMIS::Object)
        attr = extract_attr(item.attributes,item.acl)
      elsif item.kind_of?(ActiveCMIS::QueryResult)
        @@cmis_attr_dict.each do |hash|
          attr[hash[:node]] = item.property_by_id(hash[:cmis])
        end
      end
      n = Node.new(attr)
      if item.nil?
      #TODO logging
      next
      end

      n.allowable_actions = item.allowable_actions

      nodes.push(n)
    end
    return nodes
  end

  #
  #Convert CMIS extension of property to a list of Aspect model
  #
  def get_aspects(object)
    types = @repo.types
    secondary_type_ids = object.attributes['cmis:secondaryObjectTypeIds']

    aspects = Array.new

    secondary_type_ids.each do |type_id|
    #Look up from types
      types.each do |type|
        if type.id == type_id

          properties = Array.new
          #TODO attributesがnilの場合に対応
          type.attributes.each do |k,v|
            property = Property.new
            property.key = k
            property.value = object.attributes[k]
            properties << property
          end

          aspect = Aspect.new
        aspect.id = type_id
        aspect.properties = properties

        aspects << aspect

        end

      end
    end

    return aspects
  end

  #
  #Get all the repository supported aspects
  #
  def get_nemaki_aspects
    types = @repo.types

    secondary_types = types.select do |type|
      type.base_id == 'cmis:secondary' && type.id != 'cmis:secondary'
    end

    aspects = Array.new
    secondary_types.each do |type|
      aspect = Aspect.new

      isMulti = false;
      #set attributes
      aspect.id = type.id
      aspect.attributes['localName'] = type.local_name
      aspect.attributes['localNamespace'] = type.local_namespace
      aspect.attributes['displayName'] = type.display_name
      aspect.attributes['queryName'] = type.query_name
      aspect.attributes['description'] = type.description
      aspect.attributes['baseId'] = type.base_id
      aspect.attributes['parentId'] = type.parent_id
      aspect.attributes['creatable'] = type.creatable
      aspect.attributes['fileable'] = type.fileable
      aspect.attributes['queryable'] = type.queryable
      aspect.attributes['fulltextIndexed'] = type.fulltext_indexed
      aspect.attributes['controllablePolicy'] = type.controllable_policy
      aspect.attributes['controllableACL'] = type.controllable_acl
      aspect.attributes['versionable'] = type.versionable
      aspect.attributes['contentStreamAllowed'] = type.content_stream_allowed

      #set properties
      type.attributes.each do |k,v|
        property = Property.new
        property.key = k
        property.attributes['datatype'] = 'string'
        property.attributes['updatability'] = v.updatability
        property.attributes['description'] = v.description
        property.attributes['required'] = (v.required) ? v.required : false
        property.attributes['displayName'] = v.display_name
        property.attributes['cardinality'] = v.cardinality

        aspect.properties << property

        if v.cardinality == 'multi'
          isMulti = true;
        end

      end

      if  !isMulti 
        aspects << aspect
      end

    end
    return aspects
  end

  #
  #Build node's all the possible aspects with values
  #
  def get_aspects_with_attributes(node)
    nemaki_aspects = get_nemaki_aspects
    aspects = node.aspects

    result = []
    nemaki_aspects.each do |nemaki_aspect|
      copied_aspect = Aspect.new
      copied_aspect = nemaki_aspect
      copied_aspect.implemented = false

      #copy aspect
      aspects.each do |aspect|
        if aspect.id == nemaki_aspect.id
          copied_aspect = copy_property_values(aspect, nemaki_aspect)
        copied_aspect.implemented = true
        break
        end
      end

      result << copied_aspect
    end

    return result
  end

  #
  #Copy values between corresponding aspects
  #
  def copy_property_values(aspect, nemaki_aspect)
    result = Aspect.new
    result.id = nemaki_aspect.id
    result.attributes = nemaki_aspect.attributes

    nemaki_props = nemaki_aspect.properties
    props = aspect.properties

    nemaki_props.each do |nemaki_prop|
      property = Property.new
      property = nemaki_prop
      #retrieve the corresponding implemented property
      props.each do |prop|
        if prop.key == nemaki_prop.key
        property.value = prop.value
        end
      end

      result.properties << property
    end

    return result
  end

  #
  #Convert input parameters to the list of Aspect
  #
  def convert_input_to_aspects(node, param_aspects)
    if param_aspects == nil
    return []
    end

    aspects = []  #list of aspect

    param_aspects.each do |param_aspect|
      aspect = Aspect.new
      aspect.id = param_aspect['id']

      #TODO original_aspectがnilの場合
      original_aspect = get_aspect_by_id(node, aspect.id)

      #convert
      param_aspect['properties'].each do |param_property|
        property = Property.new
        property.key = param_property['key']
        property.value = param_property['value']
        aspect.properties << property
      end
      aspects << aspect
    end
    return aspects
  end

  #
  #Extract Aspect instance from node by aspect id
  #
  def get_aspect_by_id(node,id)
    aspects = node.aspects
    aspects.each do |aspect|
      if aspect.id == id
      return aspect
      end
    end
    return nil
  end

  #
  #Convert a list of Aspect to CMIS extension
  #
  def convert_aspects_to_extension(aspects=[])
    root_ext = ActiveCMIS::Extension.new
    root_ext.name="aspects"
    #set aspects
    aspects.each do |aspect|
      aspect_ext = ActiveCMIS::Extension.new
      aspect_ext.name = "aspect"
      aspect_ext.attributes = {"id" => aspect.id}
      #set properties
      aspect.properties.each do |property|
        property_ext = ActiveCMIS::Extension.new
        property_ext.name = "property"
        property_ext.attributes = {"id" => property.key}
        property_ext.value = property.value
        aspect_ext.children << property_ext
      end
      root_ext.children << aspect_ext
    end

    return root_ext
  end

  ########################################
  #CRUD
  ########################################

  #
  #Get a node instance by cmis:objectId
  #
  def find(id)
    object = @repo.object_by_id(id)
    node = cast_from_cmis_object(object)
  end

  #
  #Get a node instance by cmis:path
  #
  def find_by_path(path)
    object = @repo.object_by_path(path)
    cast_from_cmis_object(object)
  end

  #
  #Get the content stream of the document
  #
  def get_stream_data(node)
    obj = @repo.object_by_id(node.id)
    return obj.content_stream.get_data[:data]
  end

  #
  #Get all version nodes of a node
  #
  def get_all_versions(node)
    #obj = @repo.object_by_id(node.id)
    obj = get_cmis_object(node)
    if node.is_document?
      all_versions = obj.versions
      #TODO Error Handling: skip an error. When an error occurs, all_versions isn't nil
      all_versions_nodes = cast_from_cmis_collection(all_versions)
    end
    return all_versions_nodes
  end

  #
  #Create a CMIS object
  #
  def create(node_info, parent_id, cmis_type)
    wanted_type = @repo.type_by_id(cmis_type)
    node = wanted_type.new({"cmis:name" => node_info[:name]})

    if cmis_type == 'cmis:document'
      file = node_info[:file]
      node.set_content_stream({:file => file.original_filename, :data => file.read, :mime_type => file.content_type})
    end
    #TODO parent_idがsetされていない場合を考慮
    parent = @repo.object_by_id(parent_id)
    node.file(parent)
    node.save
  end

  #
  #Update a CMIS object
  #
  def update(id, update_info, update_aspects=nil)
    obj = @repo.object_by_id(id)
    attr = obj.attributes
    
    update_hash = Hash.new

    if !update_aspects.nil?
      update_hash['cmis:secondaryObjectTypeIds'] = attr['cmis:secondaryObjectTypeIds']
      update_aspects.each do |a|
        if aspect_value_exists?(a)
          if attr['cmis:secondaryObjectTypeIds'].nil? || !attr['cmis:secondaryObjectTypeIds'].include?(a.id)
              if(update_hash['cmis:secondaryObjectTypeIds'].nil?)
                update_hash['cmis:secondaryObjectTypeIds'] = []
              end
              update_hash['cmis:secondaryObjectTypeIds'] << a.id  
          end
        end
      end
    end
    
    if check_property_diff "cmis:name", obj, update_info['name']
      update_hash['cmis:name'] = update_info['name']
    end

    if check_property_diff "cmis:description", obj, update_info['description']
      update_hash['cmis:description'] = update_info['description']
    end
    obj.update(update_hash)

    #Custom Properties
    update_secondary_hash = Hash.new

    if !update_aspects.nil?
      update_aspects.each do |a|
        if aspect_value_exists?(a)
          a.properties.each do |p|
            update_secondary_hash[p.key] = p.value
          end
        end
      end
    end

    #Remove aspects with all values nil
    removed_update_aspects = []
    if update_aspects != nil
      update_aspects.each do |a|
        if aspect_value_exists?(a)
        removed_update_aspects << a
        end
      end
    end

    update_extension = convert_aspects_to_extension(removed_update_aspects)
    obj.updated_extension = update_extension

    obj.update(update_secondary_hash)
    obj.save
  end

  def aspect_value_exists?(aspect)
    properties = aspect.properties
    if properties.nil? || properties.blank?
    return false
    else
      flg = false
      properties.each do |p|
        if !p.value.blank?
        flg = true
        break
        end
      end
    return flg
    end
  end

  #
  #Check whether updated property value differs
  #
  def check_property_diff(cmis_prop_id, cmis_obj, update_value)
    if update_value == nil || cmis_obj.attribute(cmis_prop_id) == update_value
    false
    else
    true
    end
  end

  #
  #
  #FIXME change acl by add & remove(now reconstruct acl from scratch)
  #
  def update_permission(id, acl_param=nil, inheritance="true")
    obj = @repo.object_by_id(id)
    acl = obj.acl

    local_entries = []
    acl.permissions.each do |p|
      if p.direct?
      local_entries << p
      end
    end

    if acl_param
      acl_array = JSON.parse(acl_param)

      #Revoke all permissions and rewrite a new complete ACL
      #(As OpenCMIS AtomPub demands)
      acl.revoke_all_users
      acl_array.each do |ace|
        permissions = ace['permissions']
        granted = permissions.select { |k, v| v == "True"}
        #ActiveCMIS grant_permission take one permission each time
        granted.keys.each do |g|
          acl.grant_permission(ace['principal'], g)
        end
      end
    end

    #Set inheritance
    inheritance_flg = (inheritance == "true") ? true : false

    #Set acl data
    #TODO Enable to switch "inherited" flag from the client
    exts = acl.set_extension(CONFIG['repository']['acl_inheritance_namespace'], "inherited", {}, inheritance_flg)
    xml = exts[0].to_xml
    #Apply
    acl.apply
  end

  def extract_remove_principals(entries, acl_array)
    #Extract Remove ACEs
    removePrincipals = Array.new
    #entry.principalがacl_arrayに存在しなければ、remove
    entries.each do |entry|
      exist = false
      acl_array.each do|ace_hash|
        if entry.principal == ace_hash['principal']
        exist = true
        break
        end
      end
      if !exist
      removePrincipals << entry.principal
      end
    end
    return removePrincipals
  end

  def extract_grant_aces(entries, acl_array)
    #Extract Add/Update ACEs
    grantAces = Array.new
    #ace_hash['principal'] がentriesに存在すれば、update。存在しなければadd。
    acl_array.each do |ace_hash|
      exist = false
      entries.each do |entry|
        if ace_hash['principal'] == entry.principal
        exist = true
        break
        end
      end
      if exist
      grantAces << ace_hash
      end
    end
    return grantAces
  end

  def upload(id, upload_info)
    obj = @repo.object_by_id(id)
    file = upload_info[:file]
    if !file.nil?
      obj.set_content_stream({:file => file.original_filename, :data => file.read, :mime_type => file.content_type, :overwrite => true})
      obj.save
    end
  end

  def delete(id)
    obj = @repo.object_by_id(id)
    parent_id = obj.parent_folders.first.attributes['cmis:objectId']

    is_folder = (obj.attributes['cmis:objectTypeId'] == 'cmis:folder')
    if is_folder
    obj.delete_descendants
    else
    obj.destroy
    end

    return parent_id
  end

  ########################################
  #Navigation
  ########################################
  def get_root_folder
    root = @repo.root_folder
    cast_from_cmis_object(root)
  end

  def get_parent(node)
    if !node.is_root?
      find(node.parent_id)
    end
  end

  def get_children(parent_node, maxItems=nil, skipCount=0)
    if parent_node.is_folder?
      #obj =  @repo.object_by_id(parent_node.id)
      obj = get_cmis_object(parent_node)  
      items = obj.items({"includeAllowableActions" => true, "maxItems" => maxItems, "skipCount" => skipCount})
      @children = cast_from_cmis_collection(items)
    return @children
    end
  end

  def get_breadcrumbs(node, breadcrumbs=[])
    root = get_root_folder

    breadcrumbs.unshift(node)

    if node.id == root.id
    return breadcrumbs
    end

    parent = get_parent(node)
    if parent.id == root.id
    return breadcrumbs.unshift(root)
    else
      get_breadcrumbs(get_parent(node), breadcrumbs)
    end

  end

  ########################################
  #Change log
  ########################################

  #
  #Retrieve change log from CMIS server and Cache them into local DB
  #
  def cache_changes(site_id=nil, force=false)
    reset_repository

    #Prepare input parameters
    file_path = "#{Rails.root}/config/latest_change_token.yml"
    yml = YAML.load_file(file_path)
    latest_token = yml[:token]

    params = {'includeAcl' => true, 'includeProperties' => true}

    if !force && !latest_token.blank?
      if @repo.latest_changelog_token == latest_token
      #TODO logging: puts 'do nothing because there is no change'
        return
      else
        params['changeLogToken'] = latest_token.to_i
      end
    end

    #Retrieve the change log from CMIS server
    changes = @repo.changes params

    if changes.empty?
      return
    else

      first = changes.first

      latest = ChangeEvent.where(:objectId => first.attribute('cmis:objectId'),
      :change_type => first.change_event_info['changeType'],
      :change_time => first.change_event_info['changeTime'])

    skip_first = !latest_token.nil? && !latest.nil?

    end
    #Cache the log to local DB
    caches = []
    changes.each_with_index do |change, idx|

      if skip_first
      skip_first = false
      next
      end

      #Set change event info
      cache = ChangeEvent.new(:objectId => change.attribute('cmis:objectId'),
      :change_type => change.change_event_info['changeType'],
      :change_time => change.change_event_info['changeTime'])

      #Get the object
      begin
        object = @repo.object_by_id(change.attribute('cmis:objectId'))
      rescue
      #CASE:Not found
      #Search for object data from either DB(DBに存在していない場合は無視)
        persisted = ChangeEvent.find(:all, :conditions => {:objectId => change.attribute('cmis:objectId')})
        if persisted != nil && !persisted.blank?
        persisted.reverse!
        cache.name = persisted[0].name
        cache.site = persisted[0].site
        cache.user = persisted[0].user
        caches << cache
        end
      next
      end

      cache.name = object.attribute("cmis:name")
      cache.user = (object.attribute("cmis:lastModifiedBy").blank?) ? object.attribute("cmis:createdBy") : object.attribute("cmis:lastModifiedBy")

      if object.attribute("cmis:baseTypeId") == "cmis:document"
        cache.version_series = object.attribute("cmis:versionSeriesId")
        if cache.change_type == 'created'
          if !check_first_version(object)
            cache.change_type = 'version-updated'
          end
        end
      end
      parent = object.parent_folders.first
      if !parent.nil?
        site = get_site_by_node_path(parent.attribute("cmis:path"))
        if site
        cache.site = site.id
        end
      end
      caches << cache
    end

    #Save to DB if there was no error
    if !caches.empty?
      caches.each do |c|
        puts "[ChangeLog Subscription]" + c.objectId + ":" + c.change_type
        c.save
      end
    end

    #Update latestChangeToken
    yml[:token]  = @repo.latest_changelog_token
    yml[:timestamp] = Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    open(file_path, "w") do |f|
      YAML.dump(yml,f)
    end
  end

  def get_site_by_node_path(node_path)
    @site_root = @site_root || get_site_root
    @site_hash = @site_list || Hash.new

    site_name = extract_site_name_from_path(node_path)
    if site_name.nil?
      return nil
    end

    if @site_hash[site_name]
    return @site_hash[site_name]
    else
      site_path = @site_root.path + "/" + site_name
      site = find_by_path(site_path)

      if site
      @site_hash[site_name] = site
      end
    end
  end

  def check_first_version(cmis_document)
    versions = cmis_document.versions
    list = []
    versions.each do |v|
      list << v
    end
    begin
      list.sort! do |a,b|
        Time.parse(a.attribute('cmis:modificationDate')).strftime("%Y%m%d%H%M%S") <=>
        Time.parse(b.attribute('cmis:modificationDate')).strftime("%Y%m%d%H%M%S")
      end
    rescue
    return false
    end

    return cmis_document.attribute('cmis:objectId') == list.first.attribute('cmis:objectId')
  end

  ########################################
  #Site management
  ########################################
  def get_site_root
    find(CONFIG['site']['root_id'])
  end

  def get_site_list
    site_root = find(CONFIG['site']['root_id'])
    if !site_root.nil? && site_root.is_folder?
      get_children(site_root)
    end
  end

  def get_site_name(folder)
    if !folder.is_root? && !folder.is_site_root? && folder.is_folder?
      extract_site_name_from_path(folder.path)
    end
  end

  def extract_site_name_from_path(path)
    /^\/sites\// =~ path
    matched = $'
    if matched.nil?
      nil
    else
    (matched.split('/'))[0]
    end
  end

  def create_site(name)
    #FIXME validation if sites folder doesn't exist
    site_root = get_site_root

    #Create the site folder under "sites" root
    site_info = Hash.new
    site_info[:name]  = name
    site = create(site_info, site_root.id, 'cmis:folder')
  end

  ########################################
  #Search
  ########################################
  def search(statement)
    result = @repo.query statement, 'includeAllowableActions' => true
    nodes = cast_from_cmis_collection(result)
    return nodes
  end

  ########################################
  #CRUD
  ########################################
  #Return an array of users: [{user1_hash}, {users2_hash},...]
  def get_users
    #FIXME URI shouln't be hard-coded
    users_json = RestClient.get CONFIG['repository']['user_rest_url'] + 'list'
    JSON.parse(users_json)['users']
  end

  def get_users_name
    names = Array.new
    users = get_users
    users.each do |user|
      names << user['userName']
    end
    return names
  end

  def convert_user_from_json(user_json)
    user = User.new
    user.id = user_json['userId']
    user.name = user_json['userName']
    user.first_name = user_json['firstName']
    user.last_name = user_json['lastName']
    user.email = user_json['email']
    return user
  end

  def get_user_by_id(id)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'show',@auth_info[:id], @auth_info[:password])
    json = resource[id].get
    JSON.parse(json)
  end

  def delete_user(id)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'delete', @auth_info[:id], @auth_info[:password])
    json = resource[id].delete
    JSON.parse(json)
  end

  def delete_group(id)
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'] +  'delete',@auth_info[:id], @auth_info[:password])
    json = resource[id].delete
    JSON.parse(json)
  end

  def search_users(id)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'search',@auth_info[:id], @auth_info[:password])
    json = resource.get({:params => {:query => id}})
    result = JSON.parse(json)
    if result['status'] == 'success'
      return result['result']
    else
      return Array.new
    end
  end

  def search_groups(id)
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'] + 'search',@auth_info[:id], @auth_info[:password])
    json = resource.get({:params => {:query => id}})
    result = JSON.parse(json)
    if result['status'] == 'success'
      return result['result']
    else
      return Array.new
    end
  end

  def get_group_by_id(id)
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'] + 'show',@auth_info[:id], @auth_info[:password])
    json = resource[id].get
    JSON.parse(json)
  end

  def create_group(group)
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'] + 'create', @auth_info[:id], @auth_info[:password])
    json = resource[group.id].post({"name" => group.name,}, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)
  end

  def create_user(user)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'create', @auth_info[:id], @auth_info[:password])
    json = resource[user.id].post({"name" => user.name, "firstName" => user.first_name, "lastName" => user.last_name, "email" => user.email, "password" => user.password}, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)
  end

  def update_user(user)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'update', @auth_info[:id], @auth_info[:password])
    params = {"name" => user.name, "firstName" => user.first_name, "lastName" => user.last_name, "email" => user.email}
    json = resource[user.id].put(params, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)
  end

  def update_user_password(user)
    resource = RestClient::Resource.new(CONFIG['repository']['user_rest_url'] + 'updatePassword', @auth_info[:id], @auth_info[:password])
    params = {"newPassword" => user.password}
    json = resource[user.id].put(params, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)
  end

  def update_group_members(apiType, group, users=[], groups=[])
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'], @auth_info[:id], @auth_info[:password])
    userjson = users.to_json
    params = {"users" => users.to_json, "groups" => groups.to_json}
    url = resource[apiType][group.id]
    json = resource[apiType][group.id].put(params, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)

  end

  def update_group(group)
    resource = RestClient::Resource.new(CONFIG['repository']['group_rest_url'] + 'update', @auth_info[:id], @auth_info[:password])
    params = {"name" => group.name}
    json = resource[group.id].put(params, :content_type => 'application/x-www-form-urlencoded', :accept => :json)
    JSON.parse(json)
  end

  def is_admin_role(user_id)
    user_id == 'admin'
  end

  def create_types(data)
    #TODO authentication
    resource = RestClient::Resource.new(CONFIG['repository']['type_rest_url'] + "register", @auth_info[:id], @auth_info[:password])
    json = resource.post :data => data
    JSON.parse(json)
  end

  def permissions
    permissions = @repo.permissions
  end

#Class end
end
