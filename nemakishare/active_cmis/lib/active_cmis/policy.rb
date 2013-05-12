module ActiveCMIS
  class Policy < ActiveCMIS::Object
    private
    def create_url
      if f = parent_folders.first
        f.items.url
      else
        raise "not yet"
        # Policy collection of containing document?
      end
    end
  end
end
