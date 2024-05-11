/*
 * Copyright (C) 2014-2024 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.fileoperations.filesystem.smbstreamer;

/** Created by Arpit on 06-07-2015. */
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaze.filemanager.fileoperations.filesystem.cloud.CloudStreamer;

import android.net.Uri;

/**
 * A simple, tiny, nicely embeddable HTTP 1.0 (partially 1.1) server in Java
 *
 * <p>NanoHTTPD version 1.24, Copyright &copy; 2001,2005-2011 Jarno Elonen (elonen@iki.fi,
 * http://iki.fi/elonen/) and Copyright &copy; 2010 Konstantinos Togias (info@ktogias.gr,
 * http://ktogias.gr)
 *
 * <p><b>Features + limitations: </b>
 *
 * <ul>
 *   <li>Only one Java file
 *   <li>Java 1.1 compatible
 *   <li>Released as open source, Modified BSD licence
 *   <li>No fixed config files, logging, authorization etc. (Implement yourself if you need them.)
 *   <li>Supports parameter parsing of GET and POST methods
 *   <li>Supports both dynamic content and file serving
 *   <li>Supports file upload (since version 1.2, 2010)
 *   <li>Supports partial content (streaming)
 *   <li>Supports ETags
 *   <li>Never caches anything
 *   <li>Doesn't limit bandwidth, request time or simultaneous connections
 *   <li>Default code serves files and shows all HTTP parameters and headers
 *   <li>File server supports directory listing, index.html and index.htm
 *   <li>File server supports partial content (streaming)
 *   <li>File server supports ETags
 *   <li>File server does the 301 redirection trick for directories without '/'
 *   <li>File server supports simple skipping for files (continue download)
 *   <li>File server serves also very long files without memory overhead
 *   <li>Contains a built-in list of most common mime types
 *   <li>All header names are converted lowercase so they don't vary between browsers/clients
 * </ul>
 *
 * <p><b>Ways to use: </b>
 *
 * <ul>
 *   <li>Run as a standalone app, serves files and shows requests
 *   <li>Subclass serve() and embed to your own program
 *   <li>Call serveFile() from serve() with your own base directory
 * </ul>
 *
 * See the end of the source file for distribution license (Modified BSD licence)
 */
