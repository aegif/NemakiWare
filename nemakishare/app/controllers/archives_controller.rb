# encoding: utf-8

class ArchivesController < ApplicationController
  
  require 'json'
  require 'rest_client'
  
  before_filter :get_auth
  
  def get_auth
    @auth_info = session[:nemaki_auth_info]
  end
  
  def index(status=true)
    resource = RestClient::Resource.new('http://localhost:8180/Nemaki/rest/archive/index',@auth_info[:id], @auth_info[:password])
    json = resource.get()
    @archives = JSON.parse(json)['archives']
  end
  
  def restore
    uri = 'http://localhost:8180/Nemaki/rest/archive/restore/' + params[:id]
    resource = RestClient::Resource.new(uri,@auth_info[:id], @auth_info[:password])
    json = resource.put("")
    _status = JSON.parse(json)['status']
    if _status == 'failure'
      status = false
    else
      status = true
    end
    redirect_to :action => :index, :alert => status
  end
  
end