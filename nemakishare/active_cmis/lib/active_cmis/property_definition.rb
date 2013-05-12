module ActiveCMIS
  class PropertyDefinition
    # @return [String]
    attr_reader :object_type, :id, :local_name, :local_namespace, :query_name,
      :display_name, :description, :cardinality, :property_type, :updatability,
      :default_value
    # @return [Boolean]
    attr_reader :inherited, :required, :queryable, :orderable, :choices, :open_choice

    # @private
    def initialize(object_type, property_definition)
      @object_type = object_type
      @property_definition = property_definition
      params = {}
      property_type = nil
      property_definition.map do |node|
        next unless node.namespace
        next unless node.namespace.href == NS::CMIS_CORE

        # FIXME: add support for "choices"
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
        when "propertyType"
          # Will be post processed, but we need to know all the parameters before we can pick an atomic type
          property_type = node.text
        when "cardinality"
          @cardinality = node.text
        when "updatability"
          @updatability = node.text
        when "inherited"
          @inherited = AtomicType::Boolean.xml_to_bool(node.text)
        when "required"
          @required = AtomicType::Boolean.xml_to_bool(node.text)
        when "queryable"
          @queryable = AtomicType::Boolean.xml_to_bool(node.text)
        when "orderable"
          @orderable = AtomicType::Boolean.xml_to_bool(node.text)
        when "openChoice"
          @open_choice = AtomicType::Boolean.xml_to_bool(node.text)
        when "maxValue", "minValue", "resolution", "precision", "maxLength"
          params[node.node_name] = node.text
        end
      end

      if required and updatability == "readonly"
        logger.warn "The server behaved strange: attribute #{self.inspect} required but readonly, will set required to false"
        @required = false
      end
      if id == "cmis:objectTypeId" and updatability != "oncreate"
        logger.warn "The server behaved strange: cmis:objectTypeId should be updatable on create but #{updatability}"
        @updatability = "oncreate"
      end

      @property_type = case property_type.downcase
      when "string"
        max_length = params["maxLength"] ? params["maxLength"].to_i : nil
        AtomicType::String.new(max_length)
      when "decimal"
        min_value = params["minValue"] ? params["minValue"].to_f : nil
        max_value = params["maxValue"] ? params["maxValue"].to_f : nil
        AtomicType::Decimal.new(params["precision"].to_i, min_value, max_value)
      when "integer"
        min_value = params["minValue"] ? params["minValue"].to_i : nil
        max_value = params["maxValue"] ? params["maxValue"].to_i : nil
        AtomicType::Integer.new(min_value, max_value)
      when "datetime"
        AtomicType::DateTime.new(params["resolution"] || (logger.warn "No resolution for DateTime #{@id}"; "time") )
      when "html"
        AtomicType::HTML.new
      when "id"
        AtomicType::ID.new
      when "boolean"
        AtomicType::Boolean.new
      when "uri"
        AtomicType::URI.new
      else
        raise "Unknown property type #{property_type}"
      end
    end

    # @return [Boolean] Returns true if the attribute can have multiple values
    def repeating
      cardinality == "multi"
    end

    # @return [String]
    def inspect
      "#{object_type.display_name}:#{id} => #{property_type}#{"[]" if repeating}"
    end
    alias to_s inspect

    # @return [String]
    def property_name
      "property#{property_type}"
    end

    # @private
    def render_property(xml, value)
      xml["c"].send(property_name, "propertyDefinitionId" => id) {
        if repeating
          value.each do |v|
            property_type.rb2cmis(xml, v)
          end
        else
          property_type.rb2cmis(xml, value)
        end
      }
    end

    # @private
    # FIXME: should probably also raise error for out of bounds case
    def validate_ruby_value(value)
      if updatability == "readonly" # FIXME: what about oncreate?
        raise "You are trying to update a readonly attribute (#{self})"
      elsif required && value.nil?
        raise "You are trying to unset a required attribute (#{self})"
      elsif repeating != (Array === value)
        raise "You are ignoring the cardinality for an attribute (#{self})"
      else
        if repeating && z = value.detect {|v| !property_type.can_handle?(v)}
          raise "Can't assign attribute with type #{z.class} to attribute with type #{property_type}"
        elsif !repeating && !property_type.can_handle?(value)
          raise "Can't assign attribute with type #{value.class} to attribute with type #{property_type}"
        end
      end
    end

    # @private
    def extract_property(properties)
      elements = properties.children.select do |n|
        n.node_name == property_name &&
          n["propertyDefinitionId"] == id &&
          n.namespace.href == NS::CMIS_CORE
      end
      if elements.empty?
        if required
          logger.warn "The server behaved strange: attribute #{self.inspect} required but not present among properties"
          # raise ActiveCMIS::Error.new("The server behaved strange: attribute #{self.inspect} required but not present among properties")
        end
        if repeating
          []
        else
          nil
        end
      elsif elements.length == 1
        values = elements.first.children.select {|node| node.name == 'value' && node.namespace && node.namespace.href == ActiveCMIS::NS::CMIS_CORE}
        if required && values.empty?
          logger.warn "The server behaved strange: attribute #{self.inspect} required but not present among properties"
          #raise ActiveCMIS::Error.new("The server behaved strange: attribute #{self.inspect} required but no values specified")
        end
        if !repeating && values.length > 1
          logger.warn "The server behaved strange: attribute #{self.inspect} required but not present among properties"
          #raise ActiveCMIS::Error.new("The server behaved strange: attribute #{self.inspect} not repeating but multiple values given")
        end
        values
      else
        raise "Property is not unique"
      end
    end

    # @return [Logger] The logger of the repository
    def logger
      repository.logger
    end
    # @return [Repository] The repository that the CMIS type is defined in
    def repository
      object_type.repository
    end
  end
end
