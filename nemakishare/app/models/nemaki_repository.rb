# encoding: utf-8

require 'json'
require 'nokogiri'
require 'rest_client'

class NemakiRepository
  def initialize(auth_info_param=nil)
    @auth_info = auth_info_param
    cmis_config = Hash.new
    cmis_config["repo_id"] = "books"
    server = ActiveCMIS::Server.new("http://localhost:8180/Nemaki/atom/")
    nemaki = server.authenticate(:basic, @auth_info[:id], @auth_info[:password])
    @repo = nemaki.repository(cmis_config['repo_id'])
  end

  def find(id)
    #Get data from the repository
    obj = @repo.object_by_id(id)
    #Set cmis properties and acl
    attr = extract_attr(obj.attributes, obj.acl)
    node = Node.new(attr)
    #Set custom properties
    node.aspects = get_aspects(obj)
    return node
  end

  def find2(id)
    obj = @repo.object_by_id(id)
  end

  #TODO NemakiWare側のextension構造を変更する（このメソッドの出力は不変）
  def get_aspects(object)
    #retrieve atom extension data
    data = object.parse_atom_data("cra:object/c:properties", ActiveCMIS::NS::COMBINED)
    aspects_ext = data.xpath("aspects:aspects", "aspects" => "http://www.aegif.jp/Nemaki")

    aspects = []
    aspects_ext.children.each do |aspect_ext|
      aspect = Aspect.new
      #set aspect name
      aspect.id = aspect_ext.attribute('id').text
      #set aspect properties
      aspect_ext.children.each do |property_ext|
        prop = Property.new
        #set property key
        prop.key = property_ext.name.to_s
        #set property value
        prop.value = property_ext.text

        aspect.properties << prop
      end
      #add to the list
      aspects << aspect

    end

    return aspects
  end

  ##Get nemaki aspects from RepositoryInfo
  def get_nemaki_aspects
    #retrieve atom extension data
    aspects_ext = @repo.data.xpath("//aspects:aspects", "aspects" => "http://www.aegif.jp/Nemaki")

    aspects = [] #これにAspectを装填して出力する

    aspects_ext.children.each do |aspect_ext|
      aspect = Aspect.new
      #set aspect name
      aspect.id = aspect_ext.attribute('id').to_s
      #set aspect attributes & properties
      aspect_ext.children.each do |child|
      #set attributes
        if child.name == 'attributes'
          child.children.each do |attribute_ext|
            aspect.attributes[attribute_ext.name] = attribute_ext.text
          end
        #set properties
        elsif child.name == 'properties'
          child.children.each do |property_ext|
            property = Property.new
            property.key = property_ext.attribute('id').to_s
            #set property attributes
            property_ext.children.each do |property_attr_ext|
              property.attributes[property_attr_ext.name] = property_attr_ext.text
            end
            aspect.properties << property
          end
        end
      end

      aspects << aspect

    end

    return aspects
  end

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

  def get_all_versions(node)
    puts "s1"
    puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    obj = @repo.object_by_id(node.id)
    puts "s2"
    puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    if node.is_document?
      all_versions = obj.versions
      puts "s3"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      #TODO Error Handling: skip an error. When an error occurs, all_versions isn't nil
      all_versions_nodes = cast_from_cmis_collection(all_versions)
      puts "s4"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    end
    return all_versions_nodes
  end

  #nodeのリストを返す =>
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

  def create(node_info, parent_id, cmis_type)
    wanted_type = @repo.type_by_id(cmis_type)
    node = wanted_type.new("cmis:name" => node_info[:name])
    if cmis_type == 'cmis:document'
      file = node_info[:file]
      node.set_content_stream({:file => file.original_filename, :data => file.read, :mime_type => file.content_type})
    end
    #TODO parent_idがsetされていない場合を考慮
    parent = @repo.object_by_id(parent_id)
    node.file(parent)
    node.save
  end

  def update(id, update_info, update_aspects=nil)
    obj = @repo.object_by_id(id)
    #TODO Enable cmis:description update and others
    obj.update({"cmis:name" => update_info[:name]})

    update_extension = convert_aspects_to_extension(update_aspects)

    obj.updated_extension = update_extension

    obj.save
  end

  #FIXME change acl by add & remove(now reconstruct acl from scratch)
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

      #Extract ADD/Update ACEs
      #TODO validation for nil
      removePrincipals = extract_remove_principals(local_entries, acl_array)
      #grantAces = extract_grant_aces(local_entries, acl_array)

      #Revoke permissions
      removePrincipals.each do |rp|
        acl.revoke_all_permissions(rp)
      end

      acl_array.each do |ga|
        acl.grant_permission(ga['principal'], ga['permissions'])
      end
    end

    #Set inheritance
    inheritance_flg = (inheritance == "true") ? true : false

    #Set acl data
    data = acl.data
    #TODO Enable to switch "inherited" flag from the client
    exts = acl.set_extension("http://www.aegif.jp/Nemaki", "inherited", {}, inheritance_flg)
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
        property_ext.name = property.key
        property_ext.value = property.value
        aspect_ext.children << property_ext
      end
      root_ext.children << aspect_ext
    end

    return root_ext
  end

  def build_aspects_extension(update_aspects)
    extension = Nokogiri::XML::Node.new

  end

  def get_aspect_by_id(node,id)
    aspects = node.aspects
    aspects.each do |aspect|
      if aspect.id == id
      return aspect
      end
    end
    return nil
  end

  def get_property_by_key(aspect, key)
    properties = aspect.properties
    properties.each do |property|
      if property.key == key
      return property
      end
    end
    return nil
  end

  def upload(id, upload_info)
    obj = @repo.object_by_id(id)
    file = upload_info[:file]
    if !file.nil?
      obj.set_content_stream({:file => file.original_filename, :data => file.read, :mime_type => file.content_type, :overwrite => true})
    end
    obj.save
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

  def get_children(node)

    if node.is_folder?
      obj =  @repo.object_by_id(node.id)
      items = obj.items({"includeAllowableActions" => true})
      @children = cast_from_cmis_collection(items)
    return @children
    end
  end

  def get_stream_data(node)
    obj = @repo.object_by_id(node.id)
    obj.content_stream.get_data[:data]
  end

  def get_mimetype(node)
    obj = @repo.object_by_id(node.id)
    rendition = obj.content_stream
    rendition.format
  end

  def get_root_folder
    root = @repo.root_folder
    root_attr = extract_attr(root.attributes,root.acl)
    Node.new(root_attr)
  end

  def get_parent(node)
    obj = @repo.object_by_id(node.id)
    parent_obj = obj.parent_folders.first
    #FIXME 一時的にattribute不要で取得している
    attr = extract_attr(parent_obj.attributes, nil)
    parent = Node.new(attr)
  end

  def get_site_root
    root_folder = get_root_folder
    root_id = root_folder.id

    query_string = "SELECT * FROM cmis:folder WHERE cmis:name = 'sites' AND cmis:parentId = '" + root_id + "'"
    sites_obj = search(query_string).first
    if sites_obj == nil
      return nil
    end
    return sites_obj
  end

  def get_changes(site_id=nil)
    #hash形のリスト式でchangeログ情報を受け取る
    ch = @repo.changes({'includeAcl' => true, 'maxItems' => 7})

    puts "3.2.1"
    puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }

    #パラメータで指定されたサイトのノードを取得

    #同一バージョン中、最新のeventのみを抽出する
    return ch
  end

  def get_site_list
    #validation
    if get_site_root == nil
      q1 = "SELECT * FROM cmis:folder WHERE cmis:objectId = '/'"
      return search(q1)
    end

    #TODO mode validation
    q = "SELECT * FROM cmis:folder WHERE cmis:parentId = '" + get_site_root.id + "'"
    sites = search(q)
  end

  def get_site(node)
    root = get_root_folder

    #validation
    if get_site_root == nil
    return root
    end

    site_root_id = get_site_root.id

    if node.id == root.id
    return root
    end

    parent = get_parent(node)

    if parent.id == site_root_id
    return node
    elsif parent.id == root.id
    return root
    else
      get_site(parent)
    end
  end

  def create_site(name)
    #sitesフォルダはある前提
    site_root = get_site_root

    #TODO もっと簡単に一行でNode.createできるようにする
    #sitesフォルダ下にsite_nameフォルダを作成
    site_info = Hash.new
    site_info[:name]  = name
    site = create(site_info, site_root.id, 'cmis:folder')

    #site_nameフォルダ下にdocRootフォルダを作成
    doc_root_info = Hash.new
    doc_root_info[:name] = 'docRoot'
    create(doc_root_info, site.id, 'cmis:folder')
  end

  def search(statement)
    result = @repo.query statement, 'includeAllowableActions' => true
    puts "3.1.1"
    puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    nodes = cast_from_cmis_collection(result)
    puts "3.1.2"
    puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    return nodes
  end

  def extract_attr(cmis_attr,cmis_acl=nil)
    attr = {}
    @@cmis_attr_dict.each do |hash|
      attr[hash[:node]] = cmis_attr[hash[:cmis]]
    end

    #Set acl to attr
    if cmis_acl != nil
      attr[:acl] = get_acl(cmis_acl)
    end
    return attr
  end

  #return ACL class from CMIS Object
  def get_acl(cmis_acl)
    acl = Array.new
    cmis_acl.permissions.each do |cmis_ace|
      attr = Hash.new
      attr[:principal] = cmis_ace.principal
      attr[:permissions] = cmis_ace.permissions
      attr[:direct] = cmis_ace.direct?

      ace = Ace.new(attr)
      acl << ace
    end

  end

  #ActiveCMIS::Collection (Object || QueryResultの集合)　を　Nodeのリスト型で返す
  def cast_from_cmis_collection(cmis_collection)
    nodes = []
    attr = {}
    cmis_collection.each_with_index do |item, idx|
      if item.kind_of?(ActiveCMIS::Object)
        attr = extract_attr(item.attributes,item.acl)
      elsif item.kind_of?(ActiveCMIS::QueryResult)
        @@cmis_attr_dict.each do |hash|
          attr[hash[:node]] = item.property_by_id(hash[:cmis])
        end
      end

      n = Node.new(attr)
      #Allowable Actions
      #if item.kind_of?(ActiveCMIS::Object)
      n.allowable_actions = item.allowable_actions
      #end

      nodes.push(n)
    end
    return nodes
  end

  #TODO Nodeクラスに移す
  @@cmis_attr_dict = [
    {:cmis => 'cmis:objectId', :node => :id},
    {:cmis => 'cmis:name', :node => :name},
    {:cmis => 'cmis:path', :node => :path},
    {:cmis => 'cmis:parentId', :node => :parent_id},
    {:cmis => 'cmis:objectTypeId', :node => :type},
    {:cmis => 'cmis:createdBy', :node => :creator},
    {:cmis => 'cmis:creationDate', :node => :created},
    {:cmis => 'cmis:lastModifiedBy', :node => :modifier},
    {:cmis => 'cmis:lastModificationDate', :node => :modified},
    {:cmis => 'cmis:contentStreamMimeType', :node => :mimetype},
    {:cmis => 'cmis:contentStreamLength', :node => :size},
    {:cmis => 'cmis:versionSeriesId', :node => :version_series_id},
    {:cmis => 'cmis:versionLabel', :node => :version_label}

  ]

  ###############################
  ##User / Group Service
  ###############################

  #Return an array of users: [{user1_hash}, {users2_hash},...]
  def get_users
    #FIXME URI shouln't be hard-coded
    users_json = RestClient.get 'http://localhost:8180/Nemaki/rest/user/list'
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

  def get_user(name=nil)
    user_json = RestClient.get 'http://localhost:8180/Nemaki/rest/user/list'
  end

  def search_users(name=nil)
    resource = RestClient::Resource.new('http://localhost:8180/Nemaki/rest/user/search',@auth_info[:id], @auth_info[:password])
    json = resource.get({:params => {:query => name}})
    result = JSON.parse(json)
    if result['status'] == 'success'
      return result['result']
    else
      return Array.new
    end
  end

