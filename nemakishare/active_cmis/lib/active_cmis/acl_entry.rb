module ActiveCMIS
  class AclEntry
    # @param [<String>] permissions A list of permissions, valid values depend on the repository
    # @private
    def initialize(principal, permissions, direct)
      @principal = principal.freeze
      @permissions = permissions.freeze
      @permissions.each {|p| p.freeze}
      @direct = direct
    end


    # Normal users are represented with a string, a non-logged in user is known
    # as :anonymous, the principal :world represents the group of all logged in
    # users.
    # @return [String, :world, :anonymous]
    attr_reader :principal
    # @return [<String>] A frozen array of strings with the permissions
    attr_reader :permissions

    # True if this is the direct representation of the ACL from the repositories point of view. This means there are no hidden differences that can't be expressed within the limitations of CMIS
    def direct?
      @direct
    end
  end
end
