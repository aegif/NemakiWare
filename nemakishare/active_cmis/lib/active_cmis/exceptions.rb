module ActiveCMIS
  # The base class for all CMIS exceptions,
  # HTTP communication errors and the like are not catched by this
  class Error < StandardError
    # === Cause
    # One or more of the input parameters to the service method is missing or invalid
    class InvalidArgument < Error; end

    # === Cause
    # The service call has specified an object that does not exist in the Repository
    class ObjectNotFound < Error; end

    # === Cause
    # The service method invoked requires an optional capability not supported by the repository
    class NotSupported < Error; end

    # === Cause
    # The caller of the service method does not have sufficient permissions to perform the operation
    class PermissionDenied < Error; end

    # === Cause
    # Any cause not expressible by another CMIS exception
    class Runtime < Error; end

    # === Intent
    # The operation violates a Repository- or Object-level constraint defined in the CMIS domain model
    #
    # === Methods
    # see the CMIS specification
    class Constraint < Error; end
    # === Intent
    # The operation attempts to set the content stream for a Document
    # that already has a content stream without explicitly specifying the
    # "overwriteFlag" parameter
    #
    # === Methods
    # see the CMIS specification
    class ContentAlreadyExists < Error; end
    # === Intent
    # The property filter or rendition filter input to the operation is not valid
    #
    # === Methods
    # see the CMIS specification
    class FilterNotValid < Error; end
    # === Intent
    # The repository is not able to store the object that the user is creating/updating due to a name constraint violation
    #
    # === Methods
    # see the CMIS specification
    class NameConstraintViolation < Error; end
    # === Intent
    # The repository is not able to store the object that the user is creating/updating due to an internal storage problam
    #
    # === Methods
    # see the CMIS specification
    class Storage < Error; end
    # === Intent
    #
    #
    # === Methods
    # see the CMIS specification
    class StreamNotSupported < Error; end
    # === Intent
    #
    #
    # === Methods
    # see the CMIS specification
    class UpdateConflict < Error; end
    # === Intent
    #
    #
    # === Methods
    # see the CMIS specification
    class Versioning < Error; end
  end

  class HTTPError < StandardError
    class ServerError < HTTPError; end
    class ClientError < HTTPError; end
    class AuthenticationError < HTTPError; end
  end
end
