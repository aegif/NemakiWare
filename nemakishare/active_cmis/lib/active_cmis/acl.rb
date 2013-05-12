module ActiveCMIS
  # ACLs belong to a document and have no identity of their own
  #
  # = Updating:
  # The effect on documents other than the one this ACL belongs to depends
  # on the repository.
  #

  class Acl
    include Internal::Caching

    # @return [Object] The document or object from which we got this ACL
    attr_reader :document
    # @return [Repository]
    attr_reader :repository

    #aegif-
    attr_accessor :extensions
    #-aegif

    # @private
    def initialize(repository, document, link, _data = nil)
      @repository = repository
      @document   = document
      @self_link  = case link
                    when URI; link
                    else URI(link)
                    end
      @data = _data if _data
      #aegif-
      @extensions = []
      #-aegif
    end

    # Returns an array with all Acl entries.
    # @return [Array<AclEntry>]
    def permissions
      data.xpath("c:permission", NS::COMBINED).map do |permit|
        principal      = nil
        permissions    = []
        direct         = false
        permit.children.each do |child|
          next unless child.namespace && child.namespace.href == NS::CMIS_CORE

          case child.name
          when "principal"
            child.children.map do |n|
              next unless n.namespace && n.namespace.href == NS::CMIS_CORE

              if n.name == "principalId" && principal.nil?
                principal = convert_principal(n.text)
              end
            end
          when "permission"
            permissions << child.text
          when "direct"            
            direct = AtomicType::Boolean.xml_to_bool(child.text)
          end
        end
        AclEntry.new(principal, permissions, direct)
      end
    end
    cache :permissions

    # An indicator that the ACL fully describes the permissions for this object.
    # This means that there are no other security constraints.
    def exact?
      @exact ||= begin
                   value = data.xpath("c:exact", NS::COMBINED)
                   if value.empty?
                     false
                   elsif value.length == 1
                     AtomicType::Boolean.xml_to_bool(value.first.text)
                   else
                     raise "Unexpected multiplicity of exactness ACL"
                   end
                 end
    end

    # @param [String, :anonymous, :world] user Can be "cmis:user" to indicate the currently logged in user.
    # For :anonymous and :world you can use both the the active_cmis symbol or the name used by the CMIS repository
    # @param permissions (see ActiveCMIS::AclEntry#initialize)
    # @return [void]
    def grant_permission(user, *permissions)
      principal = convert_principal(user)

      relevant = self.permissions.select {|p| p.principal == principal && p.direct?}
      if relevant = relevant.first
        self.permissions.delete relevant
        permissions.concat(relevant.permissions)
      end

      @updated = true
      self.permissions << AclEntry.new(principal, permissions, true)
    end

    # @param (see ActiveCMIS::Acl#grant_permission)
    # @return [void]
    def revoke_permission(user, *permissions)
      principal = convert_principal(user)

      keep = self.permissions.reject {|p| p.principal == principal && p.permissions.any? {|t| permissions.include? t} }

      relevant = self.permissions.select {|p| p.principal == principal && p.permissions.any? {|t| permissions.include? t} }
      changed  = relevant.map {|p| AclEntry.new(principal, p.permissions - permissions, p.direct?) }

      @updated = true
      @permissions = keep + changed
    end

    # @param user (see ActiveCMIS::Acl#grant_permission)
    # @return [void]
    def revoke_all_permissions(user)
      principal = convert_principal(user)
      @updated = true
      permissions.reject! {|p| p.principal == principal}
    end

    # Needed to actually execute changes on the server, this method is also executed when you save an object with a modified ACL
    # @return [void]
    #aegif-
    #def apply
    def apply(only_basic_permissions=false, acl_propagation='repositorydetermined')
    #-aegif
      body = Nokogiri::XML::Builder.new do |xml|
        xml.acl("xmlns" => NS::CMIS_CORE) do
          permissions.each do |permission|
            xml.permission do  
              xml.principal { xml.principalId  convert_principal(permission.principal) }
              xml.direct     permission.direct?  
            #aegif-
            #permission.each do |permit|
              #xml.permission { permit }              
              permission.permissions.each do |permit|
                xml.permission  permit
             #-aegif 
              end
            end
          end
          #aegif-
          if !extensions[0].nil?
                str = extensions[0].build_str(extensions[0])
                eval(str)
              end
          #-aegif
        end
      end
      #aegif-
      #conn.put(self_link("onlyBasicPermissions" => false), body)
      conn.put(self_link("onlyBasicPermissions" => only_basic_permissions, "aclPropagation" => acl_propagation), body.to_xml, "Content-Type" => "application/atom+xml;type=entry")
      #-aegif
      reload
    end

    # True if there are local changes to the ACL
    def updated?
      @updated
    end

    # @return [void]
    def reload
      @updated = false
      @exact = nil
      __reload
    end

    private
    def self_link(options = {})
      Internal::Utils.append_parameters(@self_link, options)
    end

    def conn
      repository.conn
    end

    def data
      conn.get_xml(self_link).xpath("c:acl", NS::COMBINED)
    end
    cache :data

    def anonymous_user
      repository.anonymous_user
    end
    def world_user
      repository.world_user
    end

    def convert_principal(principal)
      case principal
      when :anonymous
        anonymous_user
      when :world
        world
      when anonymous_user
        :anonymous
      when world_user
        :world
      else
        principal
      end
    end

  end
end
