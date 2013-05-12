module ActiveCMIS
  module Internal
    # @private
    module Utils
      # @private
      def self.escape_url_parameter(parameter)
        control = "\x00-\x1F\x7F"
        space   = " "
        delims  = "<>#%\""
        unwise  = '{}|\\\\^\[\]`'
        query   = ";/?:@&=+,$"
        escape(parameter, /[#{control+space+delims+unwise+query}]/o)
      end

      # Given an url (string or URI) returns that url with the given parameters appended
      #
      # This method does not perform any encoding on the paramter or key values.
      # This method does not check the existing parameters for duplication in keys
      # @private
      def self.append_parameters(uri, parameters)
        uri       = case uri
                    when String; string = true; URI.parse(uri)
                    when URI;    uri.dup
                    end
        uri.query = [uri.query, *parameters.map {|key, value| "#{key}=#{value}"} ].compact.join "&"
        if string
          uri.to_s
        else
          uri
        end
      end

      # FIXME?? percent_encode and escape_url_parameter serve nearly the same purpose, replace one?
      # @private
      def self.percent_encode(string)
        escape(string, /[^#{URI::PATTERN::UNRESERVED}]/o)
      end

      def self.escape(string, pattern)
        if defined?(URI::Parser)
          parser = URI::Parser.new
          parser.escape(string, pattern)
        else
          URI.escape(string, pattern)
        end
      end

      # Returns id if id is already an object, object_by_id if id is a string, nil otherwise
      # @private
      def self.string_or_id_to_object(repository, id)
        # FIXME: only used in lib/activecmis/relationship.rb, the repository parameter
        # would be unnecessary if included.
        # Should this be a generic method, or should this be moved to the Relationship class?
        # Or should I start including this module in every place that needs it?
        case id
        when String; repository.object_by_id(id)
        when ::ActiveCMIS::Object; id
        end
      end

      # @private
      def self.extract_links(xml, rel, type_main = nil, type_params = {})
        links = xml.xpath("at:link[@rel = '#{rel}']", NS::COMBINED)

        if type_main
          type_main = Regexp.escape(type_main)
          if type_params.empty?
            regex = /#{type_main}/
          else
            parameters = type_params.map {|k,v| "#{Regexp.escape(k)}=#{Regexp.escape(v)}" }.join(";\s*")
            regex = /#{type_main};\s*#{parameters}/
          end
          links = links.select do |node|
             regex === node.attribute("type").to_s
          end
        end

        links.map {|l| l.attribute("href").to_s}
      end
    end
  end
end
