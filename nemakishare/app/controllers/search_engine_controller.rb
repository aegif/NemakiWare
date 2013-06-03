# encoding: utf-8
class SearchEngineController < ApplicationController
  def index
    @title = "検索エンジン(Solr)管理画面"
    server_url = CONFIG['search_engine']['server_url']
    @url_for_ui = server_url + "/" + "#/"
    @url_for_initialize = server_url + "/" + "admin/cores?core=nemaki&action=init"
    @url_for_reindex = server_url + "/" + "admin/cores?core=nemaki&action=index&tracking=FULL"
  end
end