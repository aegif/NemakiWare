# encoding: utf-8
class Archive  < ActiveModelBase
  attr_accessor :id, :name, :type, :mimetype, :parent_id, :created, :creator, :is_deleted_with_parent

  #FIXME Mix-in in common with Node class
  def is_document?
    @type == "cmis:document" || false
  end

  def is_folder?
    @type == "cmis:folder" || false
  end
end

