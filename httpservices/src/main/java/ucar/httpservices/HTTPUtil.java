/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.httpservices;

import org.apache.http.*;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;

import java.io.*;
import java.util.*;

abstract public class HTTPUtil
{

    //////////////////////////////////////////////////
    // Interceptors

    static abstract public class InterceptCommon
    {
        protected HttpContext context = null;
        protected List<Header> headers = new ArrayList<Header>();
        protected HttpRequest request = null;
        protected HttpResponse response = null;
        protected boolean printheaders = false;

        public void setPrint(boolean tf)
        {
            this.printheaders = tf;
        }

        public void
        clear()
        {
            context = null;
            headers.clear();
            request = null;
            response = null;
        }

        synchronized public HttpResponse getRequest()
        {
            return this.response;
        }

        synchronized public HttpResponse getResponse()
        {
            return this.response;
        }

        synchronized public HttpContext getContext()
        {
            return this.context;
        }

        synchronized public List<Header> getHeaders(String key)
        {
            List<Header> keyh = new ArrayList<Header>();
            for(Header h : this.headers) {
                if(h.getName().equalsIgnoreCase(key.trim()))
                    keyh.add(h);
            }
            return keyh;
        }

        synchronized public List<Header> getHeaders()
        {
            return this.headers;
        }

        public void
        printHeaders()
        {
            if(this.request != null) {
                Header[] hdrs = this.request.getAllHeaders();
                if(hdrs == null) hdrs = new Header[0];
                System.err.println("Request Headers:");
                for(Header h : hdrs)
                    System.err.println(h.toString());
            }
            if(this.response != null) {
                Header[] hdrs = this.response.getAllHeaders();
                if(hdrs == null) hdrs = new Header[0];
                System.err.println("Response Headers:");
                for(Header h : hdrs)
                    System.err.println(h.toString());
            }
            System.err.flush();
        }
    }

    static public class InterceptResponse extends InterceptCommon
        implements HttpResponseInterceptor
    {
        synchronized public void
        process(HttpResponse response, HttpContext context)
            throws HttpException, IOException
        {
            this.response = response;
            this.context = context;
            if(this.printheaders)
                printHeaders();
            else if(this.response != null) {
                Header[] hdrs = this.response.getAllHeaders();
                for(int i = 0;i < hdrs.length;i++)
                    headers.add(hdrs[i]);
            }
        }
    }

    static public class InterceptRequest extends InterceptCommon
        implements HttpRequestInterceptor
    {
        synchronized public void
        process(HttpRequest request, HttpContext context)
            throws HttpException, IOException
        {
            this.request = request;
            this.context = context;
            if(this.printheaders)
                printHeaders();
            else if(this.request != null) {
                Header[] hdrs = this.request.getAllHeaders();
                for(int i = 0;i < hdrs.length;i++)
                    headers.add(hdrs[i]);
            }
        }
    }

    /**
     * Temporary hack to remove Content-Encoding: XXX-Endian headers
     */
    static public class ContentEncodingInterceptor extends InterceptCommon
        implements HttpResponseInterceptor
    {
        synchronized public void
        process(HttpResponse response, HttpContext context)
            throws HttpException, IOException
        {
            if(response == null) return;
            Header[] hdrs = response.getAllHeaders();
            if(hdrs == null) return;
            boolean modified = false;
            for(int i=0;i < hdrs.length;i++) {
                Header h = hdrs[i];
                if(!h.getName().equalsIgnoreCase("content-encoding")) continue;
                String value = h.getValue();
                if(value.trim().toLowerCase().endsWith("-endian")) {
                    hdrs[i] = new BasicHeader("X-Content-Encoding",value);
                    modified = true;
                }
            }
            if(modified)
                response.setHeaders(hdrs);
            // Similarly, suppress encoding for Entity
            HttpEntity entity= response.getEntity();
            Header ceheader = entity.getContentEncoding();
            if (ceheader != null) {
                String value = ceheader.getValue();
                if(value.trim().toLowerCase().endsWith("-endian")) {
                    int x = 0;//entity.setContentEncoding(new BasicHeader("Content-Encoding","Identity"));
                }
            }
        }
    }


    //////////////////////////////////////////////////
    // Misc.

    static public byte[]
    readbinaryfile(InputStream stream)
        throws IOException
    {
        // Extract the stream into a bytebuffer
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] tmp = new byte[1 << 16];
        for(;;) {
            int cnt;
            cnt = stream.read(tmp);
            if(cnt <= 0) break;
            bytes.write(tmp, 0, cnt);
        }
        return bytes.toByteArray();
    }

}
