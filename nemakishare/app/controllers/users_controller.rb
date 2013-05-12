class UsersController < ApplicationController
  before_filter :set_repository
  
  def set_repository
      if session[:nemaki_auth_info] != nil
        @nemaki_repository = NemakiRepository.new(session[:nemaki_auth_info])
      end
  end
  
  def show
    
  end
  
  def search
    form = params[:search_form]
    q = form[:query]
    users = @nemaki_repository.search_users(q)
    render :json => users  
  end
end