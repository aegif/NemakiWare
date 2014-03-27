CONFIG = YAML.load_file("#{Rails.root}/config/nemakishare_config.yml")
VALIDATION = CONFIG['validation']

config_repo = CONFIG['repository']
repo_protocol = config_repo['server_protocol']
repo_host = config_repo['server_host']
repo_port = config_repo['server_port']
repo_context = config_repo['server_context']
repo_binding = config_repo['server_binding']
REPOSITORY_SERVER_ROOT = "#{repo_protocol}://#{repo_host}:#{repo_port}/#{repo_context}/"
REPOSITORY_SERVER_URL = REPOSITORY_SERVER_ROOT + "#{repo_binding}/"
REPOSITORY_SERVER_REST_ROOT = REPOSITORY_SERVER_ROOT + "rest/"
USER_REST_URL = REPOSITORY_SERVER_REST_ROOT + "user/"
GROUP_REST_URL = REPOSITORY_SERVER_REST_ROOT + "group/"
TYPE_REST_URL = REPOSITORY_SERVER_REST_ROOT + "type/"
ARCHIVE_REST_URL = REPOSITORY_SERVER_REST_ROOT + "archive/"
REPOSITORY_MAIN_ID = config_repo['repository_main_id']

config_search = CONFIG['search_engine']
search_protocol = config_search['server_protocol']
search_host = config_search['server_host']
search_port = config_search['server_port']
search_context = config_search['server_context']
SEARCH_ENGINE_URL = "#{search_protocol}://#{search_host}:#{search_port}/#{search_context}/"

#
#Change log crawler
#
require 'rufus/scheduler'

scheduler = Rufus::Scheduler.start_new
  
scheduler.every '20s' do    
  ChangeLogSubscription.execute
end