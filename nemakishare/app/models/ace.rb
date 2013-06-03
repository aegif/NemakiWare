# encoding: utf-8
class Ace  < ActiveModelBase
   attr_accessor :principal, :permissions, :direct
   
   def to_json
     hash = Hash.new
     hash[:principal] = :principal
     hash[:permissions] = :permissions
     hash[:direct] = :direct
     hash.to_json
   end
end