# encoding: utf-8

class Aspect  < ActiveModelBase
  
  attr_accessor :id, :properties, :attributes, :implemented
  
  def initialize
    @id = nil
    @properties = []
    @attributes = {}
    @implemented = false
  end
  
end