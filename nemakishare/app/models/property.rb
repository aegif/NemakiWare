# encoding: utf-8

class Property  < ActiveModelBase
  
  attr_accessor :key, :value, :attributes
  
  def initialize
    @key = nil
    @value = ""
    @attributes = {}
  end

end