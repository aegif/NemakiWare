module ActiveCMIS
  class Rendition
    # @return [Repository]
    attr_reader :repository
    # @return [Numeric,nil] Size of the rendition, may not be given or misleading
    attr_reader :size
    # @return [String,nil]
    attr_reader :rendition_kind
    # @return [String,nil] The format is equal to the mime type, but may be unset or misleading
    attr_reader :format
    # @return [ActiveCMIS::Document] The document to which the rendition belongs
    attr_reader :document

    # @private
    def initialize(repository, document, link)
      @repository = repository
      @document = document

      @rel = link['rel'] == "alternate"
      @rendition_kind = link['renditionKind'] if rendition?
      @format = link['type']
      if link['href']
        @url = URI(link['href'])
      else # For inline content streams
        @data = link['data']
      end
      @size = link['length'] ? link['length'].to_i : nil


      @link = link # FIXME: For debugging purposes only, remove
    end

    # Used to differentiate between rendition and primary content
    def rendition?
      @rel == "alternate"
    end
    # Used to differentiate between rendition and primary content
    def primary?
      @rel.nil?
    end

    # Returns a hash with the name of the file to which was written, the length, and the content type
    #
    # *WARNING*: this loads the complete file in memory and dumps it at once, this should be fixed
    # @param [String] filename Location to store the content.
    # @return [Hash]
    def get_file(file_name)
      response = get_data
      File.open(file_name, "w") {|f| f.syswrite response.delete(:data) }
      response.merge!(:file_name => file_name)
    end

    # Returns a hash with the data of te rendition, the length and the content type
    #
    # *WARNING*: this loads all the data in memory
    # Possible future enhancement could be to allow a block to which data is passed in chunks?r
    # Not sure that is possible with Net::HTTP though.
    # @param [String] filename Location to store the content.
    # @return [Hash]
    def get_data
      if @url
        response = repository.conn.get_response(@url)
        status = response.code.to_i
        if 200 <= status && status < 300
          data = response.body
        elsif 300 <= status && status < 400
          location = response["location"]
          new_url = URI.parse(location)
          new_url = @url + location if new_url.relative?
          @url = new_url
          get_data
        else
          raise HTTPError.new("Problem downloading rendition: status: #{status}, message: #{response.body}")
        end
        content_type = response.content_type
        content_length = response.content_length || response.body.length # In case content encoding is chunked? ??
      else
        data = @data
        content_type = @format
        content_length = @data.length
      end

      {:data => data, :content_type => content_type, :content_length => content_length}
    end
  end
end
