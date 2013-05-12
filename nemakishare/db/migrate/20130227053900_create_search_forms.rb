class CreateSearchForms < ActiveRecord::Migration
  def change
    create_table :search_forms do |t|
      t.string :type
      t.string :query

      t.timestamps
    end
  end
end