#Class end
end

#####################################################################
#Add method for Nemaki Aspects to ActiveCMIS framework
#####################################################################
module ActiveCMIS
  class Object
    extend ActiveModel::Naming
    include ActiveModel::Validations
    include ActiveModel::Conversion
    extend ActiveModel::Callbacks
    def persisted? ; false ; end

    def parse_atom_data(query, namespace)
      data = @data
      return data.xpath(query, namespace)
    end

    def resource_url_with_id(resource_name)
      endpoint = @repository.server.endpoint.to_s
      repository_id = @repository.key.to_s
      url = endpoint + repository_id + "/" + resource_name + "?id=" + @attributes['cmis:objectId']
    end

    def delete_descendants
      resource_name = "descendants"
      url = resource_url_with_id(resource_name)
      conn.delete(url)
    end

    def build_update_atom
      atom = render_atom_entry
      return atom
    end

  end

  #####################################################################
  class Acl
    attr_reader :data
    def set_extension(namespace, name, attr={}, value)
      root_ext = ActiveCMIS::Extension.new
      root_ext.name = name
      root_ext.attributes = attr
      root_ext.value = value
      @extensions << root_ext
    end
  end

  #####################################################################
  class QueryResult
    # @return [Hash{String => Boolean,String}] A hash containing all actions allowed on this object for the current user
    def allowable_actions
      actions = {}
      _allowable_actions.children.map do |node|
        actions[node.name.sub("can", "")] = case t = node.text
        when "true", "1"; true
        when "false", "0"; false
        else t
        end
      end
      actions
    end

    def _allowable_actions
      if actions = @atom_entry.xpath('cra:object/c:allowableActions', NS::COMBINED).first
      actions
      else
        links = @atom_entry.xpath("at:link[@rel = '#{Rel[repository.cmis_version][:allowableactions]}']/@href", NS::COMBINED)
        if link = links.first
          conn.get_xml(link.text)
        else
          nil
        end
      end
    end
  end

  #####################################################################
  class Extension
    attr_accessor :name, :namespace, :attributes, :children, :value
    def initialize
      @attributes = {}
      @children = []
    end

    #TODO validation: children XOR value

    def wrap_par(str)
      return "{" + str + "}"
    end

    def build_str(extension)
      children = extension.children
      value = extension.value
      attributes = extension.attributes

      if attributes.empty?
        attr = ""
      else
        attr = "(" + attributes.to_s + ")"
      end

      str = "xml." + extension.name  + attr + " "
      if !children.empty?
        tmp = ""
        children.each do |child|
          tmp += build_str(child) + " \n "
        end
        tmp = wrap_par(tmp)
      str += tmp
      elsif !value.nil?
        str += "{xml.text " + "'" + value + "'}"
      end

      return str
    end

    def to_xml
      str = build_str(self)
      builder = Nokogiri::XML::Builder.new { |xml|
        eval(str)
      }
      return builder.to_xml
    end
  end

  #####################################################################
  class Repository

    attr_reader :data

