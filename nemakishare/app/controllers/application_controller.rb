require 'rubygems'
require 'active_cmis'

class ApplicationController < ActionController::Base
  protect_from_forgery
  
  before_filter :check_authentication, :set_popup_param
  
  def check_authentication
    if session[:nemaki_auth_info] != nil
           
      @nemaki_repository = NemakiRepository.new(session[:nemaki_auth_info])
      @login_user = User.new
      @login_user.id = session[:nemaki_auth_info][:id]
      @login_user.password = session[:nemaki_auth_info][:password]
      is_admin_role?
    end
  end
  
  #TODO modify the logic 
  def is_admin_role?
      @is_admin_role = (@login_user.id == 'admin')
      return @is_admin_role
  end
  
  def set_popup_param
    #Global parameter for views
    if @popup_param == nil
      @popup_param = CONFIG['layout']['popup']['thickbox']['url_param']  
    end
  end
  
  def redirect_to_parent(path)
    render text: "<html><body><script type='text/javascript' charset='utf-8'>window.parent.document.location.href = '" + path+ "';</script></body></html>", content_type: :html
  end
end