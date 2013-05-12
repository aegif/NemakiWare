module ActiveCMIS
  class Document < ActiveCMIS::Object
    # Returns an ActiveCMIS::Rendition to the content stream or nil if there is none
    # @return [Rendition]
    def content_stream
      if content = data.xpath("at:content", NS::COMBINED).first
        if content['src']
          ActiveCMIS::Rendition.new(repository, self, "href" => content['src'], "type" => content["type"])
        else
          if content['type'] =~ /\+xml$/
            content_data = content.to_xml # FIXME: this may not preserve whitespace
          else
            content_data = data.unpack("m*").first
          end
          ActiveCMIS::Rendition.new(repository, self, "data" => content_data, "type" => content["type"])
        end
      elsif content = data.xpath("cra:content", NS::COMBINED).first
        content.children.each do |node|
          next unless node.namespace and node.namespace.href == NS::CMIS_REST
          content_data = node.text if node.name == "base64"
          content_type = node.text if node.name == "mediaType"
        end
        data = content_data.unpack("m*").first
        ActiveCMIS::Rendition.new(repository, self, "data" => content_data, "type" => content_type)
      end
    end
    cache :content_stream

    # Will reload if renditionFilter was not set or cmis:none, but not in other circumstances
    # @return [Array<Rendition>]
    def renditions
      filter = used_parameters["renditionFilter"]
      if filter.nil? || filter == "cmis:none"
        reload
      end

      links = data.xpath("at:link[@rel = 'alternate']", NS::COMBINED)
      links.map do |link|
        ActiveCMIS::Rendition.new(repository, self, link)
      end
    end
    cache :renditions

    # Sets new content to be uploaded, does not alter values you will get from content_stream (for the moment)
    # @param [Hash] options A hash containing exactly one of :file or :data
    # @option options [String] :file The name of a file to upload
    # @option options [#read] :data Data you want to upload (if #length is defined it should give the total length that can be read)
    # @option options [Boolean] :overwrite (true) Whether the contents should be overwritten (ignored in case of checkin)
    # @option options [String] :mime_type
    #
    # @return [void]
    def set_content_stream(options)
      if key.nil?
        if self.class.content_stream_allowed == "notallowed"
          raise Error::StreamNotSupported.new("Documents of this type can't have content")
        end
      else
        updatability = repository.capabilities["ContentStreamUpdatability"]
        if updatability == "none"
          raise Error::NotSupported.new("Content can't be updated in this repository")
        elsif updatability == "pwconly" && !working_copy?
          raise Error::Constraint.new("Content can only be updated for working copies in this repository")
        end
      end
      @updated_contents = options
    end

    # Returns all documents in the version series of this document.
    # Uses self to represent the version of this document
    # @return [Collection<Document>, Array(self)]
    def versions
      link = data.xpath("at:link[@rel = 'version-history']/@href", NS::COMBINED)
      if link = link.first
        Collection.new(repository, link) # Problem: does not in fact use self
      else
        # The document is not versionable
        [self]
      end
    end
    cache :versions

    # Returns self if this is the latest version
    # Note: There will allways be a latest version in a version series
    # @return [Document]
    def latest_version
      link = data.xpath("at:link[@rel = 'current-version']/@href", NS::COMBINED)
      if link.first
        entry = conn.get_atom_entry(link.first.text)
        self_or_new(entry)
      else
        # FIXME: should somehow return the current version even for opencmis
        self
      end
    end

    # Returns self if this is the working copy
    # Returns nil if there is no working copy
    # @return [Document]
    def working_copy
      link = data.xpath("at:link[@rel = 'working-copy']/@href", NS::COMBINED)
      if link.first
        entry = conn.get_atom_entry(link.first.text)
        self_or_new(entry)
      else
        nil
      end
    end

    def latest?
      attributes["cmis:isLatestVersion"]
    end
    def major?
      attributes["cmis:isMajorVersion"]
    end
    def latest_major?
      attributes["cmis:isLatestMajorVersion"]
    end

    def working_copy?
      return false if key.nil?

      # NOTE: This may not be a sufficient condition, but according to the spec it should be
      !data.xpath("at:link[@rel = 'via']", NS::COMBINED).empty?
    end

    # Returns information about the checked out status of this document
    #
    # @return [Hash,nil] Keys are :by for the owner of the PWC and :id for the CMIS ID, both can be unset according to the spec
    def version_series_checked_out
      attributes = self.attributes
      if attributes["cmis:isVersionSeriesCheckedOut"]
        result = {}
        if attributes.has_key? "cmis:versionSeriesCheckedOutBy"
          result[:by] = attributes["cmis:versionSeriesCheckedOutBy"]
        end
        if attributes.has_key? "cmis:versionSeriesCheckedOutId"
          result[:id] = attributes["cmis:versionSeriesCheckedOutId"]
        end
        result
      else
        nil
      end
    end

    # The checkout operation results in a Private Working Copy
    #
    # Most properties should be the same as for the document that was checked out,
    # certain properties may differ such as cmis:objectId and cmis:creationDate.
    #
    # The content stream of the PWC may be identical to that of the document
    # that was checked out, or it may be unset.
    # @return [Document] The checked out version of this document
    def checkout
      body = render_atom_entry(self.class.attributes.reject {|k,v| k != "cmis:objectId"})

      response = conn.post_response(repository.checkedout.url, body)
      if 200 <= response.code.to_i && response.code.to_i < 300
        entry = Nokogiri::XML.parse(response.body, nil, nil, Nokogiri::XML::ParseOptions::STRICT).xpath("/at:entry", NS::COMBINED)
        result = self_or_new(entry)
        if result.working_copy? # Work around a bug in OpenCMIS where result returned is the version checked out not the PWC
          result
        else
          conn.logger.warn "Repository did not return working copy for checkout operation"
          result.working_copy
        end
      else
        raise response.body
      end
    end

    # This action may not be permitted (query allowable_actions to see whether it is permitted)
    # @return [void]
    def cancel_checkout
      if !self.class.versionable
        raise Error::Constraint.new("Object is not versionable, can't cancel checkout")
      elsif working_copy?
        conn.delete(self_link)
      else
        raise Error::InvalidArgument.new("Not a working copy")
      end
    end

    # You can specify whether the new version should be major (defaults to true)
    # You can optionally give a list of attributes that need to be set.
    #
    # This operation exists only for Private Working Copies
    # @return [Document] The final version that results from the checkin
    def checkin(major = true, comment = "", updated_attributes = {})
      if working_copy?
        update(updated_attributes)
        result = self
        updated_aspects([true, major, comment]).each do |hash|
          result = result.send(hash[:message], *hash[:parameters])
        end
        result
      else
        raise "Not a working copy"
      end
    end

    # @return [void]
    def reload
      @updated_contents = nil
      super
    end

    private
    attr_reader :updated_contents

    #aegif-
    def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {}, extension = self.extension)
    #def render_atom_entry(properties = self.class.attributes, attributes = self.attributes, options = {})
    #-aegif
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


    def updated_aspects(checkin = nil)
      if working_copy? && !(checkin || repository.pwc_updatable?)
        raise Error::NotSupported.new("Updating a PWC without checking in is not supported by repository")
      end
      unless working_copy? || checkin.nil?
        raise Error::NotSupported.new("Can't check in when not checked out")
      end

      result = super

      unless checkin || key.nil? || updated_contents.nil?
        # Don't set content_stream separately if it can be done by setting the content during create
        #
        # TODO: For checkin we could try to see if we can save it via puts *before* we checkin,
        # If not checking in we should also try to see if we can actually save it
        result << {:message => :save_content_stream, :parameters => [updated_contents]}
      end

      result
    end

    def self_or_new(entry)
      if entry.nil?
        nil
      elsif entry.xpath("cra:object/c:properties/c:propertyId[@propertyDefinitionId = 'cmis:objectId']/c:value", NS::COMBINED).text == id
        reload
        @data = entry
        self
      else
        ActiveCMIS::Object.from_atom_entry(repository, entry)
      end
    end

    def create_url
      if f = parent_folders.first
        url = f.items.url
        if self.class.versionable # Necessary in OpenCMIS at least
          url
        else
          Internal::Utils.append_parameters(url, "versioningState" => "none")
        end
      else
        raise Error::NotSupported.new("Creating an unfiled document is not supported by CMIS")
        # Can't create documents that are unfiled, CMIS does not support it (note this means exceptions should not actually be NotSupported)
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
        url = Internal::Utils.append_parameters(link, "overwrite" => stream[:overwrite])
      else
        url = link
      end
      conn.put(url, data, "Content-Type" => content_type)
      self
    end
  end
end
