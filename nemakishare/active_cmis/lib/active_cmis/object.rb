module ActiveCMIS
  class Object
    include Internal::Caching

    # The repository that contains this object
    # @return [Repository]
    attr_reader :repository

    # The cmis:objectId of the object, or nil if the document does not yet exist in the repository
    # @return [String,nil]
    attr_reader :key
    alias id key

    # Creates a representation of an CMIS Object in the repository
    #
    # Not meant for direct use, use {Repository#object_by_id} instead. To create a new object use the new method on the type that you want the new object to have.
    #
    # @param [Repository] repository The repository this object belongs to
    # @param [Nokogiri::XML::Node,nil] data The preparsed XML Atom Entry or nil if the object does not yet exist
    # @param [Hash] parameters A list of parameters used to get the Atom Entry
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

    # Via method missing attribute accessors and setters are provided for the CMIS attributes of an object.
    # If attributes have a colon in their name you can access them by changing the colon in a dot
    #
    # @example Set an attribute named DateTimePropMV
    #   my_object.DateTimePropMV = Time.now #=> "Wed Apr 07 14:34:19 0200 2010"
    # @example Read the attribute named DateTimePropMV
    #   my_object.DateTimePropMV #=> "Wed Apr 07 14:34:19 0200 2010"
    # @example Get the cmis:name of an object
    #   my_object.cmis.name #=> "My object 25"
    def method_missing(method, *parameters)
      string = method.to_s
      if string[-1] == ?=
        assignment = true
        string = string[0..-2]
      end
      if attributes.keys.include? string
        if assignment
          update(string => parameters.first)
        else
          attribute(string)
        end
      elsif self.class.attribute_prefixes.include? string
        if assignment
          raise NotImplementedError.new("Mass assignment not yet supported to prefix")
        else
          @attribute_prefix ||= {}
          @attribute_prefix[method] ||= AttributePrefix.new(self, string)
        end
      else
        super
      end
    end

    # @return [String]
    def inspect
      "#<#{self.class.inspect} @key=#{key}>"
    end

    # Shorthand for the cmis:name of an object
    # @return [String]
    def name
      attribute('cmis:name')
    end
    cache :name

    # A list of all attributes that have changed locally
    # @return [Array<String>]
    attr_reader :updated_attributes

    # Attribute getter for the CMIS attributes of an object
    # @param [String] name The property id of the attribute
    def attribute(name)
      attributes[name]
    end

    # Attribute getter for the CMIS attributes of an object
    # @return [Hash{String => ::Object}] All attributes, the keys are the property ids of the attributes
    def attributes
      self.class.attributes.inject({}) do |hash, (key, attr)|
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

    # Attribute setter for all CMIS attributes. This only updates this copy of the object.
    # Use save to make these changes permanent and visible in the repositorhy.
    # (use {#reload} after save on other instances of this document to reflect these changes)
    #
    # @param [{String => ::Object}] attributes A hash with new values for selected attributes
    # @raise [Error::Constraint] if a readonly attribute is set
    # @raise if a value can't be converted to the necessary type or falls outside the constraints
    # @return [{String => ::Object}] The updated attributes hash
    def update(attributes)
      attributes.each do |key, value|
        if (property = self.class.attributes[key.to_s]).nil?
          raise Error::Constraint.new("You are trying to add an unknown attribute (#{key})")
        else
          property.validate_ruby_value(value)
        end
      end
      self.updated_attributes.concat(attributes.keys).uniq!
      self.attributes.merge!(attributes)
    end

    # Saves all changes to the object in the repository.
    #
    # *WARNING*: because of the way CMIS is constructed the save operation is not atomic if updates happen to different aspects of the object
    # (parent folders, attributes, content stream, acl), we can't work around this because CMIS lacks transactions
    # @return [Object]
    def save
      # FIXME: find a way to handle errors?
      # FIXME: what if multiple objects are created in the course of a save operation?
      result = self
      updated_aspects.each do |hash|
        result = result.send(hash[:message], *hash[:parameters])
      end
      result
    end

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
    cache :allowable_actions

    # Returns all relationships where this object is the target
    # @return [Collection]
    def target_relations
      query = "at:link[@rel = '#{Rel[repository.cmis_version][:relationships]}']/@href"
      link = data.xpath(query, NS::COMBINED)
      if link.length == 1
        link = Internal::Utils.append_parameters(link.text, "relationshipDirection" => "target", "includeSubRelationshipTypes" => true)
        Collection.new(repository, link)
      else
        raise "Expected exactly 1 relationships link for #{key}, got #{link.length}, are you sure this is a document/folder?"
      end
    end
    cache :target_relations

    # Returns all relationships where this object is the source
    # @return [Collection]
    def source_relations
      query = "at:link[@rel = '#{Rel[repository.cmis_version][:relationships]}']/@href"
      link = data.xpath(query, NS::COMBINED)
      if link.length == 1
        link = Internal::Utils.append_parameters(link.text, "relationshipDirection" => "source", "includeSubRelationshipTypes" => true)
        Collection.new(repository, link)
      else
        raise "Expected exactly 1 relationships link for #{key}, got #{link.length}, are you sure this is a document/folder?"
      end
    end
    cache :source_relations

    # @return [Acl,nil] The ACL of the document, if there is any at all
    def acl
      if repository.acls_readable? && allowable_actions["GetACL"]
        # FIXME: actual query should perhaps look at CMIS version before deciding which relation is applicable?
        query = "at:link[@rel = '#{Rel[repository.cmis_version][:acl]}']/@href"
        link = data.xpath(query, NS::COMBINED)
        if link.length == 1
          Acl.new(repository, self, link.first.text, data.xpath("cra:object/c:acl", NS::COMBINED))
        else
          raise "Expected exactly 1 acl for #{key}, got #{link.length}"
        end
      end
    end

    # Depending on the repository there can be more than 1 parent folder
    # Always returns [] for relationships, policies may also return []
    #
    # @return [Array<Folder>,Collection] The parent folders in an array or a collection
    def parent_folders
      parent_feed = Internal::Utils.extract_links(data, 'up', 'application/atom+xml','type' => 'feed')
      unless parent_feed.empty?
        Collection.new(repository, parent_feed.first)
      else
        parent_entry = Internal::Utils.extract_links(data, 'up', 'application/atom+xml','type' => 'entry')
        unless parent_entry.empty?
          e = conn.get_atom_entry(parent_entry.first)
          [ActiveCMIS::Object.from_atom_entry(repository, e)]
        else
          []
        end
      end
    end
    cache :parent_folders

    # Files an object in a folder, if the repository supports multi-filing this will be an additional folder, else it will replace the previous folder
    #
    # @param [Folder] folder The (replacement) folder
    # @return [void]
    def file(folder)
      raise Error::Constraint.new("Filing not supported for objects of type: #{self.class.id}") unless self.class.fileable
      @original_parent_folders ||= parent_folders.dup
      if repository.capabilities["MultiFiling"]
        @parent_folders << folder unless @parent_folders.detect {|f| f.id == folder.id }
      else
        @parent_folders = [folder]
      end
    end

    # Removes an object from a given folder or all folders. If the repository does not support unfiling this method throws an error if the document would have no folders left after unfiling.
    #
    # @param [Folder,nil] folder
    # @return [void]
    def unfile(folder = nil)
      # Conundrum: should this throw exception if folder is not actually among parent_folders?
      raise Error::Constraint.new("Filing not supported for objects of type: #{self.class.id}") unless self.class.fileable
      @original_parent_folders ||= parent_folders.dup
      if repository.capabilities["UnFiling"]
        if folder.nil?
          @parent_folders = []
        else
          @parent_folders.delete_if {|f| f.id == folder.id}
        end
      else
        @parent_folders.delete_if {|f| f.id == folder.id}
        if @parent_folders.empty?
          @parent_folders = @original_parent_folders
          @original_parent_folders = nil
          raise Error::NotSupported.new("Unfiling not supported for this repository")
        end
      end
    end

    # Empties the locally cached and updated values, updated data is asked from the server the next time a value is requested.
    # @raise [RuntimeError] if the object is not yet created on the server
    # @return [void]
    def reload
      if @self_link.nil?
        raise "Can't reload unsaved object"
      else
        __reload
        @updated_attributes = []
        @original_parent_folders = nil
      end
    end

    # Tries to delete the object
    # To delete all versions of a Document try #all_versions.delete
    #
    # For policies this may just remove the policy from the policy group
    # of a document, this depends on how you retrieved the policy. Be careful
    def destroy
      conn.delete(self_link)
    end

    private
    # Internal value, not meant for common-day use
    # @private
    # @return [Hash]
    attr_reader :used_parameters

    def self_link(options = {})
      url = @self_link
      if options.empty?
        url
      else
        Internal::Utils.append_parameters(url, options)
      end
      #repository.object_by_id_url(options.merge("id" => id))
    end

    def data
      parameters = {"includeAllowableActions" => true, "renditionFilter" => "*", "includeACL" => true}
      data = conn.get_atom_entry(self_link(parameters))
      @used_parameters = parameters
      data
    end
    cache :data

    def conn
      @repository.conn
    end

    def _allowable_actions
      if actions = data.xpath('cra:object/c:allowableActions', NS::COMBINED).first
        actions
      else
        links = data.xpath("at:link[@rel = '#{Rel[repository.cmis_version][:allowableactions]}']/@href", NS::COMBINED)
        if link = links.first
          conn.get_xml(link.text)
        else
          nil
        end
      end
    end

    # @param properties a hash key/definition pairs of properties to be rendered (defaults to all attributes)
    # @param attributes a hash key/value pairs used to determine the values rendered (defaults to self.attributes)
    # @param options
    # @yield [entry] Optional block to customize the rendered atom entry
    # @yieldparam [Nokogiri::XML::Builder] entry The entry XML builder element on which you can add additional tags (uses the NS::COMBINED namespaces)

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

    # @private
    attr_writer :updated_attributes

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

    def save_new_object
      if self.class.required_attributes.any? {|a, _| attribute(a).nil? }
        raise Error::InvalidArgument.new("Not all required attributes are filled in")
      end

      properties = self.class.attributes.reject do |key, definition|
        # !updated_attributes.include?(key) && !definition.required
        attributes[key].nil? or definition.updatability == "readonly"
      end
      body = render_atom_entry(properties, attributes, :create => true)

      url = create_url
      response = conn.post(create_url, body, "Content-Type" => "application/atom+xml;type=entry")
      # XXX: Currently ignoring Location header in response

      response_data = Nokogiri::XML::parse(response).xpath("at:entry", NS::COMBINED) # Assume that a response indicates success?

      @self_link = response_data.xpath("at:link[@rel = 'self']/@href", NS::COMBINED).first
      @self_link = @self_link.text
      reload
      @key  = attribute("cmis:objectId")

      self
    end

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
      properties = self.class.attributes.reject {|key,_| !updated_attributes.include?(key) && key != 'cmis:changeToken'}
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

    def save_folders(requested_parent_folders, checkin = nil)
      current = parent_folders.to_a
      future  = requested_parent_folders.to_a

      common_folders = future.map {|f| f.id}.select {|id| current.any? {|f| f.id == id } }

      added  = future.select {|f1| current.all? {|f2| f1.id != f2.id } }
      removed = current.select {|f1| future.all? {|f2| f1.id != f2.id } }

      # NOTE: an absent atom:content is important here according to the spec, for the moment I did not suffer from this
      body = render_atom_entry("cmis:objectId" => self.class.attributes["cmis:objectId"])

      # Note: change token does not seem to matter here
      # FIXME: currently we assume the data returned by post is not important, I'm not sure that this is always true
      if added.empty?
        removed.each do |folder|
          url = repository.unfiled.url
          url = Internal::Utils.append_parameters(url, "removeFrom" => Internal::Utils.escape_url_parameter(removed.id))
          conn.post(url, body, "Content-Type" => "application/atom+xml;type=entry")
        end
      elsif removed.empty?
        added.each do |folder|
          conn.post(folder.items.url, body, "Content-Type" => "application/atom+xml;type=entry")
        end
      else
        removed.zip(added) do |r, a|
          url = a.items.url
          url = Internal::Utils.append_parameters(url, "sourceFolderId" => Internal::Utils.escape_url_parameter(r.id))
          conn.post(url, body, "Content-Type" => "application/atom+xml;type=entry")
        end
        if extra = added[removed.length..-1]
          extra.each do |folder|
            conn.post(folder.items.url, body, "Content-Type" => "application/atom+xml;type=entry")
          end
        end
      end

      self
    end

    def save_acl(acl)
      acl.save
      reload
      self
    end

    class << self
      # The repository this type is defined in
      # @return [Repository]
      attr_reader :repository

      # @private
      def from_atom_entry(repository, data, parameters = {})
        query = "cra:object/c:properties/c:propertyId[@propertyDefinitionId = '%s']/c:value"
        type_id = data.xpath(query % "cmis:objectTypeId", NS::COMBINED).text
        klass = repository.type_by_id(type_id)
        if klass
          if klass <= self
            klass.new(repository, data, parameters)
          else
            raise "You tried to do from_atom_entry on a type which is not a supertype of the type of the document you identified"
          end
        else
          raise "The object #{extract_property(data, "String", 'cmis:name')} has an unrecognized type #{type_id}"
        end
      end

      # @private
      def from_parameters(repository, parameters)
        url = repository.object_by_id_url(parameters)
        data = repository.conn.get_atom_entry(url)
        from_atom_entry(repository, data, parameters)
      end

      #aegif-
      # @private
      def from_parameters_by_path(repository, parameters)
        url = repository.object_by_path_url(parameters)
        data = repository.conn.get_atom_entry(url)
        from_atom_entry(repository, data, parameters)
      end
      #-aegif

      # A list of all attributes defined on this object
      # @param [Boolean] inherited Nonfunctional
      # @return [Hash{String => PropertyDefinition}]
      def attributes(inherited = false)
        {}
      end

      # The key of the CMIS Type
      # @return [String]
      # @raise [NotImplementedError] for Object/Folder/Document/Policy/Relationship
      def key
        raise NotImplementedError
      end

    end
  end
end
