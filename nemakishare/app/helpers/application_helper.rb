require 'time'

module ApplicationHelper
  def simple_time(time)
    t = Time.parse(time.to_s)
    t.strftime("%Y-%m-%d %H:%M:%S")
  end

  def simple_time_YMD(time)
    t = Time.parse(time.to_s)
    t.strftime("%Y-%m-%d")
  end
  
  def simple_time_HMS(time)
    t = Time.parse(time.to_s)
    t.strftime("%H:%M:%S")
  end

  def icon_path(node)
    mimetype = node.mimetype
    type = node.type

    #Folder or unknown type    
    if mimetype == nil
      if node.is_folder?
        filename = "folder-icon-default.gif"
      else
        filename = "generic-file-32.png"  
      end
      return "icon/" + filename
    end

    #Document type
    if mimetype == "application/x-javascript"
      filename = "js.gif"
    elsif mimetype == "text/plain"
      filename = "text-file-32.png"
    elsif mimetype == "application/msword"
      filename = "doc-file-32.png"
    elsif mimetype == "text/xml"
      filename = "xml.gif"
    elsif mimetype == "image/gif"
      filename = "img-file-32.png"
    elsif mimetype == "image/jpeg"
      filename = "img-file-32.png"
    elsif mimetype == "image/jpeg2000"
      filename = "jpg.gif"
    elsif mimetype == "video/mpeg"
      filename = "mpeg.gif"
    elsif mimetype == "audio/x-mpeg"
      filename = "mpg.gif"
    elsif mimetype == "video/mp4"
      filename = "mp4.gif"
    elsif mimetype == "video/mpeg2"
      filename = "mp2.gif"
    elsif mimetype == "application/pdf"
      filename = "pdf-file-32.png"
    elsif mimetype == "image/png"
      filename = "img-file-32.png"
    elsif mimetype == "application/vnd.powerpoint"
      filename = "ppt-file-32.png"
    elsif mimetype == "audio/x-wav"
      filename = "wmv.gif"
    elsif mimetype == "application/vnd.excel"
      filename = "xls-file-32.png"
    elsif mimetype == "application/zip"
      filename = "zip.gif"
    elsif mimetype == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      filename = "doc-file-32.png"
    elsif mimetype == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      filename = "xls-file-32.png"
    elsif mimetype == "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      filename = "ppt-file-32.png"
    else
      filename = "generic-file-32.png"
    end
    #View uses image_tag('icon/xxx.xxx') which produces a link '/assets/icon/xxx.xxx'
    icon_path = "icon/" + filename
    return icon_path

  end
end