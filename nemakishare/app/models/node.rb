# encoding: utf-8
class Node  < ActiveModelBase
  attr_accessor :id, :name, :path, :parent_id, :type, :acl, :allowable_actions, :mimetype, :size, :creator,:created, :modifier, :modified, :version_series_id, :version_label, :aspects, :change_type, :change_time

  # setup CMIS repository
  def is_document?
    @type == "cmis:document" || false
  end

  def is_folder?
    @type == "cmis:folder" || false
  end
end