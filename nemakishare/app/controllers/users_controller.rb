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
class UsersController < ApplicationController

  def index
    @title = "ユーザ管理画面"
    
    @search_form = SearchForm.new
    
    @users = Array.new
    if params[:search_form].nil?
      @users = []
    else
      query = params[:search_form][:query]
      @search_form.query = query
      results = search_internal(query)
      results.each do |json|
        @users << convert_user_from_json(json)
      end        
    end
  end
  
  #FIXME there is already User model!
  def convert_user_from_json(user_json)
    user = User.new
    user.id = user_json['userId']
    user.name = user_json['userName']
    user.first_name = user_json['firstName']
    user.last_name = user_json['lastName']
    user.email = user_json['email']
    return user
  end
  
  def parse_result_json
    result = @nemaki_repository.get_user_by_id(params[:id])
    if result && result['status'] == 'success'
      @user = convert_user_from_json(result['user'])
    else result && result['status'] == 'error'
      @error = result['error']
    end
  end
  
  def show
    parse_result_json
    
    render :layout => 'popup'
  end

  def new
    @user = User.new
    render :layout => 'popup'
  end
  
  def edit
    parse_result_json
    
    render :layout => 'popup'
  end
  
  def edit_password
    parse_result_json
    
    render :layout => 'popup'
  end

  def create
    @user = User.new(params[:user])
    if @user.valid?
      result = @nemaki_repository.create_user @user
      if is_success?(result)
        flash[:notice] = "ユーザが新規作成されました"
      else
        flash[:error] = "ユーザの新規作成に失敗しました"
      end
    else
      flash[:error] = "バリデーションエラー：正しい値を入力してください"  
    end
    redirect_to_parent(users_path(:search_form => {:query => params[:user][:id]}))
  end
  
  def is_success?(result)
   puts result['status']
    result['status'] == "success" 
  end
  
  def update
    @user = User.new(params[:user])
    if @user.valid?
      result = @nemaki_repository.update_user @user
      if is_success?(result)
        flash[:notice] = "ユーザ情報が更新されました"
      else
        flash[:error] = "ユーザ情報の更新に失敗しました"
      end
    else
      flash[:error] = "バリデーションエラー：正しい値を入力してください"  
    end
    
    redirect_to_parent(users_path(:search_form => {:query => params[:id]}))
  end
  
  def update_password
    @user = User.new(params[:user])
    if @user.valid?
      result = @nemaki_repository.update_user_password @user
      if is_success?(result)
        flash[:notice] = "ユーザのパスワードが更新されました"
      else
        flash[:error] = "パスワードの更新に失敗しました"
      end
    else
      flash[:notice] = "バリデーションエラー：正しい値を入力してください"  
    end
    redirect_to_parent(users_path(:search_form => {:query => params[:id]}))
  end
  
  def search
    users = search_internal(params[:search_form][:query])
    render :json => users
  end
  
  def search_internal(query)
    users = @nemaki_repository.search_users(query)
  end
  
  def destroy
    result = @nemaki_repository.delete_user params[:id]
    if result && result['status'] == 'error'
      @error = result['error']
    end
    redirect_to_parent(users_path(:search_form => {:query => params[:id]}))
  end
  
end