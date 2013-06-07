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
class GroupsController < ApplicationController

  def index
    @title = "グループ管理画面"
    
    @search_form = SearchForm.new
    
    @groups = Array.new
    if params[:search_form].nil?
      @groups = []
    else
      query = params[:search_form][:query]
      @search_form.query = query
      json = @nemaki_repository.search_groups query
      if json['status'] == 'success'
          results = json['groups']
          if results != nil && !results.blank?
            results.each do |result|
              @groups << convert_group_from_json(result)
            end
          end  
      end
    end
  end
  
  def show
    parse_result_json
    render :layout => 'popup'
  end
  
  def parse_result_json(id=nil)
    if id.nil?
      id = params[:id]
    end
    
    result = @nemaki_repository.get_group_by_id(params[:id])
    if result && result['status'] == 'success'
      @group = convert_group_from_json(result['group'])
    else result && result['status'] == 'error'
      @error = result['error']
    end
  end
  
  def new
    @group = Group.new
    render :layout => 'popup'
  end

  def create
    @group = Group.new(params[:groups])
    if @group.valid?
      result = @nemaki_repository.create_group @group
      if is_success?(result)
        flash[:notice] = "グループが新規作成されました"
      else
        flash[:error] = "グループの新規作成に失敗しました"
      end
    else
      flash[:error] = "バリデーションエラー：正しい値を入力してください"
    end
    redirect_to_parent(groups_path(:search_form => {:query => params[:groups][:id]}))
  end
  
  def edit
    parse_result_json
    render :layout => 'popup'
  end

  def update
    @group = Group.new(params[:group])
    if @group.valid?
      result = @nemaki_repository.update_group @group
      if is_success?(result)
        flash[:notice] = "グループの詳細編集に成功しました"
      else
        flash[:error] = "グループの詳細編集に失敗しました:" + result['error'].to_json
      end
    else
      flash[:error] = "バリデーションエラー：正しい値を入力してください"
    end
    redirect_to_parent(groups_path(:search_form => {:query => params[:id]}))
  end
  
  def destroy
    result = @nemaki_repository.delete_group params[:id]
    if is_success?(result)
      flash[:notice] = "グループの削除に成功しました"
    else
      flash[:error] = "グループの削除に失敗しました:" + result['error'].to_json
    end
    redirect_to_parent(groups_path(:search_form => {:query => params[:id]}))
  end
  
  def search
    result = @nemaki_repository.search_groups(params[:search_form][:query])
    if result['status'] == 'success'
      groups = result['groups']
    end
    render :json => groups
  end
  
  def edit_member_users
    parse_result_json
    render :layout => 'popup'
  end
  
  def edit_member_groups
    parse_result_json
    render :layout => 'popup'
  end

  def update_member_users
    update_member_internal :user, params[:principals_json]
  end
  
  def update_member_groups
   update_member_internal :group, params[:principals_json]
  end
    
  def update_member_internal(member_type, principals_json)
    principals = JSON.parse principals_json
    parse_result_json
    
    principalIds = Array.new
    principals.each do |p|
      principalIds << p['principalId']
    end 
    
    members = []
    case member_type
    when :user
      if @group != nil && @group.users != nil
        members = @group.users
      end
    when :group
      if @group != nil && @group.users != nil
        members = @group.groups
      end
    end
    
    #Added record that is not in the list
    added = []
    (principalIds - members).each do |d|
      added.push({"id" => d})
    end
      
    #Removed record that is not in the list
    removed = []
    (members - principalIds).each do |d|
      removed.push({"id" => d})
    end
    
    case member_type
    when :user
      @nemaki_repository.update_group_members("add", @group, added, [])
      @nemaki_repository.update_group_members("remove", @group, removed, [])
    when :group
      @nemaki_repository.update_group_members("add", @group, [], added)
      @nemaki_repository.update_group_members("remove", @group, [], removed)
    end  
    
    
    redirect_to_parent(groups_path(:search_form => {:query => params[:id]}))
  end
  
  def convert_group_from_json(group_json)
    group = Group.new
    group.id = group_json['groupId']
    group.name = group_json['groupName']
    group.users = group_json['users']
    group.groups = group_json['groups']
    return group
  end
  
  def is_success?(result)
   puts result['status']
    result['status'] == "success" 
  end
end