class Site  < ActiveModelBase
  
  attr_accessor :id, :name
  def create()
    
     #sitesフォルダはある前提
     #TODO idをなんとか取得する
     
    sites_id = Site.get_sites_id
    
    #TODO もっと簡単に一行でNode.createできるようにする
    #sitesフォルダ下にsite_nameフォルダを作成
    site = Node.new
    site.name = @name
    site.parent_id = sites_id
    site_obj = site.create('cmis:folder')
    
    #site_nameフォルダ下にdocRootフォルダを作成
    docRoot = Node.new
    docRoot.name = 'docRoot'
    docRoot.parent_id = site_obj.attributes['cmis:objectId']
    docRoot.create('cmis:folder')
  end
  
  def self.list
    #TODO mode validation
    q = "SELECT * FROM cmis:folder WHERE cmis:parentId = '" + self.get_sites_id + "'"
    sites = Node.search(q)
  end
  
  def self.get_sites_id
    root_folder = Node.root_folder
    root_id = root_folder.id
    
    query_string = "SELECT * FROM cmis:folder WHERE cmis:name = 'sites' AND cmis:parentId = '" + root_id + "'"
    sites_obj = Node.search(query_string).first
    if sites_obj == nil
      return nil
    end
    return sites_obj.id
  end
  
  
end