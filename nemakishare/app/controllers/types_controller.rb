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
class TypesController < ApplicationController
  def index

  end

  def create
    data=params[:file]

    json = @nemaki_repository.create_types data

    #Use notification tool
    if(json['status'] == "success")
      addInfoMessage("message.node.success_text_create")
    else
      addErrorMessage("message.node.error_text_create")
    end

    redirect_to_parent(types_path)
  end
end
