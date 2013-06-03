CONFIG = YAML.load_file("#{Rails.root}/config/nemakiware_config.yml")
VALIDATION = CONFIG['validation']

#
#Change log crawler
#
require 'rufus/scheduler'

scheduler = Rufus::Scheduler.start_new
  
scheduler.every '20s' do    
  ChangeLogSubscription.execute
end