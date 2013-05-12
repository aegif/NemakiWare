require 'rubygems'
require 'active_cmis'

class ApplicationController < ActionController::Base
  protect_from_forgery
  
  before_filter :foo
  
  def foo
    if session[:nemaki_auth_info] != nil
           
      @login_user = User.new
      @login_user.id = session[:nemaki_auth_info][:id]
      @login_user.password = session[:nemaki_auth_info][:password]
    end
  end
end
