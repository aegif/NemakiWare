# encoding: utf-8
class Property  < ActiveModelBase
  
  attr_accessor :key, :value, :attributes
  
  def initialize
    @key = nil
    @value = ""
    @attributes = {}
  end

  def to_hash
    hash = Hash.new
    hash[:key] = @key
    hash[:value] = @value
    hash[:attributes] = @attributes
   
    return hash
  end
  
end