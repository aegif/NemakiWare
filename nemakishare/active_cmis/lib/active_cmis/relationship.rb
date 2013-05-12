module ActiveCMIS
  class Relationship < ::ActiveCMIS::Object
    # @return [Object]
    def source
      Internal::Utils.string_or_id_to_object(repository, attribute("cmis:sourceId"))
    end
    cache :source

    # @return [Object]
    def target
      Internal::Utils.string_or_id_to_object(repository, attribute("cmis:targetId"))
    end
    cache :target

    # Remove the relationship
    # @return [void]
    def delete
      conn.delete(self_link)
    end

    # @see Object#update
    # @param (see ActiveCMIS::Object#update)
    # @return [void]
    def update(updates = {})
      super
      # Potentially necessary if repositories support it
      # Probably not though

      # Note: we use remove_instance_variable because of the way I implemented the caching
      if updates["cmis:sourceId"] && instance_variable_defined?("@source")
        remove_instance_variable "@source"
      end
      if updates["cmis:targetId"] && instance_variable_defined?("@target")
        remove_instance_variable "@target"
      end
    end

    # Return [], a relationship is not fileable
    # @return [Array()]
    def parent_folders
      []
    end

    private
    def create_url
      source.source_relations.url
    end
  end
end
