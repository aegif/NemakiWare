# encoding: utf-8
class Group < ActiveModelBase
  attr_accessor :id, :name, :users, :groups
  
  #TODO validation
  validation = VALIDATION['group']
  
   if validation['id']['required']
    validates :id, :presence => true
  end
  
  if validation['name']['required']
    validates :name, :presence => true
  end
end