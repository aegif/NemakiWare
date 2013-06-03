# encoding: utf-8

class CreateChangeEvents < ActiveRecord::Migration
  def change
    create_table :change_events do |t|
      t.string :objectId
      t.string :change_type
      t.datetime :change_time
      t.string :base_type_id
      t.string :object_type_id
      t.string :name
      t.string :version_series
      t.string :user
      t.boolean :first_version
      t.string :site

      t.timestamps
      
    end
  end
end