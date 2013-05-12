class SitesController < ApplicationController
  
  #これnodesと共通化できそう
   before_filter :set_repository
  
  def set_repository
      if session[:nemaki_auth_info] != nil
         @nemaki_repository = NemakiRepository.new(session[:nemaki_auth_info])
      end
  end
  
  def index
    list = @nemaki_repository.get_site_list
    first = list.first
    @site_name = list.first.property_by_id('cmis:name')
  end

  def new
     @site = Site.new
     render :layout => 'popup'
  end

  def create
    @nemaki_repository.create_site(params[:site][:name])
    redirect_to :controller => 'nodes', :action => 'explore', :id => '/'
  end
  
  
  
  
end