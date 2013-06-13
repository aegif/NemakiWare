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
class ArchivesController < ApplicationController
  
  require 'json'
  require 'rest_client'
  
  before_filter :get_auth
  
  def get_auth
    @auth_info = session[:nemaki_auth_info]
  end
  
  def index(status=true)
    
    resource = RestClient::Resource.new(CONFIG['repository']['archive_rest_url'] + 'index', @auth_info[:id], @auth_info[:password])
    json = resource.get()
    result = JSON.parse(json)
    
    @archives = Array.new
    if(result['status'] == 'success')
      if !result['archives'].nil? && !result['archives'].empty?
        result['archives'].each do |a|
          @archives << convert_from_json(a)
        end
      end
      
      if !params[:restore_status].blank?
        if params[:restore_status] == "success"
          flash[:notice] = t('message.archive.restore_success')
        else
          flash[:error] = t('message.archive.restore_failure')
        end  
      end
    else
      flash[:error] = t('message.archive.cannot_display_archive') + ":" + result["error"].to_s
      redirect_to explore_node_path("/")
    end  
  end
  
  def convert_from_json(archive_json)
    hash = {
      :id => archive_json['id'],
      :name => archive_json['name'],
      :type => archive_json['type'],
      :mimetype => archive_json['mimeType'],
      :parent_id => archive_json['parentId'],
      :creator => archive_json['creator'],
      :created => archive_json['created'],
      :is_deleted_with_parent => archive_json['isDeletedWithParent']
    }
    
    Archive.new(hash)
  end
  
  def restore
    #uri = CONFIG['repository']['archive_rest_url'] + "restore/" + params[:id]
    resource = RestClient::Resource.new(CONFIG['repository']['archive_rest_url'], @auth_info[:id], @auth_info[:password])
    json = resource["restore"][params[:id]].put("")
    _status = JSON.parse(json)['status']
    
    redirect_to :action => :index, :restore_status => _status
  end
  
end