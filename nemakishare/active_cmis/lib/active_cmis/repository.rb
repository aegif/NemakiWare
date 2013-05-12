module ActiveCMIS
  class Repository
    # @return [Logger] A logger to which debug output and so on is sent
    attr_reader :logger

    # @return [ActiveCMIS::Server] The server from which the repository was
    #     requested
    attr_reader :server

    # @private
    def initialize(server, connection, logger, initial_data, authentication_info) #:nodoc:
      @server = server
      @conn = connection
      @data = initial_data
      @logger = logger
      method, *params = authentication_info
      if method
        conn.authenticate(method, *params)
      end
    end

    # Use authentication to access the CMIS repository
    # This returns a new Repository object, the existing repository will still
    # use the previous authentication info.
    # If the used authentication info (method, username, password) is the same
    # as for the current Repository object, then self will be returned (unless
    # the server repository cache is cleared first)
    #
    # e.g.: authenticated = repo.authenticate(:basic, "username", "password")
    # @param method [:basic, :ntlm]
    # @param username [String]
    # @param password [String]
    # @return [Repository]
    def authenticate(*authentication_info)
      server.repository(key, authentication_info)
    end

    # The identifier of the repository
    # @return [String]
    def key
      @key ||= data.xpath('cra:repositoryInfo/c:repositoryId', NS::COMBINED).text
    end

    # @return [String]
    def inspect
      "<#ActiveCMIS::Repository #{key}>"
    end

    # The version of the CMIS standard supported by this repository
    # @return [String]
    def cmis_version
      # NOTE: we might want to "version" our xml namespaces depending on the CMIS version
      # If we do that we need to make this method capable of not using the predefined namespaces
      #
      # On the other hand breaking the XML namespace is probably going to break other applications too so the might not change them even when the spec is updated
      @cmis_version ||= data.xpath("cra:repositoryInfo/c:cmisVersionSupported", NS::COMBINED).text
    end

    # The name of the repository, meant for display purposes
    # @return [String]
    def name
      @name ||= data.xpath("cra:repositoryInfo/c:repositoryName", NS::COMBINED).text
    end

    # A description of the repository
    # @return [String]
    def description
      @name ||= data.xpath("cra:repositoryInfo/c:repositoryDescription", NS::COMBINED).text
    end

    # The name of the vendor of this Repository
    # @return [String]
    def vendor
      @vendor ||= data.xpath("cra:repositoryInfo/c:vendorName", NS::COMBINED).text
    end

    # The name of the product behind this Repository
    # @return [String]
    def product_name
      @product_name ||= data.xpath("cra:repositoryInfo/c:productName", NS::COMBINED).text
    end

    # The version of the product behind this Repository
    # @return [String]
    def product_version
      @product_version ||= data.xpath("cra:repositoryInfo/c:productVersion", NS::COMBINED).text
    end

    # Changelog token representing the most recent change to this repository (will
    # represent the most recent change at the time that this ActiveCMIS::Repository
    # was created
    # @return [String]
    def latest_changelog_token
      @changelog_token ||= data.xpath("cra:repositoryInfo/c:latestChangeLogToken", NS::COMBINED).text
    end

    # A URI that points to a web service for this repository. May not be present
    # @return [URI, nil]
    def thin_client_uri
      @thin_client_uri ||= begin
                             string = data.xpath("cra:repositoryInfo/c:thinClientURI", NS::COMBINED).text
                             URI.parse(string) if string && string != ""
                           end
    end

    # Finds the object with a given ID in the repository
    #
    # @param [String] id
    # @param parameters A list of parameters used to get (defaults are what you should use)
    # @return [Object]
    def object_by_id(id, parameters = {"renditionFilter" => "*", "includeAllowableActions" => "true", "includeACL" => true})
      ActiveCMIS::Object.from_parameters(self, parameters.merge("id" => id))
    end

    # @private
    def object_by_id_url(parameters)
      template = pick_template("objectbyid")
      raise "Repository does not define required URI-template 'objectbyid'" unless template
      url = fill_in_template(template, parameters)
    end

    # Finds the type with a given ID in the repository
    # @return [Class]
    def type_by_id(id)
      @type_by_id ||= {}
      if result = @type_by_id[id]
        result
      else
        template = pick_template("typebyid")
        raise "Repository does not define required URI-template 'typebyid'" unless template
        url = fill_in_template(template, "id" => id)

        @type_by_id[id] = Type.create(conn, self, conn.get_atom_entry(url))
      end
    end

    %w[root checkedout unfiled].each do |coll_name|
      define_method coll_name do
        iv = :"@#{coll_name}"
        if instance_variable_defined?(iv)
          instance_variable_get(iv)
        else
          href = data.xpath("app:collection[cra:collectionType[child::text() = '#{coll_name}']]/@href", NS::COMBINED)
          if href.first
            result = Collection.new(self, href.first)
          else
            result = nil
          end
          instance_variable_set(iv, result)
        end
      end
    end

    # A collection containing the CMIS base types supported by this repository
    # @return [Collection<Class>]
    def base_types
      @base_types ||= begin
                        query = "app:collection[cra:collectionType[child::text() = 'types']]/@href"
                        href = data.xpath(query, NS::COMBINED)
                        if href.first
                          url = href.first.text
                          Collection.new(self, url) do |entry|
                            id = entry.xpath("cra:type/c:id", NS::COMBINED).text
                            type_by_id id
                          end
                        else
                          raise "Repository has no types collection, this is strange and wrong"
                        end
                      end
    end

    # An array containing all the types used by this repository
    # @return [<Class>]
    def types
      @types ||= base_types.map do |t|
        t.all_subtypes
      end.flatten
    end

    # Returns a collection with the changes since the given changeLogToken.
    #
    # Completely uncached so use with care
    #
    # @param options Keys can be Symbol or String, all options are optional
    # @option options [String] filter
    # @option options [String] changeLogToken A token indicating which changes you already know about
    # @option options [Integer] maxItems For paging
    # @option options [Boolean] includeAcl
    # @option options [Boolean] includePolicyIds
    # @option options [Boolean] includeProperties
    # @return [Collection]
    def changes(options = {})
      query = "at:link[@rel = '#{Rel[cmis_version][:changes]}']/@href"
      link = data.xpath(query, NS::COMBINED)
      if link = link.first
        link = Internal::Utils.append_parameters(link.to_s, options)
        Collection.new(self, link)
      end
    end

    # Returns a collection with the results of a query (if supported by the repository)
    #
    # @param [#to_s] query_string A query in the CMIS SQL format (unescaped in any way)
    # @param [{Symbol => ::Object}] options Optional configuration for the query
    # @option options [Boolean] :searchAllVersions (false)
    # @option options [Boolean] :includeAllowableActions (false)
    # @option options ["none","source","target","both"] :includeRelationships
    # @option options [String] :renditionFilter ('cmis:none') Possible values: 'cmis:none', '*' (all), comma-separated list of rendition kinds or mimetypes
    # @option options [Integer] :maxItems used for paging
    # @option options [Integer] :skipCount (0) used for paging
    # @return [Collection] A collection with each return value wrapped in a QueryResult
    def query(query_string, options = {})
      raise "This repository does not support queries" if capabilities["Query"] == "none"
      # For the moment we make no difference between metadataonly,fulltextonly,bothseparate and bothcombined
      # Nor do we look at capabilities["Join"] (none, inneronly, innerandouter)

      # For searchAllVersions need to check capabilities["AllVersionsSearchable"]
      # includeRelationships, includeAllowableActions and renditionFilter only work if SELECT only contains attributes from 1 object
      valid_params = ["searchAllVersions", "includeAllowableActions", "includeRelationships", "renditionFilter", "maxItems", "skipCount"]
      invalid_params = options.keys - valid_params
      unless invalid_params.empty?
        raise "Invalid parameters for query: #{invalid_params.join ', '}"
      end

      # FIXME: options are not respected yet by pick_template
      url = pick_template("query", :mimetype => "application/atom+xml", :type => "feed")
      url = fill_in_template(url, options.merge("q" => query_string))
      Collection.new(self, url) do |entry|
        QueryResult.new(entry)
      end
    end

    # The root folder of the repository (as defined in the CMIS standard)
    # @return [Folder]
    def root_folder(reload = false)
      if reload
        @root_folder = object_by_id(data.xpath("cra:repositoryInfo/c:rootFolderId", NS::COMBINED).text)
      else
        @root_folder ||= object_by_id(data.xpath("cra:repositoryInfo/c:rootFolderId", NS::COMBINED).text)
      end
    end

    # Returns an Internal::Connection object, normally you should not use this directly
    # @return [Internal::Connection]
    def conn
      @conn ||= Internal::Connection.new
    end

    # Describes the capabilities of the repository
    # @return [Hash{String => String,Boolean}] The hash keys have capability cut of their name
    def capabilities
      @capabilities ||= begin
                          capa = {}
                          data.xpath("cra:repositoryInfo/c:capabilities/*", NS::COMBINED).map do |node|
                            # FIXME: conversion should be based on knowledge about data model + transforming bool code should not be duplicated
                            capa[node.name.sub("capability", "")] = case t = node.text
                                              when "true", "1"; true
                                              when "false", "0"; false
                                              else t
                                              end
                          end
                          capa
                        end
    end

    # Responds with true if Private Working Copies are updateable, false otherwise
    # (if false the PWC object can only be updated during the checkin)
    def pwc_updatable?
      capabilities["PWCUpdatable"]
    end

    # Responds with true if different versions of the same document can
    # be filed in different folders
    def version_specific_filing?
      capabilities["VersionSpecificFiling"]
    end

    # returns true if ACLs can at least be viewed
    def acls_readable?
      ["manage", "discover"].include? capabilities["ACL"]
    end

    # You should probably not use this directly, use :anonymous instead where a user name is required
    # @return [String]
    def anonymous_user
      if acls_readable?
        data.xpath('cra:repositoryInfo/c:principalAnonymous', NS::COMBINED).text
      end
    end

    # You should probably not use this directly, use :world instead where a user name is required
    # @return [String]
    def world_user
      if acls_readable?
        data.xpath('cra:repositoryInfo/c:principalAnyone', NS::COMBINED).text
      end
    end

    private
    # @private
    attr_reader :data

    def pick_template(name, options = {})
      # FIXME: we can have more than 1 template with differing media types
      #        I'm not sure how to pick the right one in the most generic/portable way though
      #        So for the moment we pick the 1st and hope for the best
      #        Options are ignored for the moment
      data.xpath("n:uritemplate[n:type[child::text() = '#{name}']][1]/n:template", "n" => NS::CMIS_REST).text
    end


    # The type parameter should contain the type of the uri-template
    #
    # The keys of the values hash should be strings,
    # if a key is not in the hash it is presumed to be equal to the empty string
    # The values will be percent-encoded in the fill_in_template method
    # If a given key is not present in the template it will be ignored silently
    #
    # e.g. fill_in_template("objectbyid", "id" => "@root@", "includeACL" => true)
    #      -> 'http://example.org/repo/%40root%40?includeRelationships&includeACL=true'
    def fill_in_template(template, values)
      result = template.gsub(/\{([^}]+)\}/) do |match|
        Internal::Utils.percent_encode(values[$1].to_s)
      end
    end
  end
end
