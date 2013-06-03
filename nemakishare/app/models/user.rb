# encoding: utf-8
class User  < ActiveModelBase
  attr_accessor :id, :name, :first_name, :last_name, :email, :password, :new_password
  
  #FIXME Rails default validation can only specify one action by :on param.
  #Validation
  validation = VALIDATION['user']
  
  if validation['id']['required']
    validates :id, :presence => true, :on => :update
  end
  
  if validation['name']['required']
    validates :name, :presence => true, :on => :update
  end
  
  if validation['first_name']['required']
    validates :first_name, :presence => true, :on => :update
  end
  
  if validation['password']['required']
    validates_presence_of :password, :on => :update_password
  end
  
  if validation['password']['length']['min']
    validates_length_of :password, :minimum => validation['password']['length']['min'], :allow_blank => true, :on => :update_password
  end
  
end