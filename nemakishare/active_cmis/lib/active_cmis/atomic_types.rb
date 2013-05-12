module ActiveCMIS
  module AtomicType
    class CommonBase
      def cmis2rb(value)
        if value.children.empty? && value.attribute("nil")
          nil
        else
          _cmis2rb(value)
        end
      end
      def rb2cmis(xml, value)
        if value.nil?
          xml.value("xmlns:xsi" => "http://www.w3.org/2001/XMLSchema-instance", "xsi:nil" => "true")
        else
          _rb2cmis(xml, value)
        end
      end
      def can_handle?(value)
        raise NotImplementedError
      end
    end

    class String < CommonBase
      attr_reader :max_length
      def initialize(max_length = nil)
        @max_length = max_length
      end

      def to_s
        "String"
      end

      def _cmis2rb(value)
        value.text
      end
      def _rb2cmis(xml, value)
        v = value.to_s
        if max_length && max_length > 0 && v.length > max_length #xCMIS says maxLength=0
          raise Error::InvalidArgument.new("String representation is longer than maximum (max: #{max_length}, string: \n'\n#{v}\n')\n")
        end
        xml["c"].value v
      end
      def can_handle?(value)
        value.respond_to?(:to_s)
      end

      private :_cmis2rb, :_rb2cmis
    end

    # Qarning: Precision is ignored?
    class Decimal < CommonBase
      attr_reader :precision, :min_value, :max_value
      def initialize(precision = nil, min_value = nil, max_value = nil)
        @precision, @min_value, @max_value = precision, min_value, max_value
      end

      def to_s
        "Decimal"
      end

      def _cmis2rb(value)
        value.text.to_f
      end
      def _rb2cmis(xml, value)
        v = value.to_f
        if (min_value && v < min_value) || (max_value && v > max_value)
          raise Error::InvalidArgument.new("OutOfBounds: #{v} should be between #{min_value} and #{max_value}")
        end
        xml["c"].value("%f" % v)
      end
      def can_handle?(value)
        value.respond_to?(:to_s)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class Integer < CommonBase
      attr_reader :min_value, :max_value
      def initialize(min_value = nil, max_value = nil)
        @min_value, @max_value = min_value, max_value
      end

      def to_s
        "Integer"
      end

      def _cmis2rb(value)
        value.text.to_i
      end
      def _rb2cmis(xml, value)
        v = value.to_int
        if (min_value && v < min_value) || (max_value && v > max_value)
          raise Error::InvalidArgument.new("OutOfBounds: #{v} should be between #{min_value} and #{max_value}")
        end
        xml["c"].value("%i" % v)
      end
      def can_handle?(value)
        value.respond_to?(:to_int)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class DateTime < CommonBase
      attr_reader :resolution

      @instances ||= {}
      def self.new(precision = TIME)
        raise ArgumentError.new("Got precision = #{precision.inspect}") unless [YEAR, DATE, TIME].include? precision.to_s.downcase
        @instances[precision] ||= super
      end

      def to_s
        "DateTime"
      end

      def initialize(resolution)
        @resolution = resolution
      end
      YEAR = "year"
      DATE = "date"
      TIME = "time"

      def _cmis2rb(value)
        case @resolution
        when YEAR, DATE; ::DateTime.parse(value.text).to_date
        when TIME; ::DateTime.parse(value.text)
        end
      end
      def _rb2cmis(xml, value)
        # FIXME: respect resolution, I still have to find out how to do that
        xml["c"].value(value.strftime("%Y-%m-%dT%H:%M:%S%Z"))
      end
      def can_handle?(value)
        value.respond_to?(:strftime)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class Singleton < CommonBase
      def self.new
        @singleton ||= super
      end
    end

    class Boolean < Singleton
      def self.xml_to_bool(value)
        case value
        when "true", "1"; true
        when "false", "0"; false
        else raise ActiveCMIS::Error.new("An invalid boolean was found in CMIS")
        end
      end

      def to_s
        "Boolean"
      end

      def _cmis2rb(value)
        self.class.xml_to_bool(value.text)
      end
      def _rb2cmis(xml, value)
        xml["c"].value( (!!value).to_s )
      end
      def can_handle?(value)
        [true, false].include?(value)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class URI < Singleton
      def to_s
        "Uri"
      end

      def _cmis2rb(value)
        URI.parse(value.text)
      end
      def _rb2cmis(xml, value)
        xml["c"].value( value.to_s )
      end
      def can_handle?(value)
        value.respond_to?(:to_s)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class ID < Singleton
      def to_s
        "Id"
      end

      def _cmis2rb(value)
        value.text
      end
      def _rb2cmis(xml, value)
        case value
        when ::ActiveCMIS::Object; value.id
        else xml["c"].value( value.to_s )
        end
      end
      def can_handle?(value)
        value.class < ::ActiveCMIS::Object || value.respond_to?(:to_s)
      end

      private :_cmis2rb, :_rb2cmis
    end

    class HTML < Singleton
      def to_s
        "Html"
      end

      def _cmis2rb(value)
        value.children
      end
      def _rb2cmis(xml, value)
        # FIXME: Test that this works
        xml["c"].value value
      end
      def can_handle?(value)
        true # FIXME: this is probably incorrect
      end

      private :_cmis2rb, :_rb2cmis
    end

    # Map of XML property elements to the corresponding AtomicTypes
    MAPPING = {
      "propertyString" => ActiveCMIS::AtomicType::String,
      "propertyBoolean" => ActiveCMIS::AtomicType::Boolean,
      "propertyId" => ActiveCMIS::AtomicType::ID,
      "propertyDateTime" => ActiveCMIS::AtomicType::DateTime,
      "propertyInteger" => ActiveCMIS::AtomicType::Integer,
      "propertyDecimal" => ActiveCMIS::AtomicType::Decimal,
      "propertyHtml" => ActiveCMIS::AtomicType::HTML,
      "propertyUri" => ActiveCMIS::AtomicType::URI,
    }

  end
end