public abstract class StreamServer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamServer.class);

  // ==================================================
  // API parts
  // ==================================================

  /**
   * Override this to customize the server.
   *
   * <p>(By default, this delegates to serveFile() and allows directory listing.)
   *
   * @param uri Percent-decoded URI without parameters, for example "/index.cgi"
   * @param method "GET", "POST" etc.
   * @param parms Parsed, percent decoded parameters from URI and, in case of POST, data.
   * @param header Header entries, percent decoded
   * @return HTTP response, see class Response for details
   */
  public abstract Response serve(
      String uri, String method, Properties header, Properties parms, Properties files);

  /** HTTP response. Return one of these from serve(). */
  public class Response {
    /** Basic constructor. */
    public Response(String status, String mimeType, StreamSource data) {
      this.status = status;
      this.mimeType = mimeType;
      this.data = data;
    }

    /** Adds given line to the header. */
    public void addHeader(String name, String value) {
      header.put(name, value);
    }

    /** HTTP status code after processing, e.g. "200 OK", HTTP_OK */
    public String status;

    /** MIME type of content, e.g. "text/html" */
    public String mimeType;

    /** Data of the response, may be null. */
    public StreamSource data;

    /** Headers for the HTTP response. Use addHeader() to add lines. */
    public Properties header = new Properties();
  }

  /** Some HTTP response status codes */
  public static final String HTTP_OK = "200 OK",
      HTTP_PARTIALCONTENT = "206 Partial Content",
      HTTP_RANGE_NOT_SATISFIABLE = "416 Requested Range Not Satisfiable",
      HTTP_REDIRECT = "301 Moved Permanently",
      HTTP_FORBIDDEN = "403 Forbidden",
      HTTP_NOTFOUND = "404 Not Found",
      HTTP_BADREQUEST = "400 Bad Request",
      HTTP_INTERNALERROR = "500 Internal Server Error",
      HTTP_NOTIMPLEMENTED = "501 Not Implemented";

  /** Common mime types for dynamic content */
  public static final String MIME_PLAINTEXT = "text/plain",
      MIME_HTML = "text/html",
      MIME_DEFAULT_BINARY = "application/octet-stream",
      MIME_XML = "text/xml";

  // ==================================================
  // Socket & server code
  // ==================================================

  /**
   * Starts a HTTP server to given port.
   *
   * <p>Throws an IOException if the socket is already in use
   */

  // private HTTPSession session;
  public StreamServer(int port, File wwwroot) throws IOException {
    myTcpPort = port;
    this.myRootDir = wwwroot;
    myServerSocket = tryBind(myTcpPort);
    myThread =
        new Thread(
            () -> {
              try {
                while (true) {
                  Socket accept = myServerSocket.accept();
                  new HTTPSession(accept);
                }
              } catch (IOException ioe) {
              }
            });
    myThread.setDaemon(true);
    myThread.start();
  }

  /** Stops the server. */
  public void stop() {
    try {
      myServerSocket.close();
      myThread.join();
    } catch (IOException | InterruptedException e) {
    }
  }

  /**
   * Since CloudStreamServer and Streamer both uses the same port, shutdown the CloudStreamer before
   * acquiring the port.
   *
   * @return ServerSocket
   */
  private ServerSocket tryBind(int port) throws IOException {
    ServerSocket socket;
    try {
      socket = new ServerSocket(port);
    } catch (BindException ifPortIsOccupiedByCloudStreamer) {
      CloudStreamer.getInstance().stop();
      socket = new ServerSocket(port);
    }
    return socket;
  }

  /** Handles one session, i.e. parses the HTTP request and returns the response. */
  private class HTTPSession implements Runnable {
    private InputStream is;
    private final Socket socket;

    public HTTPSession(Socket s) {
      socket = s;
      // mySocket = s;
      Thread t = new Thread(this);
      t.setDaemon(true);
      t.start();
    }

    public void run() {
      try {
        // openInputStream();
        handleResponse(socket);
      } finally {
        if (is != null) {
          try {
            is.close();
            socket.close();
          } catch (IOException e) {
            LOG.warn("failure while closing stream server connection", e);
          }
        }
      }
    }

    private void handleResponse(Socket socket) {
      try {
        is = socket.getInputStream();
        if (is == null) return;

        // Read the first 8192 bytes.
        // The full header should fit in here.
        // Apache's default header limit is 8KB.
        int bufsize = 8192;
        byte[] buf = new byte[bufsize];
        int rlen = is.read(buf, 0, bufsize);
        if (rlen <= 0) return;

        // Create a BufferedReader for parsing the header.
        ByteArrayInputStream hbis = new ByteArrayInputStream(buf, 0, rlen);
        BufferedReader hin = new BufferedReader(new InputStreamReader(hbis, "utf-8"));
        Properties pre = new Properties();
        Properties parms = new Properties();
        Properties header = new Properties();
        Properties files = new Properties();

        // Decode the header into parms and header java properties
        decodeHeader(hin, pre, parms, header);
        LOG.debug(pre.toString());
        LOG.debug("Params: " + parms.toString());
        LOG.debug("Header: " + header.toString());
        String method = pre.getProperty("method");
        String uri = pre.getProperty("uri");

        long size = 0x7FFFFFFFFFFFFFFFL;
        String contentLength = header.getProperty("content-length");
        if (contentLength != null) {
          try {
            size = Integer.parseInt(contentLength);
          } catch (NumberFormatException ex) {
          }
        }

        // We are looking for the byte separating header from body.
        // It must be the last byte of the first two sequential new lines.
        int splitbyte = 0;
        boolean sbfound = false;
        while (splitbyte < rlen) {
          if (buf[splitbyte] == '\r'
              && buf[++splitbyte] == '\n'
              && buf[++splitbyte] == '\r'
              && buf[++splitbyte] == '\n') {
            sbfound = true;
            break;
          }
          splitbyte++;
        }
        splitbyte++;

        // Write the part of body already read to ByteArrayOutputStream f
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        if (splitbyte < rlen) f.write(buf, splitbyte, rlen - splitbyte);

        // While Firefox sends on the first read all the data fitting
        // our buffer, Chrome and Opera sends only the headers even if
        // there is data for the body. So we do some magic here to find
        // out whether we have already consumed part of body, if we
        // have reached the end of the data to be sent or we should
        // expect the first byte of the body at the next read.
        if (splitbyte < rlen) size -= rlen - splitbyte + 1;
        else if (!sbfound || size == 0x7FFFFFFFFFFFFFFFL) size = 0;

        // Now read all the body and write it to f
        buf = new byte[512];
        while (rlen >= 0 && size > 0) {
          rlen = is.read(buf, 0, 512);
          size -= rlen;
          if (rlen > 0) f.write(buf, 0, rlen);
        }

        // Get the raw body as a byte []
        byte[] fbuf = f.toByteArray();

        // Create a BufferedReader for easily reading it as string.
        ByteArrayInputStream bin = new ByteArrayInputStream(fbuf);
        BufferedReader in = new BufferedReader(new InputStreamReader(bin));

        // If the method is POST, there may be parameters
        // in data section, too, read it:
        if (method.equalsIgnoreCase("POST")) {
          String contentType = "";
          String contentTypeHeader = header.getProperty("content-type");
          StringTokenizer st = new StringTokenizer(contentTypeHeader, "; ");
          if (st.hasMoreTokens()) {
            contentType = st.nextToken();
          }

          if (contentType.equalsIgnoreCase("multipart/form-data")) {
            // Handle multipart/form-data
            if (!st.hasMoreTokens())
              sendError(
                  socket,
                  HTTP_BADREQUEST,
                  "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html");
            String boundaryExp = st.nextToken();
            st = new StringTokenizer(boundaryExp, "=");
            if (st.countTokens() != 2)
              sendError(
                  socket,
                  HTTP_BADREQUEST,
                  "BAD REQUEST: Content type is multipart/form-data but boundary syntax error. Usage: GET /example/file.html");
            st.nextToken();
            String boundary = st.nextToken();

            decodeMultipartData(boundary, fbuf, in, parms, files);
          } else {
            // Handle application/x-www-form-urlencoded
            String postLine = "";
            char pbuf[] = new char[512];
            int read = in.read(pbuf);
            while (read >= 0 && !postLine.endsWith("\r\n")) {
              postLine += String.valueOf(pbuf, 0, read);
              read = in.read(pbuf);
              if (Thread.interrupted()) {
                throw new InterruptedException();
              }
            }
            postLine = postLine.trim();
            decodeParms(postLine, parms);
          }
        }

        // Ok, now do the serve()
        Response r = serve(uri, method, header, parms, files);
        if (r == null)
          sendError(
              socket,
              HTTP_INTERNALERROR,
              "SERVER INTERNAL ERROR: Serve() returned a null response.");
        else sendResponse(socket, r.status, r.mimeType, r.header, r.data);

        in.close();
      } catch (IOException ioe) {
        try {
          sendError(
              socket,
              HTTP_INTERNALERROR,
              "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        } catch (Throwable t) {
        }
      } catch (InterruptedException ie) {
        // Thrown by sendError, ignore and exit the thread.
      }
    }

    /** Decodes the sent headers and loads the data into java Properties' key - value pairs */
    private void decodeHeader(
        BufferedReader in, Properties pre, Properties parms, Properties header)
        throws InterruptedException {
      try {
        // Read the request line
        String inLine = in.readLine();
        if (inLine == null) return;
        StringTokenizer st = new StringTokenizer(inLine);
        if (!st.hasMoreTokens())
          sendError(
              socket, HTTP_BADREQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");

        String method = st.nextToken();
        pre.put("method", method);

        if (!st.hasMoreTokens())
          sendError(
              socket, HTTP_BADREQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");

        String uri = st.nextToken();

        // Decode parameters from the URI
        int qmi = uri.indexOf('?');
        if (qmi >= 0) {
          decodeParms(uri.substring(qmi + 1), parms);
          uri = decodePercent(uri.substring(0, qmi));
        } else uri = Uri.decode(uri); // decodePercent(uri);

        // If there's another token, it's protocol version,
        // followed by HTTP headers. Ignore version but parse headers.
        // NOTE: this now forces header names lowercase since they are
        // case insensitive and vary by client.
        if (st.hasMoreTokens()) {
          String line = in.readLine();
          while (line != null && line.trim().length() > 0) {
            int p = line.indexOf(':');
            if (p >= 0)
              header.put(line.substring(0, p).trim().toLowerCase(), line.substring(p + 1).trim());
            line = in.readLine();
          }
        }

        pre.put("uri", uri);
      } catch (IOException ioe) {
        sendError(
            socket, HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
      }
    }

    /** Decodes the Multipart Body data and put it into java Properties' key - value pairs. */
    private void decodeMultipartData(
        String boundary, byte[] fbuf, BufferedReader in, Properties parms, Properties files)
        throws InterruptedException {
      try {
        int[] bpositions = getBoundaryPositions(fbuf, boundary.getBytes());
        int boundarycount = 1;
        String mpline = in.readLine();
        while (mpline != null) {
          if (mpline.indexOf(boundary) == -1)
            sendError(
                socket,
                HTTP_BADREQUEST,
                "BAD REQUEST: Content type is multipart/form-data but next chunk does not start with boundary. Usage: GET /example/file.html");
          boundarycount++;
          Properties item = new Properties();
          mpline = in.readLine();
          while (mpline != null && mpline.trim().length() > 0) {
            int p = mpline.indexOf(':');
            if (p != -1)
              item.put(mpline.substring(0, p).trim().toLowerCase(), mpline.substring(p + 1).trim());
            mpline = in.readLine();
          }
          if (mpline != null) {
            String contentDisposition = item.getProperty("content-disposition");
            if (contentDisposition == null) {
              sendError(
                  socket,
                  HTTP_BADREQUEST,
                  "BAD REQUEST: Content type is multipart/form-data but no content-disposition info found. Usage: GET /example/file.html");
            }
            StringTokenizer st = new StringTokenizer(contentDisposition, "; ");
            Properties disposition = new Properties();
            while (st.hasMoreTokens()) {
              String token = st.nextToken();
              int p = token.indexOf('=');
              if (p != -1)
                disposition.put(
                    token.substring(0, p).trim().toLowerCase(), token.substring(p + 1).trim());
            }
            String pname = disposition.getProperty("name");
            pname = pname.substring(1, pname.length() - 1);

            String value = "";
            if (item.getProperty("content-type") == null) {
              while (mpline != null && mpline.indexOf(boundary) == -1) {
                mpline = in.readLine();
                if (mpline != null) {
                  int d = mpline.indexOf(boundary);
                  if (d == -1) value += mpline;
                  else value += mpline.substring(0, d - 2);
                }
              }
            } else {
              if (boundarycount > bpositions.length)
                sendError(socket, HTTP_INTERNALERROR, "Error processing request");
              int offset = stripMultipartHeaders(fbuf, bpositions[boundarycount - 2]);
              String path = saveTmpFile(fbuf, offset, bpositions[boundarycount - 1] - offset - 4);
              files.put(pname, path);
              value = disposition.getProperty("filename");
              value = value.substring(1, value.length() - 1);
              do {
                mpline = in.readLine();
              } while (mpline != null && mpline.indexOf(boundary) == -1);
            }
            parms.put(pname, value);
          }
        }
      } catch (IOException ioe) {
        sendError(
            socket, HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
      }
    }

    /** Find the byte positions where multipart boundaries start. */
    public int[] getBoundaryPositions(byte[] b, byte[] boundary) {
      int matchcount = 0;
      int matchbyte = -1;
      Vector matchbytes = new Vector();
      for (int i = 0; i < b.length; i++) {
        if (b[i] == boundary[matchcount]) {
          if (matchcount == 0) matchbyte = i;
          matchcount++;
          if (matchcount == boundary.length) {
            matchbytes.addElement(matchbyte);
            matchcount = 0;
            matchbyte = -1;
          }
        } else {
          i -= matchcount;
          matchcount = 0;
          matchbyte = -1;
        }
      }
      int[] ret = new int[matchbytes.size()];
      for (int i = 0; i < ret.length; i++) {
        ret[i] = (Integer) matchbytes.elementAt(i);
      }
      return ret;
    }

    /**
     * Retrieves the content of a sent file and saves it to a temporary file. The full path to the
     * saved file is returned.
     */
    private String saveTmpFile(byte[] b, int offset, int len) {
      String path = "";
      if (len > 0) {
        String tmpdir = System.getProperty("java.io.tmpdir");
        try {
          File temp = File.createTempFile("NanoHTTPD", "", new File(tmpdir));
          OutputStream fstream = new FileOutputStream(temp);
          fstream.write(b, offset, len);
          fstream.close();
          path = temp.getAbsolutePath();
        } catch (Exception e) { // Catch exception if any
          System.err.println("Error: " + e.getMessage());
        }
      }
      return path;
    }

    /** It returns the offset separating multipart file headers from the file's data. */
    private int stripMultipartHeaders(byte[] b, int offset) {
      int i = 0;
      for (i = offset; i < b.length; i++) {
        if (b[i] == '\r' && b[++i] == '\n' && b[++i] == '\r' && b[++i] == '\n') break;
      }
      return i + 1;
    }

    /**
     * Decodes the percent encoding scheme. <br>
     * For example: "an+example%20string" -> "an example string"
     */
    private String decodePercent(String str) throws InterruptedException {
      try {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < str.length(); i++) {
          char c = str.charAt(i);
          switch (c) {
            case '+':
              sb.append(' ');
              break;
            case '%':
              sb.append((char) Integer.parseInt(str.substring(i + 1, i + 3), 16));
              i += 2;
              break;
            default:
              sb.append(c);
              break;
          }
        }
        return sb.toString();
      } catch (Exception e) {
        sendError(socket, HTTP_BADREQUEST, "BAD REQUEST: Bad percent-encoding.");
        return null;
      }
    }

    /**
     * Decodes parameters in percent-encoded URI-format ( e.g.
     * "name=Jack%20Daniels&pass=Single%20Malt" ) and adds them to given Properties. NOTE: this
     * doesn't support multiple identical keys due to the simplicity of Properties -- if you need
     * multiples, you might want to replace the Properties with a Hashtable of Vectors or such.
     */
    private void decodeParms(String parms, Properties p) throws InterruptedException {
      if (parms == null) return;

      StringTokenizer st = new StringTokenizer(parms, "&");
      while (st.hasMoreTokens()) {
        String e = st.nextToken();
        int sep = e.indexOf('=');
        if (sep >= 0)
          p.put(decodePercent(e.substring(0, sep)).trim(), decodePercent(e.substring(sep + 1)));
      }
    }

    /**
     * Returns an error message as a HTTP response and throws InterruptedException to stop further
     * request processing.
     */
    private void sendError(Socket socket, String status, String msg) throws InterruptedException {
      sendResponse(socket, status, MIME_PLAINTEXT, null, null);
      throw new InterruptedException();
    }

    /** Sends given response to the socket. */
    private void sendResponse(
        Socket socket, String status, String mime, Properties header, StreamSource data) {
      try {
        if (status == null) throw new Error("sendResponse(): Status can't be null.");

        OutputStream out = socket.getOutputStream();
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.0 " + status + " \r\n");

        if (mime != null) pw.print("Content-Type: " + mime + "\r\n");

        if (header == null || header.getProperty("Date") == null)
          pw.print("Date: " + gmtFrmt.format(new Date()) + "\r\n");

        if (header != null) {
          Enumeration e = header.keys();
          while (e.hasMoreElements()) {
            String key = (String) e.nextElement();
            String value = header.getProperty(key);
            pw.print(key + ": " + value + "\r\n");
          }
        }

        pw.print("\r\n");
        pw.flush();

        if (data != null) {
          // long pending = data.availableExact();      // This is to support partial sends, see
          // serveFile()
          data.open();
          byte[] buff = new byte[8192];
          int read = 0;
          while ((read = data.read(buff)) > 0) {
            // if(SolidExplorer.LOG)Log.d(CloudUtil.TAG, "Read: "+ read +", pending: "+
            // data.availableExact());
            out.write(buff, 0, read);
          }
        }
        out.flush();
        out.close();
        if (data != null) data.close();

      } catch (IOException ioe) {
        // Couldn't write? No can do.
        try {
          socket.close();
        } catch (Throwable t) {
        }
      }
    }
  }

  private int myTcpPort;
  private final ServerSocket myServerSocket;
  private Thread myThread;
  private File myRootDir;

  /** GMT date formatter */
  private static java.text.SimpleDateFormat gmtFrmt;

  static {
    gmtFrmt = new java.text.SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  /** The distribution licence */
  private static final String LICENCE =
      "Copyright (C) 2001,2005-2011 by Jarno Elonen <elonen@iki.fi>\n"
          + "and Copyright (C) 2010 by Konstantinos Togias <info@ktogias.gr>\n"
          + "\n"
          + "Redistribution and use in source and binary forms, with or without\n"
          + "modification, are permitted provided that the following conditions\n"
          + "are met:\n"
          + "\n"
          + "Redistributions of source code must retain the above copyright notice,\n"
          + "this list of conditions and the following disclaimer. Redistributions in\n"
          + "binary form must reproduce the above copyright notice, this list of\n"
          + "conditions and the following disclaimer in the documentation and/or other\n"
          + "materials provided with the distribution. The name of the author may not\n"
          + "be used to endorse or promote products derived from this software without\n"
          + "specific prior written permission. \n"
          + " \n"
          + "THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR\n"
          + "IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES\n"
          + "OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.\n"
          + "IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,\n"
          + "INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT\n"
          + "NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,\n"
          + "DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY\n"
          + "THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT\n"
          + "(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE\n"
          + "OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.";
}
