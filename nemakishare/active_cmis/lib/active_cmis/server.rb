module ActiveCMIS
  # This class is used to manage different CMIS servers.
  class Server
    include Internal::Caching
    # @return [URI] The location of the server
    attr_reader :endpoint
    # @return [Logger] A default logger for derived repositories
    attr_reader :logger

    # @return [Server] Cached by endpoint and logger
    def self.new(endpoint, logger = nil, authentication_info = nil)
      endpoint = case endpoint
                 when URI; endpoint
                 else URI(endpoint.to_s)
                 end
      server = super(endpoint, logger || ActiveCMIS.default_logger, authentication_info)
      endpoints[endpoint.to_s][authentication_info][logger] ||= server
    end

    # @return [{(URI, Logger) => Server}] The cache of known Servers
    def self.endpoints
      @endpoints ||= Hash.new {|h, k| h[k] = Hash.new {|h2, k2| h2[k2] = {}}}
    end

    # @return [String]
    def inspect
      "Server #{@endpoint}"
    end
    # @return [String]
    def to_s
      "Server " + @endpoint.to_s + " : " + repositories.map {|h| h[:name] + "(#{h[:id]})"}.join(", ")
    end

    # A connection needs the URL to a CMIS REST endpoint.
    #
    # It's used to manage all communication with the CMIS Server
    # @param endpoint [URI] The URL where the CMIS AtomPub REST endpoint can be found
    # @param logger [Logger] The logger that will be used to log debug/info messages
    # @param authentication_info [Array?] Optional authentication info to be used when retrieving the data from the AtomPub endpoint
    def initialize(endpoint, logger, authentication_info = nil)
      @endpoint = endpoint
      @logger = logger


      method, *params = authentication_info
      @authentication_info = authentication_info
      if method
        conn.authenticate(method, *params)
      end
    end

    # This returns a new Server object using the specified authentication info
    #
    # @param (see ActiveCMIS::Internal::Connection#authenticate)
    # @see Internal::Connection#authenticate
    # @return [void]
    def authenticate(*authentication_info)
      self.class.new(endpoint, logger, authentication_info)
    end

    # Returns the _Repository identified by the ID
    # Authentication will take place with the optional second paramater, if it
    # is absent and there is server authentcation then the server authentication
    # will be used
    #
    # Cached by the repository_id and, authentcation info. The cache can be reset
    # by calling clear_repositories.
    #
    # @param [String] repository_id
    # @param [Array] authentication_info
    # @return [Repository]
    def repository(repository_id, authentication_info = @authentication_info)
      key = [repository_id, authentication_info]
      cached_repositories[key] ||= uncached_repository(*key)
    end

    def uncached_repository(repository_id, authentication_info)
      path = "/app:service/app:workspace[cra:repositoryInfo/c:repositoryId[child::text() = '#{repository_id}']]"
      repository_data = repository_info.xpath(path, NS::COMBINED)
      if repository_data.empty?
        raise Error::ObjectNotFound.new("The repository #{repository_id} doesn't exist")
      else
        Repository.new(self, conn.dup, logger.dup, repository_data, authentication_info)
      end
    end
    private :uncached_repository

    # Reset cache of Repository objects
    #
    # @return [void]
    def clear_repositories
      @cached_repositories = {}
    end


    # Lists all the available repositories
    #
    # @return [<{:id, :name} => String>]
    def repositories
      repositories = repository_info.xpath("/app:service/app:workspace/cra:repositoryInfo", NS::COMBINED)
      repositories.map {|ri| next {:id => ri.xpath("ns:repositoryId", "ns" => NS::CMIS_CORE).text,
        :name => ri.xpath("ns:repositoryName", "ns" => NS::CMIS_CORE).text }}
    end

    private
    def repository_info
      @repository_info ||= conn.get_xml(endpoint)
    end
    cache :repository_info

    def cached_repositories
      @cached_repositories ||= {}
    end

    def conn
      @conn ||= Internal::Connection.new(logger.dup)
    end
  end
end
