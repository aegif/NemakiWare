# encoding: utf-8
class ChangeEvent < ActiveRecord::Base
  attr_accessible :base_type_id, :change_time, :change_type, :first_version, :name, :objectId, :object_type_id, :site, :user, :version_series
end
