require 'rubygems'
require 'active_cmis'

Nemakishare::Application.configure do
  # Settings specified here will take precedence over those in config/application.rb

  # In the development environment your application's code is reloaded on
  # every request. This slows down response time but is perfect for development
  # since you don't have to restart the web server when you make code changes.
  config.cache_classes = false

  # Log error messages when you accidentally call methods on nil.
  config.whiny_nils = true

  # Show full error reports and disable caching
  config.consider_all_requests_local       = true
  config.action_controller.perform_caching = false

  # Don't care if the mailer can't send
  config.action_mailer.raise_delivery_errors = false

  # Print deprecation notices to the Rails logger
  config.active_support.deprecation = :log

  # Only use best-standards-support built into browsers
  config.action_dispatch.best_standards_support = :builtin

  # Raise exception on mass assignment protection for Active Record models
  config.active_record.mass_assignment_sanitizer = :strict

  # Log the query plan for queries taking more than this (works
  # with SQLite, MySQL, and PostgreSQL)
  config.active_record.auto_explain_threshold_in_seconds = 0.5

  # Do not compress assets
  config.assets.compress = false

  # Expands the lines which load the assets
  config.assets.debug = true
  
  #modify icons
  $icons = {
    "application/x-javascript" =>"/nemaki_images/js.gif",
    "text/plain" => "/nemaki_images/text-file-32.png",
    "text/html" => "/nemaki_images/html.gif",
    "application/msword" => "/nemaki_images/doc.gif",
    "text/xml" => "/nemaki_images/xml.gif",
    "image/gif" => "/nemaki_images/gif.gif",
    "image/jpeg" => "/nemaki_images/jpg.gif",
    "image/jpeg2000" => "/nemaki_images/jpg.gif",
    "video/mpeg" => "/nemaki_images/mpeg.gif",
    "audio/x-mpeg" => "/nemaki_images/mpg.gif",
    "video/mp4" => "/nemaki_images/mp4.gif",
    "video/mpeg2" => "/nemaki_images/mp2.gif",
    "application/pdf" => "/nemaki_images/pdf.gif",
    "image/png" => "/nemaki_images/png.gif",
    "application/vnd.powerpoint" => "/nemaki_images/ppt-file-32.png",
    "audio/x-wav" => "/nemaki_images/wmv.gif",
    "application/vnd.excel" => "/nemaki_images/xls-file-32.png",
    "application/zip" => "/nemaki_images/zip.gif",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => "/nemaki_images/doc.gif",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => "/nemaki_images/xls.gif",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" => "/nemaki_images/ppt.gif",
    "folder" => "/nemaki_images/generic-folder-32.png"
  }
  
end