=begin
  ##もともとActiveCMISにもchangesメソッドはあり、取得方法も同じだが、
  ##Collection型にキャストするところでエラー(changeEventInfoがあるタイプのObjectDataに未対応)のため、
  ##独自メソッドを書いている(いずれPullRequest)
  def changes(options = {})
  query = "at:link[@rel = '#{Rel[cmis_version][:changes]}']/@href"
  link = data.xpath(query, NS::COMBINED)
  if link = link.first
  link = Internal::Utils.append_parameters(link.to_s, options)
  data = conn.get_xml(link)

  query_entry = "//at:entry"
  change_list = Array.new()

  data.xpath(query_entry, NS::COMBINED).each do |entry|
  hash = {}

  properties = entry.xpath("cmisra:object/cmis:properties")
  elements = properties.children.select do |n|
  key = n['propertyDefinitionId']
  if !n.child.nil?
  value = n.child.text
  hash[key] = value
  end
  end

  change_type = entry.xpath("cmisra:object/cmis:changeEventInfo/cmis:changeType").text
  hash[:change_type] = change_type

  change_time = entry.xpath("cmisra:object/cmis:changeEventInfo/cmis:changeTime").text
  hash[:change_time] = change_time

  change_list << hash
  end
  return change_list
  end
  end
=end
  end
end