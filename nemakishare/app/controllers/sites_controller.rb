# encoding: utf-8
class SitesController < ApplicationController
  
  def index
    @sites = @nemaki_repository.get_site_list
    @login_user = session[:nemaki_auth_info][:id]
    
    #FIXME modify the logic
    if @login_user == 'admin'
      @sites.unshift @nemaki_repository.get_root_folder
    end

    check_site_create_permission
    
    render :layout => 'popup'
  end

  def create
    @nemaki_repository.create_site(params[:site][:name])
    flash[:notice] = "新規サイトを作成しました"
    redirect_to :controller => 'sites', :action => 'index'
  end

  def check_site_create_permission
    @can_create_site = false
    sites_root = @nemaki_repository.get_site_root
    if sites_root != nil
      @can_create_site = sites_root.allowable_actions['CreateFolder'] && sites_root.allowable_actions['ApplyACL']
    end 
  end
  
end