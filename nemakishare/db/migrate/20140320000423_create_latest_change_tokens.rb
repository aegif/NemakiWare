class CreateLatestChangeTokens < ActiveRecord::Migration
  def change
    create_table :latest_change_tokens do |t|
      t.string :token

      t.timestamps
    end
  end
end
