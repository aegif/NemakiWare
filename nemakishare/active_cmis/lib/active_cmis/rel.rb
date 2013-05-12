module ActiveCMIS
  module Rel
    def self.[](version)
      if version == '1.0'
        prefix = "http://docs.oasis-open.org/ns/cmis/link/200908/"
        {
          :allowableactions => "#{prefix}allowableactions",
          :acl => "#{prefix}acl",
          :relationships => "#{prefix}relationships",
          :changes => "#{prefix}changes",
        }
      else
        raise ActiveCMIS::Error.new("ActiveCMIS only works with CMIS 1.0, requested version was #{version}")
      end
    end
  end
end
