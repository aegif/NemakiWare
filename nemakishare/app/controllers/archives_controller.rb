# encoding: utf-8

class ArchivesController < ApplicationController
  
  require 'json'
  require 'rest_client'
  
  before_filter :get_auth
  
  def get_auth
    @auth_info = session[:nemaki_auth_info]
  end
  
  def index(status=true)
    @title = "ゴミ箱" 
    
    resource = RestClient::Resource.new('http://localhost:8180/Nemaki/rest/archive/index',@auth_info[:id], @auth_info[:password])
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
          flash[:notice] = "リストアに成功しました"
        else
          flash[:error] = "リストアに失敗しました"
        end  
      end
      
    else
      flash[:error] = "ゴミ箱が表示できません:" + result["error"].to_s
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
    uri = 'http://localhost:8180/Nemaki/rest/archive/restore/' + params[:id]
    resource = RestClient::Resource.new(uri,@auth_info[:id], @auth_info[:password])
    json = resource.put("")
    _status = JSON.parse(json)['status']
    
    redirect_to :action => :index, :restore_status => _status
  end
  
end