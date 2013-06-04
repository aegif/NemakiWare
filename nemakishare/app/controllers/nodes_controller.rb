# encoding: utf-8
class NodesController < ApplicationController
  
  before_filter :initialize_breadcrumb
  
  def initialize_breadcrumb
      if session[:breadcrumb].blank?
        session[:breadcrumb] = []        
      end
  end
  
  def set_breadcrumb(node)
    if node == nil
      crumb = {:id => "/", :name => "/"}
    else
      crumb = {:id => node.id, :name => node.name}
    end  
    
    if check_new_breadcrumb crumb
      if session[:breadcrumb].size >= CONFIG['change_log']['display_number']
        session[:breadcrumb].shift
      end
      
      session[:breadcrumb] << crumb
    end 
  end

  def check_new_breadcrumb(crumb)
    if session[:breadcrumb].present?
      if session[:breadcrumb].last[:id] == crumb[:id]
        false
      else
        true
      end
    else !session[:breadcrumb].nil?
      true
    end
  end
  
  def index
    if session[:nemaki_auth_info] != nil
      redirect_to :action => 'explore' 
    end
  end

  def authenticate
    user = params[:user]
    server = ActiveCMIS::Server.new(CONFIG['repository']['server_url'])
    nemaki = server.authenticate(:basic, user[:id], user[:password])
    repo = nemaki.repository(CONFIG['repository']['repository_id'])
    session[:nemaki_auth_info] = user 
    
    redirect_to :action => 'explore'
  end

  def new
    @node = Node.new
    @parent_id = params[:parent_id]
    if params[:type] == 'document'
      @document = true
      @type = "document"
    elsif params[:type] == 'folder'
      @folder = true
      @type = "folder"  
    end
    
    render :layout => 'popup'
  end

  def create
    if params[:type] == 'document'
      cmis_type = 'cmis:document' 
    elsif params[:type] == 'folder'
      cmis_type = 'cmis:folder'
    end
    
    @nemaki_repository.create(params[:node], params[:parent_id], cmis_type)    
    
    #TODO 失敗時の処理
    redirect_to_parent(explore_node_path(params[:parent_id]))       
  end

  def edit
    @node = @nemaki_repository.find(params[:id])
    @aspects = @nemaki_repository.get_aspects_with_attributes(@node)
    render :layout => 'popup'
  end 
  
  def update
    node = @nemaki_repository.find(params[:id])
   
    update_properties = Hash.new
    bps = JSON.parse(params[:basic_properties])
      if !bps.nil? && !bps.empty?
        bps.each do |bp|
          puts 
          update_properties[bp['key']] = bp['value']
        end
      end 
     
   update_aspects = @nemaki_repository.convert_input_to_aspects(node, JSON.parse(params[:custom_properties])) 

   #TODO CMIS属性の更新でdiffがあるときのみupdateになっているか確認 
   @nemaki_repository.update(params[:id], update_properties, update_aspects)

    #TODO 失敗時の処理
    
    parent = @nemaki_repository.get_parent(node)
    redirect_to_parent(explore_node_path(parent.id))
  end

  #新規バージョンのアップロード処理
  def upload
    @nemaki_repository.upload(params[:id], params[:node])
    node = @nemaki_repository.find(params[:id])
    redirect_to :action => 'explore', :id => node.parent_id #TODO pass via params?
  end

  def show
    @node = @nemaki_repository.find(params[:id])
    aspects_all = @nemaki_repository.get_aspects_with_attributes(@node)
    
    @aspects = []
    if aspects_all != nil && !aspects_all.empty?
      aspects_all.each do |a|
        if a.implemented
          @aspects << a  
        end
      end
    end
    
    if @node.is_document?
      @versions = @nemaki_repository.get_all_versions(@node)  
    end
    @parent = @nemaki_repository.get_parent @node
    
    #Navigation setting
    site = @nemaki_repository.get_site_by_node_path(@parent.path)
    set_allowed_up(site)
    
    render :layout => 'popup'  
  end

  def download
    if params[:id]
      @node = @nemaki_repository.find(params[:id])
      send_data @nemaki_repository.get_stream_data(@node), :filename => @node.name, :type => @node.mimetype
    end
  end
  
  def move
    
  end
  
  def explore
      #Get the folder to be explored
      if(params[:id] )
        @node = @nemaki_repository.find(params[:id])
      else
        if is_admin_role?
          @node = @nemaki_repository.find('/')
        else
          @node = @nemaki_repository.find(CONFIG['site']['root_id'])
        end
      end
      
      #Get the children contained in the folder
      if @node.is_folder? 
        #Retrieve folder items
        @maxItems = params[:maxItems].blank? ? CONFIG['paging']['maxItems'] + 1 : params[:maxItems].to_i + 1
        @skipCount = params[:skipCount].blank? ? 0 : params[:skipCount]
        @nodes = @nemaki_repository.get_children(@node, @maxItems, @skipCount)

        #Check paging parameter      
        @hasBeforePage = (@skipCount.blank? || @skipCount.to_i > 0) ? true : false 
        if @nodes != nil && !@nodes.blank?
          if @nodes.size > CONFIG['paging']['maxItems']
            @hasNextPage = true
            @nodes.pop
          else
            @hasnextPage = false
          end
        end 
        if !@node.is_root?
           @parent = @nemaki_repository.get_parent(@node)
        end  
      else
        nil 
      end
      
      #Set the site name to which the node belongs
      if @node.is_root?
        @site_name = "Repository Root"
      elsif @node.is_site_root?
        @site_name = "サイト一覧"
      else
        @site_name = @nemaki_repository.get_site_name(@node)
        if @site_name.nil?
          @site_name = ""
        end  
      end
      
      #チェンジログ
      ##現在のサイトに属するものだけ抽出
      site = @nemaki_repository.get_site_by_node_path(@node.path)
      if site != nil
        @site_changes = ChangeEvent.where(:site => site.id).last(CONFIG['change_log']['display_number'])
        @site_changes.reverse!  
      end
            
      #Set breadcrumbs
      set_breadcrumb(@node)
      @breadcrumbs = session[:breadcrumb]
      
      #Navigation setting
      set_allowed_up(site)
  end
  
  def set_allowed_up(site)
    if site == nil
      @is_allowed_up = @is_admin_role
    else
      @is_allowed_up = true  
    end
  end
  
  def search
    @search_form = SearchForm.new(params[:search_form])
    if !@search_form.valid?
      flash[:error] = "検索ワードを入力してください"
      redirect_to :action => :explore, :id => "/"
      return
    end
    
    q = @search_form.query

    #TODO Search under a site

    #Build CMIS SQL Query statement
    #NOTE: CMIS says CONTAINS() 'MAY' be allowed with AND.
    statement_document = "SELECT * FROM cmis:document WHERE (cmis:name = '" + q + "' OR CONTAINS('" + q +  "')) AND cmis:isLatestVersion = true"
    @nodes = @nemaki_repository.search(statement_document)
    statement_folder = "SELECT * FROM cmis:folder WHERE cmis:name = '" + q + "'"
    @nodes = @nodes + @nemaki_repository.search(statement_folder)
    
    #Set site
    @sites = Hash.new
    sites_cache = Hash.new
    site_root = @nemaki_repository.get_site_root
    @nodes.each do |n|
      p = @nemaki_repository.get_parent n
      site_name = @nemaki_repository.get_site_name(p)
      if site_name.blank?
        @sites[n.id] = nil
      else
        if sites_cache[site_name].blank?
          site_path = site_root.path + "/" + site_name
          site = @nemaki_repository.find_by_path site_path
          sites_cache[site_name] = site
        end
        @sites[n.id] = sites_cache[site_name]  
      end
    end
    
    #Set query word
    @query_word = q
  end
  
  def destroy
    if params[:id]
      parent_id = @nemaki_repository.delete(params[:id])
    end
    #TODO rootだった場合の処理
    redirect_to :action => 'explore', :id => parent_id
  end
  
  def edit_permission
    @node = @nemaki_repository.find(params[:id])  
    render :layout => 'popup'  
  end
  
  def update_permission
    if params[:id]
      node = @nemaki_repository.find(params[:id]);
    end
   
    @nemaki_repository.update_permission(params[:id], params[:acl][:entries], params[:acl][:inheritance]);
  
    parent = @nemaki_repository.get_parent(node)
    redirect_to_parent(explore_node_path(parent.id))
  end
  
  def redirect_parent_id(node)
    parent = @nemaki_repository.get_parent(node);
    if parent == nil
      parent_id = "/"  
    else  
      parent_id = parent.id
    end
    return parent_id
  end
  
  def logout
    session[:nemaki_auth_info] = nil
    redirect_to :action => :index
  end
end