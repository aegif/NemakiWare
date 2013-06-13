require 'rubygems'

begin
  require 'yard'

  YARD::Rake::YardocTask.new do |t|
    t.files   = ['lib/**/*.rb', '-', 'TODO']   # optional
    t.options = ["--default-return", "::Object", "--query", "!@private", "--hide-void-return"]
  end
rescue LoadError
  puts "Yard, or a dependency, not available. Install it with gem install yard"
end

begin
  require 'jeweler'
  require './lib/active_cmis/version'
  Jeweler::Tasks.new do |gemspec|
    gemspec.name = "active_cmis"
    gemspec.version = ActiveCMIS::Version::STRING
    gemspec.summary = "A library to interact with CMIS repositories through the AtomPub/REST binding"
    gemspec.description = "A CMIS library implementing both reading and updating capabilities through the AtomPub/REST binding to CMIS."
    gemspec.email = "joeri@xaop.com"
    gemspec.homepage = "http://xaop.com/labs/activecmis/"
    gemspec.authors = ["Joeri Samson"]

    gemspec.add_runtime_dependency 'nokogiri', '>= 1.4.1'
    gemspec.add_runtime_dependency 'ntlm-http', '~> 0.1.1'
    gemspec.add_runtime_dependency 'require_relative', '~> 1.0.2'

    gemspec.required_ruby_version = '>= 1.8.6'
    gemspec.files.exclude '.gitignore'
  end
  Jeweler::GemcutterTasks.new
rescue LoadError
  puts "Jeweler (or a dependency) not available. Install it with: gem install jeweler"
end
