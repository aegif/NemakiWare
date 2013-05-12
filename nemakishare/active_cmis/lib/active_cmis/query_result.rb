module ActiveCMIS
  # QueryResults are returned when doing a query
  # You can retrieve the values they contain either by the properties ID or by the query name.
  #
  # Since it is possible that a JOIN is being requested it is not guaranteed that either the query name or the object ID are unique.
  # If that's not the case then it is impossible to retrieve one of the two values. It is therefore important to choose unique queryNames
  #  Furthermore, it is not possible to guess which type a query is returning, and therefore it is also not possible to know whether a property is repeating or not, it is possible that a repeating property contains 0 or 1 values, in which case nil or that single value are returned. If multiple values are found for a property then an Array with those properties will be returned
  class QueryResult
    def initialize(atom_entry)
      @atom_entry = atom_entry
      properties = atom_entry.xpath("cra:object/c:properties/c:*", NS::COMBINED)
      @properties_by_id = {}
      @properties_by_query_name = {}
      properties.each do |property|
        type = ActiveCMIS::AtomicType::MAPPING[property.node_name]
        converter = type.new

        values = property.xpath("c:value", NS::COMBINED)
        # FIXME: If attributes are repeating, but have 0-1 value they won't be in array
        if values.length > 1
          value = values.map {|v| converter.cmis2rb(v)}
        elsif !values.empty?
          value = converter.cmis2rb(values)
        else
          value = nil
        end
        @properties_by_id[property["propertyDefinitionId"]] = value
        @properties_by_query_name[property["queryName"]] = value
      end
    end

    def property_by_id(name)
      @properties_by_id[name]
    end

    def property_by_query_name(name)
      @properties_by_query_name[name]
    end
  end
end
