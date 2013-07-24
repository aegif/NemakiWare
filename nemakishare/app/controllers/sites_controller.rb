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
    if params[:site][:name].empty?
      flash[:error] = t("message.site.create_error")
      redirect_to :controller => 'sites', :action => 'index'
    else
      @nemaki_repository.create_site(params[:site][:name])
      flash[:notice] = t('message.site.create_success')
      redirect_to :controller => 'sites', :action => 'index'    
    end
  end

  def check_site_create_permission
    @can_create_site = false
    sites_root = @nemaki_repository.get_site_root
    if sites_root != nil
      @can_create_site = sites_root.allowable_actions['CreateFolder'] && sites_root.allowable_actions['ApplyACL']
    end 
  end
end