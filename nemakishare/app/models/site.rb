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
#     linzhixing(https://github.com/linzhixing) - initial API and implementation
# ******************************************************************************
class Site  < ActiveModelBase
  
  attr_accessor :id, :name
  def create()
    
     #sitesフォルダはある前提
     #TODO idをなんとか取得する
     
    sites_id = Site.get_sites_id
    
    #TODO もっと簡単に一行でNode.createできるようにする
    #sitesフォルダ下にsite_nameフォルダを作成
    site = Node.new
    site.name = @name
    site.parent_id = sites_id
    site_obj = site.create('cmis:folder')
    
    #site_nameフォルダ下にdocRootフォルダを作成
    docRoot = Node.new
    docRoot.name = 'docRoot'
    docRoot.parent_id = site_obj.attributes['cmis:objectId']
    docRoot.create('cmis:folder')
  end
  
  def self.list
    #TODO mode validation
    q = "SELECT * FROM cmis:folder WHERE cmis:parentId = '" + self.get_sites_id + "'"
    sites = Node.search(q)
  end
  
  def self.get_sites_id
    root_folder = Node.root_folder
    root_id = root_folder.id
    
    query_string = "SELECT * FROM cmis:folder WHERE cmis:name = 'sites' AND cmis:parentId = '" + root_id + "'"
    sites_obj = Node.search(query_string).first
    if sites_obj == nil
      return nil
    end
    return sites_obj.id
  end
  
  
end