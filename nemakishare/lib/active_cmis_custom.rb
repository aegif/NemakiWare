require 'active_cmis'

module ActiveCMIS
  class Acl

    attr_accessor :extensions

    #override initialize by using around alias
    alias :initialize_original :initialize

    def initialize(repository, document, link, _data = nil)
      initialize_original(repository, document, link, _data)
      @extensions = []
    end

    #define new apply method with argument 
    def apply(only_basic_permissions=false, acl_propagation='repositorydetermined')
    #-aegif
      body = Nokogiri::XML::Builder.new do |xml|
        xml.acl("xmlns" => NS::CMIS_CORE) do
          permissions.each do |permission|
            xml.permission do  
              xml.principal { xml.principalId  convert_principal(permission.principal) }
              xml.direct     permission.direct?  
            
              #TODO need to understand this fix
              #aegif-
              #permission.each do |permit|
              #xml.permission { permit }              
              permission.permissions.each do |permit|
                xml.permission  permit
                #-aegif 
              end
            end
          end

          #aegif-
          if !extensions[0].nil?
                str = extensions[0].build_str(extensions[0])
                eval(str)
              end
          #-aegif
        end

      end
      #aegif-
      #conn.put(self_link("onlyBasicPermissions" => false), body)
      conn.put(self_link("onlyBasicPermissions" => only_basic_permissions, "aclPropagation" => acl_propagation), body.to_xml, "Content-Type" => "application/atom+xml;type=entry")
      #-aegif
      reload
    end    

    # override convert_principal (world added)
    def convert_principal(principal)
      case principal
      when :anonymous
        anonymous_user
      when :world
        #aegif-
        #world
        world_user
        #-aegif
      when anonymous_user
        :anonymous
      when world_user
        :world
      else
        principal
      end
    end

    #########################
    #### added method #######
    #########################    

    attr_reader :data
    def set_extension(namespace, name, attr={}, value)
      root_ext = ActiveCMIS::Extension.new
      root_ext.name = name
      root_ext.attributes = attr
      root_ext.value = value
      @extensions << root_ext
    end

    #ActiveCMIS converts CMIS ANYONE user to :world,
    #but it's not convenient when applying ACL,
    #and more, it could be cause principal conflicts in the server.
    #So, Nemaki decided to invalidate the method.
    def convert_principal(principal)
      principal
    end

  end

  class Document < ActiveCMIS::Object
    # define(override) new render_atom_entry with extension 
    #original definition is def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {})
    def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {}, extension = self.extension)
      super(properties, attributes, options, extension) do |entry|
        if updated_contents && (options[:create] || options[:checkin])
          entry["cra"].content do
            entry["cra"].mediatype(updated_contents[:mime_type] || "application/binary")
            data = updated_contents[:data] || File.read(updated_contents[:file])
            entry["cra"].base64 [data].pack("m")
          end
        end
        if block_given?
          yield(entry)
        end
      end
    end

    def save_content_stream(stream)
      # Should never occur (is private method)
      raise "no content to save" if stream.nil?

      # put to link with rel 'edit-media' if it's there
      # NOTE: cmislib uses the src link of atom:content instead, that might be correct
      edit_links = Internal::Utils.extract_links(data, "edit-media")
      if edit_links.length == 1
        link = edit_links.first
      elsif edit_links.empty?
        raise Error.new("No edit-media link, can't save content")
      else
        raise Error.new("Too many edit-media links, don't know how to choose")
      end
      data = stream[:data] || File.open(stream[:file])
      content_type = stream[:mime_type] || "application/octet-stream"

      if stream.has_key?(:overwrite)
        #aegif-
        #url = Internal::Utils.append_parameters(link, "overwrite" => stream[:overwrite])
        ct = attribute("cmis:changeToken");
        url = Internal::Utils.append_parameters(link, "overwrite" => stream[:overwrite], "changeToken" => Internal::Utils.escape_url_parameter(ct))
        #-aegif
      else
        url = link
      end
      conn.put(url, data, "Content-Type" => content_type)
      self
    end
  end

  class Folder < ActiveCMIS::Object
    def items(option={})
      conn.logger.debug "items is called"
      item_feed = Internal::Utils.extract_links(data, 'down', 'application/atom+xml','type' => 'feed')
      #aegif-
      link = Internal::Utils.append_parameters(item_feed.first, option)
      #-aegif
      raise "No child feed link for folder" if item_feed.empty?
      #aegif-
      #Collection.new(repository, item_feed.first)
      #Collection.new(repository, link)
      PagedCollection.new(repository, link)
      #-aegif
    end    
  end

  # Page scoped Collection
  class PagedCollection < ActiveCMIS::Collection
    def length
      receive_page
      @elements.length
    end
    cache :length

    def sanitize_index(index)
      index < 0 ? length + index : index
    end

  end

  class Object

    # consider self_link does not exist
    def initialize(repository, data, parameters)
      @repository = repository
      @data = data

      @updated_attributes = []

      if @data.nil?
        # Creating a new type from scratch
        raise Error::Constraint.new("This type is not creatable") unless self.class.creatable
        @key = parameters["id"]
        @allowable_actions = {}
        @parent_folders = [] # start unlinked
      else
        @key = parameters["id"] || attribute('cmis:objectId')
        @self_link = data.xpath("at:link[@rel = 'self']/@href", NS::COMBINED).first
        #aegif-
        #PURPOSE: ChangeEvent type atom has no self link if it's deleted
        #@self_link = @self_link.text
        if @self_link
          @self_link = @self_link.text
        end
        #-aegif
      end
      @used_parameters = parameters
      # FIXME: decide? parameters to use?? always same ? or parameter with reload ?
    end

    # introducce secondary type attributes definitions
    def secondary_attributes_definition
      definitions = Hash.new
      ids = attributes['cmis:secondaryObjectTypeIds']
      if !ids.nil?
        ids.each do |id|
          type = @repository.type_by_id(id)
          definitions.merge!(type.attributes)  
        end
      end
      definitions
    end

    # Attribute getter for the CMIS attributes of an object
    # @return [Hash{String => ::Object}] All attributes, the keys are the property ids of the attributes
    def attributes
      #aegif-
      primary_attributes = self.class.attributes
      attrs = {}
      attrs.merge!(primary_attributes)
      ids = secondary_ids
      if !ids.nil? && ids.size != 0
        ids.each do |id|
          secondary = @repository.type_by_id(id)
          attrs = attrs.merge!(secondary.attributes)
        end  
      end
      attrs.inject({}) do |hash, (key, attr)|
      #self.class.attributes.inject({}) do |hash, (key, attr)|
      #-aegif
        if data.nil?
          if key == "cmis:objectTypeId" 
            hash[key] = self.class.id
          else
            hash[key] = nil
          end
        else
          properties = data.xpath("cra:object/c:properties", NS::COMBINED)
          values = attr.extract_property(properties)
          hash[key] = if values.nil? || values.empty?
                        if attr.repeating
                          []
                        else
                          nil
                        end
                      elsif attr.repeating
                        values.map do |value|
                          attr.property_type.cmis2rb(value)
                        end
                      else
                        attr.property_type.cmis2rb(values.first)
                      end
        end
        hash
      end
    end
    cache :attributes

    #aegif-
    def secondary_ids
      if(data == nil)
        return []
      else
        properties = data.xpath("cra:object/c:properties", NS::COMBINED)
        ids = self.class.attributes["cmis:secondaryObjectTypeIds"]
        if(ids == nil)
          return []
        end
        values = ids.extract_property(properties)
        values.map do |value|
          ids.property_type.cmis2rb(value)
        end
      end
    end
    #-aegif

    #aegif-
    def change_event_info
      if !data.nil?
        info = data.xpath("cra:object/c:changeEventInfo", NS::COMBINED)
        if !info.nil?
          hash = {}
          info.children.select do |n|
            hash[n.node_name] = n.text
          end
          hash
        end
      end
    end
    cache :change_event_info
    #-aegif

    #aegif-
    attr_accessor :extension, :updated_extension
    #-aegif

    def update(attributes)
      attributes.each do |key, value|
        #aegif-
        #if (property = self.class.attributes[key.to_s]).nil?
        if (property = self.class.attributes[key.to_s]).nil? && (property = secondary_attributes_definition[key.to_s]).nil? 
        #-aegif
          raise Error::Constraint.new("You are trying to add an unknown attribute (#{key})")
        else
          property.validate_ruby_value(value)
        end
      end
      self.updated_attributes.concat(attributes.keys).uniq!
      self.attributes.merge!(attributes)
    end

    #aegif-
    def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {}, extension = nil)
    #def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {})
    #-aegif
      builder = Nokogiri::XML::Builder.new do |xml|
        xml.entry(NS::COMBINED) do
          xml.parent.namespace = xml.parent.namespace_definitions.detect {|ns| ns.prefix == "at"}
          xml["at"].author do
            xml["at"].name conn.user # FIXME: find reliable way to set author?
          end
          xml["at"].title attributes["cmis:name"]
          if attributes["cmis:objectId"]
            xml["at"].id_ attributes["cmis:objectId"]
          else
            xml["at"].id_ "random-garbage"
          end
          xml["cra"].object do
            xml["c"].properties do
              properties.each do |key, definition|
                definition.render_property(xml, attributes[key])
              end
              #aegif-
              if !extension.nil?
                str = extension.build_str(extension)
                eval(str)
              end
              #-aegif
            end
          end
          yield(xml) if block_given?
        end
      end
      conn.logger.debug builder.to_xml

      #aegif-
      builder.to_xml
      #builder.to_xml
      #-aegif
    end

    def updated_aspects(checkin = nil)
      result = []

      if key.nil?
        result << {:message => :save_new_object, :parameters => []}
        if parent_folders.length > 1
          # We started from 0 folders, we already added the first when creating the document

          # Note: to keep a save operation at least somewhat atomic this might be better done  in save_new_object
          result << {:message => :save_folders, :parameters => [parent_folders]}
        end
      else
        #aegif-
        #if !updated_attributes.empty?
        if !updated_attributes.empty? || !updated_extension.nil?
        #-aegif 
          #aegif-
          result << {:message => :save_attributes, :parameters => [updated_attributes, attributes, checkin, updated_extension]}
          #result << {:message => :save_attributes, :parameters => [updated_attributes, attributes, checkin]}
          #-aegif
        end
        if @original_parent_folders
          result << {:message => :save_folders, :parameters => [parent_folders, checkin && !updated_attributes]}
        end
      end
      if acl && acl.updated? # We need to be able to do this for newly created documents and merge the two
        result << {:message => :save_acl, :parameters => [acl]}
      end

      if result.empty? && checkin
        # NOTE: this needs some thinking through: in particular this may not work well if there would be an updated content stream
        result << {:message => :save_attributes, :parameters => [[], [], checkin]}
      end

      result
    end

    #aegif-
    def attributes_definition
      definitions = Hash.new
      definitions = self.class.attributes.merge(secondary_attributes_definition)
    end
    #-aegif

    #aegif-
    def save_attributes(attributes, values, checkin = nil, updated_extension = nil)
    #def save_attributes(attributes, values, checkin = nil)
    #-aegif
      #aegif-
      #if attributes.empty? && checkin.nil?
      if (attributes.empty? && checkin.nil?) && updated_extension.nil?
      #-aegif
        raise "Error: saving attributes but nothing to do"
      end
      #aegif-
      #properties = self.class.attributes.reject {|key,_| !updated_attributes.include?(key)}
      #properties = self.class.attributes.reject {|key,_| !updated_attributes.include?(key) && key != 'cmis:changeToken'}
      properties = attributes_definition.reject {|key,_| !updated_attributes.include?(key) && key != 'cmis:changeToken'}
      #-aegif

      #aegif-
      body = render_atom_entry(properties, values, {:checkin => checkin}, updated_extension)
      #body = render_atom_entry(properties, values, :checkin => checkin)
      #-aegif
      if checkin.nil?
        parameters = {}
      else
        checkin, major, comment = *checkin
        parameters = {"checkin" => checkin}
        if checkin
          parameters.merge! "major" => !!major, "checkinComment" => Internal::Utils.escape_url_parameter(comment)

          if properties.empty?
            # The standard specifies that we can have an empty body here, that does not seem to be true for OpenCMIS
            # body = ""
          end
        end
      end

      # NOTE: Spec says Entity Tag should be used for changeTokens, that does not seem to work
      if ct = attribute("cmis:changeToken")
        parameters.merge! "changeToken" => Internal::Utils.escape_url_parameter(ct)
      end

      uri = self_link(parameters)
      response = conn.put(uri, body, "Content-Type" => "application/atom+xml;type=entry")

      data = Nokogiri::XML.parse(response, nil, nil, Nokogiri::XML::ParseOptions::STRICT).xpath("at:entry", NS::COMBINED)
      if data.xpath("cra:object/c:properties/c:propertyId[@propertyDefinitionId = 'cmis:objectId']/c:value", NS::COMBINED).text == id
        reload
        @data = data
        self
      else
        reload # Updated attributes should be forgotten here
        ActiveCMIS::Object.from_atom_entry(repository, data)
      end
    end

    #aegif-
    # @private
    class << self
      def from_parameters_by_path(repository, parameters)
        url = repository.object_by_path_url(parameters)
        data = repository.conn.get_atom_entry(url)
        from_atom_entry(repository, data, parameters)
      end
      #-aegif    
    end

    #########################
    #### added method #######
    #########################
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

  class Repository
    #aegif-
    def object_by_path(path, parameters = {"renditionFilter" => "*", "includeAllowableActions" => "true", "includeACL" => true})
      ActiveCMIS::Object.from_parameters_by_path(self, parameters.merge("path" => path))
    end

    # @private
    def object_by_path_url(parameters)
      template = pick_template("objectbypath")
      raise "Repository does not define required URI-template 'objectbypath'" unless template
      url = fill_in_template(template, parameters)
    end
    #-aegif
  end

  #Introduce Secondary Type Class
  class Secondary < ActiveCMIS::Object    
  end

  #Introuce Item Type Class
  class Item < ActiveCMIS::Object
  end

  class Server
    def clear_repositories
      @cached_repositories = {}
      #aegif-
      @repository_info = conn.get_xml(endpoint)
      #-aegif-
    end
  end

  #introduce secondary type
  module Type
    def self.create(param_conn, repository, klass_data)
      parent_id = klass_data.xpath("cra:type/c:parentId/text()", NS::COMBINED)
      superclass = if parent = parent_id.first
                     repository.type_by_id(parent.to_s)
                   else
                     base_type_id = klass_data.xpath("cra:type/c:baseId", NS::COMBINED).text
                     case base_type_id
                     when "cmis:document"
                       Document
                     when "cmis:folder"
                       Folder
                     when "cmis:relationship"
                       Relationship
                     when "cmis:policy"
                       Policy
                     #aegif-
                     when "cmis:item"
                       Item
                     when "cmis:secondary"
                       Secondary
                       #Document
                     #-aegif
                     else
                       raise ActiveCMIS::Error.new("Type #{klass_data.xpath("cra:type/c:id", NS::COMBINED).text} without supertype, and not actually a valid base_type (#{base_type_id.inspect})\n" + klass_data.to_s)
                     end
                   end

      klass = ::Class.new(superclass) do
        extend ActiveCMIS::Type::ClassMethods
        include ActiveCMIS::Type::InstanceMethods

        @repository = repository
        @conn = param_conn
        @data = klass_data
        @self_link = klass_data.xpath("at:link[@rel = 'self']/@href", NS::COMBINED).text

      end
      klass
    end    
  end

  #override CMIS version
  module Rel
    def self.[](version)
      if version == '1.1'
        prefix = "http://docs.oasis-open.org/ns/cmis/link/200908/"
        {
          :allowableactions => "#{prefix}allowableactions",
          :acl => "#{prefix}acl",
          :relationships => "#{prefix}relationships",
          :changes => "#{prefix}changes",
        }
      else
        raise ActiveCMIS::Error.new("ActiveCMIS only works with CMIS 1.1, requested version was #{version}")
      end
    end
  end

  #####################################################################
  ### added classes                                                 ###
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
        str += "{xml.text " + "'" + value.to_s + "'}"
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

  #######
  class Repository
    attr_accessor :data
  end

end
