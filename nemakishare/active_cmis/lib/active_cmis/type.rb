module ActiveCMIS
  module Type
    # @private
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

    # Instance Methods on CMIS Types
    module InstanceMethods
      # Creates a new CMIS Object
      # @overload initialize(attributes)
      #   @param [Hash] attributes Attributes for a newly created object of the current type
      #   @return [Object]
      def initialize(rep = nil, data = nil, parameters = {})
        case rep
        when Hash
          attributes = rep
          rep = nil
          data = nil # Force data and parameters to be empty in that case (NOTE: assert may be better?)
          parameters = {}
        when NilClass
        else
          if rep != self.class.repository
            raise "Trying to create element in different repository than type"
          end
        end
        super(rep || self.class.repository, data, parameters)
        unless attributes.nil?
          update(attributes)
        end
      end
    end

    # Class Methods on CMIS Types
    #
    # The following info is also available:
    #  id, local_name, local_namespace, display_name, query_name, description, base_id, parent_id, creatable, fileable,
    #  queryable, fulltext_indexed, controllable_policy, controllable_acl, versionable, content_stream_allowed
    module ClassMethods
      include Internal::Caching

      cached_reader :id, :local_name, :local_namespace, :display_name, :query_name, :description, :base_id,
        :parent_id, :creatable, :fileable, :queryable, :fulltext_indexed, :controllable_policy, :controllable_acl,
        :versionable, :content_stream_allowed

      # @param [Boolean] inherited (false) *Ignored*
      # @return [{String => PropertyDefinition}] A list of propery definitions for all properties on the type
      def attributes(inherited = false)
        load_from_data unless defined?(@attributes)
        if inherited && superclass.respond_to?(:attributes)
          super.merge(@attributes)
        else
          @attributes
        end
      end

      # @return [{String => PropertyDefinition}] A list of all required definitions (includes inherited attributes)
      def required_attributes
        attributes(true).reject {|key, value| !value.required}
      end

      # @return [<String>] A list of prefixes that can be used for adressing attributes (like cmis:)
      def attribute_prefixes
        @prefixes ||= attributes(true).keys.map do |key|
          if key =~ /^([^:]+):/
            $1
          end
        end.compact.uniq
      end

      # @return [void]
      def reload
        remove_instance_variable(:@attributes) if defined? @attributes
        [:attributes] + __reload # Could also do if defined?(super) then super else __reload end, but we don't do anything but remove_instance_variable @attributes in superclasses anyway
      end

      # @return [String]
      def inspect
        "#<#{repository.key}::Class #{key}>"
      end

      # @return [String] The CMIS ID of the type
      def key
        @key ||= data.xpath("cra:type/c:id", NS::COMBINED).text
      end

      # @return [Collection<Class>] All direct subtypes (1 level deep)
      def subtypes
        types_feed = Internal::Utils.extract_links(data, 'down', 'application/atom+xml', 'type' => 'feed')
        raise "No subtypes link for #{id}" if types_feed.empty?

        Collection.new(repository, types_feed.first) do |entry|
          id = entry.xpath("cra:type/c:id", NS::COMBINED).text
          repository.type_by_id id
        end
      end
      cache :subtypes

      # @return [Array<Class>] All subtypes
      def all_subtypes
        subtypes.map do |t|
          t.all_subtypes
        end.flatten << self
      end
      cache :all_subtypes

      private
      # @private
      attr_reader :self_link, :conn
      def data
        @data ||= conn.get_atom_entry(self_link)
      end
      cache :data

      def load_from_data
        @attributes = {}
        data.xpath('cra:type', NS::COMBINED).children.each do |node|
          next unless node.namespace
          next unless node.namespace.href == NS::CMIS_CORE

          case node.node_name
          when "id"
            @id = node.text
          when "localName"
            @local_name = node.text
          when "localNamespace"
            @local_namespace = node.text
          when "displayName"
            @display_name = node.text
          when "queryName"
            @query_name = node.text
          when "description"
            @description = node.text
          when "baseId"
            @base_id = node.text
          when "parentId"
            @parent_id = node.text
          when "creatable"
            @creatable = AtomicType::Boolean.xml_to_bool(node.text)
          when "fileable"
            @fileable = AtomicType::Boolean.xml_to_bool(node.text)
          when "queryable"
            @queryable = AtomicType::Boolean.xml_to_bool(node.text)
          when "fulltextIndexed"
            @fulltext_indexed = AtomicType::Boolean.xml_to_bool(node.text)
          when "controllablePolicy"
            @controllable_policy = AtomicType::Boolean.xml_to_bool(node.text)
          when "controllableACL"
            @controllable_acl = AtomicType::Boolean.xml_to_bool(node.text)
          when "versionable"
            @versionable = AtomicType::Boolean.xml_to_bool(node.text)
          when "contentStreamAllowed"
            # FIXME? this is an enumeration, should perhaps wrap
            @content_stream_allowed = node.text
          when /^property(?:DateTime|String|Html|Id|Boolean|Integer|Decimal)Definition$/
            attr = PropertyDefinition.new(self, node.children)
            @attributes[attr.id] = attr
          end
        end
        if %w(cmis:folder cmis:document).include? @base_id and not @fileable
          repository.logger.warn "The server behaved strange: #{@id}, with basetype #{@base_id} MUST be fileable"
          @fileable = true
        elsif @base_id == "cmis:relationship" and @fileable
          repository.logger.warn "The server behaved strange: #{@id}, with basetype #{@base_id} MUST NOT be fileable"
          @fileable = false
        end
        @attributes.freeze
      end
    end
  end
end
