module ActiveCMIS
  # This module defines namespaces that often occur in the REST/Atompub API to CMIS
  module NS
    CMIS_CORE = "http://docs.oasis-open.org/ns/cmis/core/200908/"
    CMIS_REST = "http://docs.oasis-open.org/ns/cmis/restatom/200908/"
    CMIS_MESSAGING = "http://docs.oasis-open.org/ns/cmis/messaging/200908/"
    APP = "http://www.w3.org/2007/app"
    ATOM = "http://www.w3.org/2005/Atom"

    COMBINED = {
      "xmlns:c" => CMIS_CORE,
      "xmlns:cra" => CMIS_REST,
      "xmlns:cm" => CMIS_MESSAGING,
      "xmlns:app" => APP,
      "xmlns:at" => ATOM
    }
  end
end
