# Load the rails application
require File.expand_path('../application', __FILE__)

#Set global config
#CONFIG = YAML.load_file("#{Rails.root}/config/nemakiware_config.yml")

# Initialize the rails application
Nemakishare::Application.initialize!
