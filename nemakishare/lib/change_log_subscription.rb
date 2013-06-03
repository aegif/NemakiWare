require 'active_cmis'

class ChangeLogSubscription
  def self.authenticate
    nemaki_auth_info = {:id => CONFIG['repository']['admin_id'], :password => CONFIG['repository']['admin_password']}
    @nemaki_repository = NemakiRepository.new(nemaki_auth_info) 
  end

  def self.execute
   authenticate
   @nemaki_repository.cache_changes 
  end
end