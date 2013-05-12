module ActiveCMIS
  # A class used to get and set attributes that have a prefix like cmis: in their attribute IDs
  class AttributePrefix
    # @return [Object] The object that the attribute getting and setting will take place on
    attr_reader :object
    # @return [String]
    attr_reader :prefix

    # @private
    def initialize(object, prefix)
      @object = object
      @prefix = prefix
    end

    # For known attributes will act as a getter and setter
    def method_missing(method, *parameters)
      string = method.to_s
      if string[-1] == ?=
        assignment = true
        string = string[0..-2]
      end
      attribute = "#{prefix}:#{string}"
      if object.class.attributes.keys.include? attribute
        if assignment
          object.update(attribute => parameters.first)
        else
          object.attribute(attribute)
        end
      else
        # TODO: perhaps here we should try to look a bit further to see if there is a second :
        super
      end
    end
  end
end
