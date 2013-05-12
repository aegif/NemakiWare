module ActiveCMIS
  class Folder < ActiveCMIS::Object
    # Returns a collection of all items contained in this folder (1 level deep)
    # @return [Collection<Document,Folder,Policy>]
    def items(option={})
      item_feed = Internal::Utils.extract_links(data, 'down', 'application/atom+xml','type' => 'feed')
      #aegif-
      link = Internal::Utils.append_parameters(item_feed.first, option)
      #-aegif
      raise "No child feed link for folder" if item_feed.empty?
      #aegif-
      #Collection.new(repository, item_feed.first)
      Collection.new(repository, link)
      #-aegif
    end
    cache :items

    def allowed_object_types
      if attributes["cmis:allowedChildObjectTypeIds"].empty?
        repository.types.select { |type| type.fileable }
      else
        # TODO: it is repository specific if subtypes of the allowed types MAY be filed (line 976)
        #
        # There is as far as I can see no other mention of this possibility in the spec, no way to
        # check if this is so for any specific repository. In addition there is in a few places a
        # requirement that an error is thrown if the cmis:objectTypeId is not in the list of allowed
        # values. So for now this is not supported at all.
        attributes["cmis:allowedChildObjectTypeIds"].map { |type_id| repository.type_by_id(type_id) }
      end
    end
    cache :allowed_object_types

    private
    def create_url
      if f = parent_folders.first
        f.items.url
      else
        raise "Not possible to create folder without parent folder"
      end
    end
  end
end
