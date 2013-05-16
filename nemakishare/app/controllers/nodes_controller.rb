class NodesController < ApplicationController
  
  before_filter :set_repository
  
  def set_repository
      if session[:nemaki_auth_info] != nil
         @nemaki_repository = NemakiRepository.new(session[:nemaki_auth_info])
      end
      
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
      if session[:breadcrumb].size > 5
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
    
    @login_user = User.new
    @user = User.new
  end

  def authenticate
    user = params[:user]
    
    cmis_config = Hash.new
    cmis_config["repo_id"] = "books"
    server = ActiveCMIS::Server.new("http://localhost:8180/Nemaki/atom/")
    nemaki = server.authenticate(:basic, user[:id], user[:password])
    repo = nemaki.repository(cmis_config['repo_id'])
    session[:nemaki_auth_info] = user 
    
    redirect_to :action => 'explore'
  end

  def new
    @node = Node.new
    @parent_id = params[:parent_id]
    if params[:type] == 'document'
      @document = true
      @type = "document"
    else params[:type] == 'folder'
      @folder = true
      @type = "folder"  
    end
    
    render :layout => 'popup'
  end

  def create
    if params[:type] == 'document'
      cmis_type = 'cmis:document' 
    else params[:type] == 'folder'
      cmis_type = 'cmis:folder'
    end
    
    @nemaki_repository.create(params[:node], params[:parent_id], cmis_type)    
    
    #TODO 失敗時の処理
    redirect_to :action => 'explore', :id => params[:parent_id]        
  end

  #更新画面のレンダリング
  def edit
    @node = @nemaki_repository.find(params[:id])

    @aspects = @nemaki_repository.get_aspects_with_attributes(@node)
    
    render :layout => 'popup'
  end 
  
  #更新処理
  def update
   original_node = @nemaki_repository.find(params[:id])
   update_aspects = convert_to_aspects(original_node, params[:aspects]) 

   #TODO CMIS属性の更新でdiffがあるときのみupdateになっているか確認 
   @nemaki_repository.update(params[:id], params[:node], update_aspects)

   node = @nemaki_repository.find(params[:id]) #TODO
    #TODO 失敗時の処理
    redirect_to :action => 'explore', :id => node.parent_id #TODO pass via params?
  end
  
  def convert_to_aspects(node, param_aspects) 
    if param_aspects == nil
      return []
    end
    
    aspects = []  #list of aspect
    
    param_aspects.each do |param_aspect|
      aspect = Aspect.new
      aspect.id = param_aspect[:id]
      
      #TODO original_aspectがnilの場合 
      original_aspect = @nemaki_repository.get_aspect_by_id(node, aspect.id)   
      
      #convert
      param_aspect[:properties].each do |param_property|
        puts param_aspect[:properties]
        property = Property.new
        property.key = param_property[:key]
        property.value = param_property[:value]
        aspect.properties << property        
      end
      aspects << aspect
    end
    return aspects
  end

  def edit_upload
    @node = @nemaki_repository.find(params[:id])
    render :layout => 'popup'
  end

  #新規バージョンのアップロード処理
  def upload
    @nemaki_repository.upload(params[:id], params[:node])
    node = @nemaki_repository.find(params[:id])
    redirect_to :action => 'explore', :id => node.parent_id #TODO pass via params?
  end

  def show
    @node = @nemaki_repository.find(params[:id])
    @aspects = @nemaki_repository.get_aspects_with_attributes(@node)
    @versions = @nemaki_repository.get_all_versions(@node)
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
      puts "1"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      #親フォルダ
      if(params[:id])
        @node = @nemaki_repository.find(params[:id])  
      else
        @node = @nemaki_repository.find('/')
      end
      
      puts "2"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      #親フォルダに含まれるノードリスト
      if @node.is_folder? 
        @nodes = @nemaki_repository.get_children(@node) 
      else
        nil 
      end
      
      puts "3"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
    
      #現在のサイト
      #親フォルダが属するサイトのノードを返す
      #@site = @nemaki_repository.get_site(@node)
      @site = @nemaki_repository.get_root_folder
      
      #サイトのリスト
      puts "3.1"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }     
      @site_list = @nemaki_repository.get_site_list
      
      #チェンジログ
      ##現在のサイトに属するものだけ抽出
      puts "3.2"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      @changes = @nemaki_repository.get_changes(@site.id)
      
      #breadcrumbs
      puts "3.3"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      #@breadcrumbs = @nemaki_repository.get_breadcrumbs(@node)
      set_breadcrumb(@node)
      @breadcrumbs = session[:breadcrumb]
      #session[:breadcrumb] = []
      
      puts "4"
      puts Time.now.instance_eval { '%s.%03d' % [strftime('%Y/%m/%d %H:%M:%S'), (usec / 1000.0).round] }
      puts "end"
  end
  
  def search
    form = params[:search_form]
    q = form[:query]

    #TODO Search under a site

    #Build CMIS SQL Query statement
    statement_document = "SELECT * FROM cmis:document WHERE cmis:name = '" + q + "' OR CONTAINS('" + q +  "')"
    @nodes = @nemaki_repository.search(statement_document)
    statement_folder = "SELECT * FROM cmis:folder WHERE cmis:name = '" + q + "'"
    @nodes = @nodes + @nemaki_repository.search(statement_folder)
    @query_word = q
  end
  
  def destroy
    if params[:id]
      parent_id = @nemaki_repository.delete(params[:id])
    end
    #TODO rootだった場合の処理
    redirect_to :action => 'explore', :id => parent_id
  end
  
  def permission
    @node = @nemaki_repository.find(params[:id])  
    render :layout => 'popup'  
  end
  
  def update_permission
    if params[:id]
      node = @nemaki_repository.find(params[:id]);
      parent = @nemaki_repository.get_parent(node); 
    end
   
    @nemaki_repository.update_permission(params[:id], params[:acl_json], params[:inheritance]);
  
    if parent == nil
      redirect_to :action => 'explore', :id => "/"  
    else  
      redirect_to :action => 'explore', :id => parent.id
    end
  end
  
  def logout
    session[:nemaki_auth_info] = nil
    #FIXME HARD-CODED
    redirect_to 'http://127.0.0.1:3000/nodes/'
  end
  
end