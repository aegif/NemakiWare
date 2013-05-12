class ActiveModelBase
  extend ActiveModel::Naming
  include ActiveModel::Validations
  include ActiveModel::Conversion
  extend ActiveModel::Callbacks

  def initialize(attributes = {})
    attributes.each do |name, value|
      send("#{name}=", value) rescue nil
    end
  end

  def persisted? ; false ; end
end