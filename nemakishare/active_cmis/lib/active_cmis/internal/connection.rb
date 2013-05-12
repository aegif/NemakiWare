
module ActiveCMIS
  module Internal
    class Connection
      # @return [String, nil] The user that is used with the authentication to the server
      attr_reader :user
      # @return [Logger] A logger used to send debug and info messages
      attr_reader :logger

      # @param [Logger] Initialize with a logger of your choice
      def initialize(logger)
        @logger = logger || ActiveCMIS.default_logger
      end

      # Use authentication to access the CMIS repository
      #
      # @param method [Symbol] Currently only :basic is supported
      # @param params The parameters that need to be sent to the Net::HTTP authentication method used, username and password for basic authentication
      # @return [void]
      # @example Basic authentication
      #   repo.authenticate(:basic, "username", "password")
      # @example NTLM authentication
      #   repo.authenticate(:ntlm, "username", "password")
      def authenticate(method, *params)
        case method
        when :basic, "basic"
          @authentication = {:method => :basic_auth, :params => params}
          @user = params.first
        when :ntlm, "ntlm"
          require 'net/ntlm_http'
          @authentication = {:method => :ntlm_auth, :params => params}
          @user = params.first
        else raise "Authentication method not supported"
        end
      end

      # The return value is the unparsed body, unless an error occured
      # If an error occurred, exceptions are thrown (see _ActiveCMIS::Exception
      #
      # @private
      # @return [String] returns the body of the request, unless an error occurs
      def get(url)
        uri = normalize_url(url)

        req = Net::HTTP::Get.new(uri.request_uri)
        handle_request(uri, req)
      end

      # Does not throw errors, returns the full response (includes status code and headers)
      # @private
      # @return [Net::HTTP::Response]
      def get_response(url)
        logger.debug "GET (response) #{url}"
        uri = normalize_url(url)

        req = Net::HTTP::Get.new(uri.request_uri)
        http = authenticate_request(uri, req)
        response = nil
        http.request(req) do |res|
          logger.debug "GOT (#{res.code}) #{url}"
          response = res
        end
        response
      end

      # Returns the parsed body of the result
      # @private
      # @return [Nokogiri::XML::Document]
      def get_xml(url)
        Nokogiri::XML.parse(get(url), nil, nil, Nokogiri::XML::ParseOptions::STRICT)
      end

      # @private
      # @return [Nokogiri::XML::Node]
      def get_atom_entry(url)
        # FIXME: add validation that first child is really an entry
        get_xml(url).child
      end

      # @private
      def put(url, body, headers = {})
        uri = normalize_url(url)

        req = Net::HTTP::Put.new(uri.request_uri)
        headers.each {|k,v| req.add_field k, v}
        assign_body(req, body)
        handle_request(uri, req)
      end

      # @private
      def delete(url, headers = {})
        uri = normalize_url(url)

        req = Net::HTTP::Put.new(uri.request_uri)
        headers.each {|k,v| req.add_field k, v}
        handle_request(uri, req)
      end

      # @private
      def post(url, body, headers = {})
        uri = normalize_url(url)

        req = Net::HTTP::Post.new(uri.request_uri)
        headers.each {|k,v| req.add_field k, v}
        assign_body(req, body)
        handle_request(uri, req)
      end

      # Does not throw errors, returns the full response (includes status code and headers)
      # @private
      def post_response(url, body, headers = {})
        logger.debug "POST (response) #{url}"
        uri = normalize_url(url)

        req = Net::HTTP::Post.new(uri.request_uri)
        headers.each {|k,v| req.add_field k, v}
        assign_body(req, body)

        http = authenticate_request(uri, req)
        response = http.request(req)
        logger.debug "POSTED (#{response.code}) #{url}"
        response
      end

      # @private
      def delete(url)
        uri = normalize_url(url)

        req = Net::HTTP::Delete.new(uri.request_uri)
        handle_request(uri, req)
      end

      private
      def normalize_url(url)
        case url
        when URI; url
        else URI.parse(url.to_s)
        end
      end

      def http_class
        @http_class ||= begin
                          if proxy = ENV['HTTP_PROXY'] || ENV['http_proxy'] then
                            p_uri = URI.parse(proxy)
                            p_user, p_pass = p_uri.user, p_uri.password if p_uri.user
                            Net::HTTP::Proxy(p_uri.host, p_uri.port, p_user, p_pass)
                          else
                            Net::HTTP
                          end
                        end
      end

      def authenticate_request(uri, req)
        http = http_class.new(uri.host, uri.port)
        if uri.scheme == 'https'
          http.use_ssl = true
        end
        if auth = @authentication
          req.send(auth[:method], *auth[:params])
        end
        http
      end

      def assign_body(req, body)
        if body.respond_to? :length
          req.body = body
        else
          req.body_stream = body
          if body.respond_to? :stat
            req["Content-Length"] = body.stat.size.to_s
          elsif req["Content-Size"].nil?
            req["Transfer-Encoding"] = 'chunked'
          end
        end
      end

      def handle_request(uri, req, retry_count = 0)
        logger.debug "#{req.method} #{uri}"
        http = authenticate_request(uri, req)

        status, body, headers = nil
        http.request(req) { |resp|
          status = resp.code.to_i
          body = resp.body
          headers = resp
        }

        logger.debug "RECEIVED #{status}"

        if 200 <= status && status < 300
          return body
        elsif 300 <= status && status < 400
          # follow the redirected a limited number of times
          location = headers["location"]
          logger.debug "REDIRECTING: #{location.inspect}"
          if retry_count <= 3
            new_uri = URI.parse(location)
            if new_uri.relative?
              new_uri = uri + location
            end
            new_req = req.class.new(uri.request_uri)
            handle_request(new_uri, new_req, retry_count + 1)
          else
            raise HTTPError.new("Too many redirects")
          end
        elsif 400 <= status && status < 500
          # Problem: some codes 400, 405, 403, 409, 500 have multiple meanings
          logger.error "Error occurred when handling request:\n#{body}"
          case status
          when 400; raise Error::InvalidArgument.new(body)
            # FIXME: can also be filterNotValid
          when 401; raise HTTPError::AuthenticationError.new(body)
          when 404; raise Error::ObjectNotFound.new(body)
          when 403; raise Error::PermissionDenied.new(body)
            # FIXME: can also be streamNotSupported (?? shouldn't that be 405??)
          when 405; raise Error::NotSupported.new(body)
          else
            raise HTTPError::ClientError.new("A HTTP #{status} error occured, for more precision update the code:\n" + body)
          end
        elsif 500 <= status
          raise HTTPError::ServerError.new("The server encountered an internal error #{status} (this could be a client error though):\n" + body)
        end
      end
    end
  end
end
