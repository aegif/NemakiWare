/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.impl;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.activation.MimetypesFileTypeMap;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class MimeTypes {

    private static final String OCTET_STREAM = "application/octet-stream";
    private static final MimetypesFileTypeMap TYPE_MAP = new MimetypesFileTypeMap();
    private static final Map<String, String> MIME2EXT = new HashMap<String, String>(180);

    private MimeTypes() {
    }

    static {
        // MIME type to extension
        MIME2EXT.put("application/octet-stream", "");
        MIME2EXT.put("application/envoy", "evy");
        MIME2EXT.put("application/epub+zip", "epub");
        MIME2EXT.put("application/fractals", "fif");
        MIME2EXT.put("application/futuresplash", "spl");
        MIME2EXT.put("application/hta", "hta");
        MIME2EXT.put("application/internet-shortcut", "url");
        MIME2EXT.put("application/java-archive", "jar");
        MIME2EXT.put("application/java-serialized-object", "ser");
        MIME2EXT.put("application/java-vm", "class");
        MIME2EXT.put("application/javascript", "js");
        MIME2EXT.put("application/json", "json");
        MIME2EXT.put("application/mac-binhex40", "hqx");
        MIME2EXT.put("application/msword", "doc");
        MIME2EXT.put("application/oda", "oda");
        MIME2EXT.put("application/olescript", "axs");
        MIME2EXT.put("application/onenote", "onetoc");
        MIME2EXT.put("application/pdf", "pdf");
        MIME2EXT.put("application/pics-rules", "prf");
        MIME2EXT.put("application/pkcs10", "p10");
        MIME2EXT.put("application/pkix-cert", "cer");
        MIME2EXT.put("application/pkix-crl", "crl");
        MIME2EXT.put("application/postscript", "ps");
        MIME2EXT.put("application/rtf", "rtf");
        MIME2EXT.put("application/vnd.android.package-archive", "apk");
        MIME2EXT.put("application/vnd.apple.keynote", "key");
        MIME2EXT.put("application/vnd.apple.numbers", "numbers");
        MIME2EXT.put("application/vnd.apple.pages", "pages");
        MIME2EXT.put("application/vnd.framemaker", "fm");
        MIME2EXT.put("application/vnd.ms-excel", "xls");
        MIME2EXT.put("application/vnd.ms-pkicertstore", "sst");
        MIME2EXT.put("application/vnd.ms-pkiseccat", "cat");
        MIME2EXT.put("application/vnd.ms-pkistl", "stl");
        MIME2EXT.put("application/vnd.ms-powerpoint", "ppt");
        MIME2EXT.put("application/vnd.ms-project", "mpp");
        MIME2EXT.put("application/vnd.ms-works", "wps");
        MIME2EXT.put("application/vnd.oasis.opendocument.chart", "odc");
        MIME2EXT.put("application/vnd.oasis.opendocument.chart-template", "otc");
        MIME2EXT.put("application/vnd.oasis.opendocument.database", "odb");
        MIME2EXT.put("application/vnd.oasis.opendocument.formula", "odf");
        MIME2EXT.put("application/vnd.oasis.opendocument.formula-template", "odft");
        MIME2EXT.put("application/vnd.oasis.opendocument.graphics", "odg");
        MIME2EXT.put("application/vnd.oasis.opendocument.graphics-template", "otg");
        MIME2EXT.put("application/vnd.oasis.opendocument.image", "odi");
        MIME2EXT.put("application/vnd.oasis.opendocument.image-template", "oti");
        MIME2EXT.put("application/vnd.oasis.opendocument.presentation", "odp");
        MIME2EXT.put("application/vnd.oasis.opendocument.presentation-template", "otp");
        MIME2EXT.put("application/vnd.oasis.opendocument.spreadsheet", "ods");
        MIME2EXT.put("application/vnd.oasis.opendocument.spreadsheet-template", "ots");
        MIME2EXT.put("application/vnd.oasis.opendocument.text", "odt");
        MIME2EXT.put("application/vnd.oasis.opendocument.text-master", "odm");
        MIME2EXT.put("application/vnd.oasis.opendocument.text-template", "ott");
        MIME2EXT.put("application/vnd.oasis.opendocument.text-web", "oth");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.presentationml.slideshow", "ppsx");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.presentationml.template", "potx");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.spreadsheetml.template", "xltx");
        MIME2EXT.put("application/winhlp", "hlp");
        MIME2EXT.put("application/x-cdf", "cdf");
        MIME2EXT.put("application/x-compress", "z");
        MIME2EXT.put("application/x-compressed", "tgz");
        MIME2EXT.put("application/x-cpio", "cpio");
        MIME2EXT.put("application/x-csh", "csh");
        MIME2EXT.put("application/x-director", "dxr");
        MIME2EXT.put("application/x-dvi", "dvi");
        MIME2EXT.put("application/x-gtar", "gtar");
        MIME2EXT.put("application/x-gzip", "gz");
        MIME2EXT.put("application/x-hdf", "hdf");
        MIME2EXT.put("application/x-internet-signup", "isp");
        MIME2EXT.put("application/x-iphone", "iii");
        MIME2EXT.put("application/x-iwork-keynote-sffkey", "key");
        MIME2EXT.put("application/x-iwork-numbers-sffnumber", "numbers");
        MIME2EXT.put("application/x-iwork-pages-sffpages", "pages");
        MIME2EXT.put("application/x-javascript", "js");
        MIME2EXT.put("application/x-latex", "latex");
        MIME2EXT.put("application/x-msaccess", "mdb");
        MIME2EXT.put("application/x-mscardfile", "crd");
        MIME2EXT.put("application/x-msclip", "clp");
        MIME2EXT.put("application/x-msdownload", "dll");
        MIME2EXT.put("application/x-msmediaview", "mvb");
        MIME2EXT.put("application/x-msmetafile", "wmf");
        MIME2EXT.put("application/x-mspublisher", "pub");
        MIME2EXT.put("application/x-msschedule", "scd");
        MIME2EXT.put("application/x-msterminal", "trm");
        MIME2EXT.put("application/x-mswrite", "wri");
        MIME2EXT.put("application/x-perfmon", "pmw");
        MIME2EXT.put("application/x-pkcs12", "pfx");
        MIME2EXT.put("application/x-pkcs12v", "p12");
        MIME2EXT.put("application/x-pkcs7-certificates", "p7b");
        MIME2EXT.put("application/x-pkcs7-certreqresp", "p7r");
        MIME2EXT.put("application/x-pkcs7-mime", "p7m");
        MIME2EXT.put("application/x-pkcs7-signature", "p7s");
        MIME2EXT.put("application/x-sh", "sh");
        MIME2EXT.put("application/x-shar", "shar");
        MIME2EXT.put("application/x-shockwave-flash", "swf");
        MIME2EXT.put("application/x-stuffit", "sit");
        MIME2EXT.put("application/x-tar", "tar");
        MIME2EXT.put("application/x-tcl", "tcl");
        MIME2EXT.put("application/x-tex", "tex");
        MIME2EXT.put("application/x-texinfo", "texinfo");
        MIME2EXT.put("application/x-troff-man", "man");
        MIME2EXT.put("application/x-troff-me", "me");
        MIME2EXT.put("application/x-troff-ms", "ms");
        MIME2EXT.put("application/x-troff", "tr");
        MIME2EXT.put("application/x-ustar", "ustar");
        MIME2EXT.put("application/x-wais-source", "src");
        MIME2EXT.put("application/x-x509-ca-cert", "cer");
        MIME2EXT.put("application/ynd.ms-pkipko", "vpko");
        MIME2EXT.put("application/zip", "zip");
        MIME2EXT.put("audio/basic", "snd");
        MIME2EXT.put("audio/flac", "flac");
        MIME2EXT.put("audio/mp4", "m4a");
        MIME2EXT.put("audio/mid", "mid");
        MIME2EXT.put("audio/midi", "mid");
        MIME2EXT.put("audio/mpeg", "mp3");
        MIME2EXT.put("audio/ogg", "ogg");
        MIME2EXT.put("audio/webm", "webm");
        MIME2EXT.put("audio/x-aiff", "aif");
        MIME2EXT.put("audio/x-m4a", "m4a");
        MIME2EXT.put("audio/x-mpegurl", "m3u");
        MIME2EXT.put("audio/x-ms-wax", "wax");
        MIME2EXT.put("audio/x-ms-wma", "wma");
        MIME2EXT.put("audio/x-pn-realaudio", "ram");
        MIME2EXT.put("audio/x-wav", "wav");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.wordprocessingml.template", "doct");
        MIME2EXT.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        MIME2EXT.put("image/bmp", "bmp");
        MIME2EXT.put("image/cis-cod", "cod");
        MIME2EXT.put("image/gif", "gif");
        MIME2EXT.put("image/ief", "ief");
        MIME2EXT.put("image/jpeg", "jpeg");
        MIME2EXT.put("image/pipeg", "jfif");
        MIME2EXT.put("image/png", "png");
        MIME2EXT.put("image/svg+xml", "svg");
        MIME2EXT.put("image/tiff", "tiff");
        MIME2EXT.put("image/webp", "webp");
        MIME2EXT.put("image/x-cmu-raster", "ras");
        MIME2EXT.put("image/x-cmx", "cmx");
        MIME2EXT.put("image/x-icon", "ico");
        MIME2EXT.put("image/x-portable-anymap", "pnm");
        MIME2EXT.put("image/x-portable-bitmap", "pbm");
        MIME2EXT.put("image/x-portable-graymap", "pgm");
        MIME2EXT.put("image/x-portable-pixmap", "ppm");
        MIME2EXT.put("image/x-rgb", "rgb");
        MIME2EXT.put("image/x-xbitmap", "xbm");
        MIME2EXT.put("image/x-xpixmap", "xpm");
        MIME2EXT.put("image/x-xwindowdump", "xwd");
        MIME2EXT.put("message/rfc822", "mhtml");
        MIME2EXT.put("text/css", "css");
        MIME2EXT.put("text/csv", "csv");
        MIME2EXT.put("text/comma-separated-values", "csv");
        MIME2EXT.put("text/html", "html");
        MIME2EXT.put("text/iuls", "uls");
        MIME2EXT.put("text/markdown", "md");
        MIME2EXT.put("text/plain", "txt");
        MIME2EXT.put("text/richtext", "rtx");
        MIME2EXT.put("text/scriptlet", "sct");
        MIME2EXT.put("text/tab-separated-values", "tsv");
        MIME2EXT.put("text/webviewhtml", "htt");
        MIME2EXT.put("text/x-component", "htc");
        MIME2EXT.put("text/x-setext", "etx");
        MIME2EXT.put("text/x-vcard", "vcf");
        MIME2EXT.put("text/xml", "xml");
        MIME2EXT.put("video/mp4", "mp4");
        MIME2EXT.put("video/mpeg", "mpeg");
        MIME2EXT.put("video/mpegv", "mpe");
        MIME2EXT.put("video/ogg", "ogv");
        MIME2EXT.put("video/quicktime", "mov");
        MIME2EXT.put("video/quicktime", "qt");
        MIME2EXT.put("video/webm", "webm");
        MIME2EXT.put("video/x-f4v", "f4v");
        MIME2EXT.put("video/x-fli", "fli");
        MIME2EXT.put("video/x-flv", "flv");
        MIME2EXT.put("video/x-la-asf", "lsf");
        MIME2EXT.put("video/x-m4v", "m4v");
        MIME2EXT.put("video/x-ms-asf", "asf");
        MIME2EXT.put("video/x-msvideo", "avi");
        MIME2EXT.put("video/x-sgi-movie", "movie");
        MIME2EXT.put("x-world/x-vrml", "vrml");
    }

    /**
     * Returns the MIME type for file extension.
     */
    public static String getMIMEType(String ext) {
        if (ext == null) {
            return OCTET_STREAM;
        }

        int x = ext.lastIndexOf('.');
        if (x > -1) {
            ext = ext.substring(x + 1);
        }

        return TYPE_MAP.getContentType("x." + ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Returns the MIME type for a file.
     */
    public static String getMIMEType(File file) {
        if (file == null) {
            return OCTET_STREAM;
        }

        return TYPE_MAP.getContentType(file);
    }

    /**
     * Guesses a extension from a MIME type.
     */
    public static String getExtension(String mimeType) {
        if (mimeType == null) {
            return "";
        }

        int x = mimeType.indexOf(';');
        if (x > -1) {
            mimeType = mimeType.substring(0, x);
        }
        mimeType = mimeType.trim().toLowerCase(Locale.ENGLISH);

        String extension = MIME2EXT.get(mimeType);
        return (extension == null || extension.length() == 0) ? "" : "." + extension;
    }
}
