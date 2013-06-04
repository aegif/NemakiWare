# encoding: utf-8

# *******************************************************************************
# Copyright (c) 2013 aegif.
# 
# This file is part of NemakiWare.
# 
# NemakiWare is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
# 
# NemakiWare is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
# 
# You should have received a copy of the GNU General Public License along with NemakiWare.
# If not, see <http://www.gnu.org/licenses/>.
# 
# Contributors:
#     linzhixing - initial API and implementation
# ******************************************************************************
require 'time'

module ApplicationHelper
  def simple_time(time)
    t = Time.parse(time.to_s)
    t.strftime("%Y-%m-%d %H:%M")
  end

  def simple_time_YMD(time)
    t = Time.parse(time.to_s)
    t.strftime("%Y-%m-%d")
  end
  
  def simple_time_HMS(time)
    t = Time.parse(time.to_s)
    t.strftime("%H:%M:%S")
  end

  def divide_path(path)
    ary = path.split("/")
    ary.delete_at(0)
    ary
  end
  
  #TODO implemented only Japanese 
  def l10n_change_type(change_type, language_code=nil)
    case change_type
    when "created"
      "作成"
    when "updated"
      "更新"
    when "deleted"
      "削除"
    when "security"
      "権限を変更"
    when "version-updated"
      "バージョンを更新"
    else
    end
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
  
  def simple_header(title)
    html = 
      "<div class='navbar-outer' style='padding-top:0;margin-top:0;'><div class='navbar navbar-fluid-top' style='padding-top:0;margin-top:0;'>
        <div class='navbar-inner' style='padding-top:0;margin-top:0;'>
          <div>
            #{title}
          </div>
        </div>
      </div></div>"
      return html.html_safe
  end
end