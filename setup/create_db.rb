require 'rest_client'

begin
	puts "Creating DB: " + ARGV[0]
	RestClient.put ARGV[0], {}
#RestClient got error when receiving 412 status code from CouchDB
rescue
	puts "DB may already exist: " + ARGV[0]
	#Init the db
	puts "Delete and recreate the db: " + ARGV[0]
	RestClient.delete ARGV[0]	
	RestClient.put ARGV[0], {}
end