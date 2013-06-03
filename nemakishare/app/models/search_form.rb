class SearchForm < ActiveModelBase
  attr_accessor :query, :type
  
  validates_presence_of :query
end
