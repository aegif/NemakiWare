# encoding: utf-8
class Aspect  < ActiveModelBase
  
  attr_accessor :id, :attributes, :properties, :implemented
  
  def initialize
    @id = nil
    @properties = []
    @attributes = {}
    @implemented = false
  end
  
  def to_json
    hash = Hash.new
    hash[:id] = @id
    hash[:attributes] = @attributes
    if !properties.nil? && !properties.empty?
      hash[:properties] = @properties.map { |e| e.to_hash }
    end
    hash[:implemented] = @implemented
    
    return hash.to_json
  end
  
end