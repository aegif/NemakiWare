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
require 'active_cmis_custom'

class ApplicationController < ActionController::Base
  protect_from_forgery
  
  before_filter :check_authentication, :set_popup_param, :set_locale, :show_stacked_message
  
  def check_authentication
    if session[:nemaki_auth_info] != nil
           
      @nemaki_repository = NemakiRepository.new(session[:nemaki_auth_info], logger)

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
    session[:messages] = @notified_messages
    render text: "<html><body><script type='text/javascript' charset='utf-8'>window.parent.document.location.href = '" + path+ "';</script></body></html>", content_type: 'text/html'
  end
  
  def set_locale
    I18n.locale = extract_locale_from_accept_language_header
  end

  def addErrorMessage(text_label, title_label = "message.general.general_title_error")
    addMessage(title_label, text_label, "error")
  end

  def addInfoMessage(text_label, title_label = "message.general.general_title_info")
    addMessage(title_label, text_label, "info")
  end

  def addSuccessMessage(text_label, title_label = "message.general.general_title_success")
    addMessage(title_label, text_label, "success")
  end


  def addMessage(title_label, text_label, category)
    (@notified_messages ||= []).push(NotifiedMessage.new(I18n.t(title_label), I18n.t(text_label), category))
  end

  def show_stacked_message()
    unless session[:messages].nil?
      @notified_messages = session[:messages]
      session[:messages] = nil
    end
  end

  # for logging
  p logger.level
  if logger.level == Logger::DEBUG

    def self.wrap_by_log(method) 
      original_method_s = ( "original_" + method.to_s ).to_sym
      alias_method original_method_s, method
      define_method(method) do |*args, &block|
        logger.debug "** [#{method}] start"
        result = self.send original_method_s, *args, &block
        logger.debug "** [#{method}] end"
        result
      end
    end

   wrap_by_log :check_authentication
  end
  
  private
  def extract_locale_from_accept_language_header
    request.env['HTTP_ACCEPT_LANGUAGE'].scan(/^[a-z]{2}/).first
  end

end
