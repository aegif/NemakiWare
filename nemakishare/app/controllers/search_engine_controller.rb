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
class SearchEngineController < ApplicationController
  def index
    server_url = CONFIG['search_engine']['server_url']
    @url_for_ui = server_url + "/" + "#/"
    @url_for_initialize = server_url + "/" + "admin/cores?core=nemaki&action=init"
    @url_for_reindex = server_url + "/" + "admin/cores?core=nemaki&action=index&tracking=FULL"
  end
end