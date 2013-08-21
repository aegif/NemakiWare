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
require 'rubygems'
require 'active_cmis'

class ApplicationController < ActionController::Base
  protect_from_forgery
  
  before_filter :check_authentication, :set_popup_param, :set_locale
  
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
  
  def set_locale
    I18n.locale = extract_locale_from_accept_language_header
  end

  # for logging
  if logger.level == Logger::DEBUG
    def self.wrap_by_log(original_method, method) 
      define_method(method) do |*args, &block|
        logger.debug "** [#{method}] start"
        result = self.send original_method, *args, &block
        logger.debug "** [#{method}] end"
        result
      end
    end

    alias :original_check_authenticate :check_authenticate
    wrap_by_log :original_check_authenticate, :check_authenticate    

  end
  
  private
  def extract_locale_from_accept_language_header
    request.env['HTTP_ACCEPT_LANGUAGE'].scan(/^[a-z]{2}/).first
  end

end
