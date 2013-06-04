# encoding: utf-8

# *******************************************************************************
# Copyright (c) 2013 aegif.
# 
# This file is part of NemakiWare.
# 
# NemakiWare is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
# 
# NemakiWare is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
# 
# You should have received a copy of the GNU General Public License along with NemakiWare.
# If not, see <http://www.gnu.org/licenses/>.
# 
# Contributors:
#     linzhixing - initial API and implementation
# ******************************************************************************
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