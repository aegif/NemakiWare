# encoding: utf-8
class Node  < ActiveModelBase
  @@attributes = [:id, :name, :description, :path, :parent_id, :type, :object_type, :acl, :acl_inherited, :allowable_actions, :mimetype, :size, :creator,:created, :modifier, :modified, :version_series_id, :version_label, :aspects, :change_type, :change_time]
  @@attributes.each {|attr| attr_accessor attr}
  
  validates_presence_of :name
  validates_length_of :name, :minimum => 1
  
  def to_hash
    hash = Hash.new
    @@attributes.each do |attr|
      if attr == :created || attr == :modified
        hash[attr] = simple_time_YMD(send("#{attr}")) + "  " + simple_time_HM(send("#{attr}"))
      else
        hash[attr] = send("#{attr}")
      end
    end
    hash
  end
  
  def to_json
    to_hash.to_json
  end
  
  # setup CMIS repository
  def is_document?
    @type == "cmis:document" || false
  end

  def is_folder?
    @type == "cmis:folder" || false
  end
  
  def is_root? 
    @path == "/" || false 
  end
  
  def is_site_root?
    @id == CONFIG['site']['root_id'] || false
  end
  
  def simple_time_YMD(time)
    t = Time.parse(time.to_s)
    t.strftime("%Y-%m-%d")
  end
  
  def simple_time_HM(time)
    t = Time.parse(time.to_s)
    t.strftime("%H:%M")
  end
  
end