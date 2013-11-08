/*
 *
 *  * Copyright 1998-2013 University Corporation for Atmospheric Research/Unidata
 *  *
 *  *  Portions of this software were developed by the Unidata Program at the
 *  *  University Corporation for Atmospheric Research.
 *  *
 *  *  Access and use of this software shall impose the following obligations
 *  *  and understandings on the user. The user is granted the right, without
 *  *  any fee or cost, to use, copy, modify, alter, enhance and distribute
 *  *  this software, and any derivative works thereof, and its supporting
 *  *  documentation for any purpose whatsoever, provided that this entire
 *  *  notice appears in all copies of the software, derivative works and
 *  *  supporting documentation.  Further, UCAR requests that the user credit
 *  *  UCAR/Unidata in any publications that result from the use of this
 *  *  software or in any product that includes this software. The names UCAR
 *  *  and/or Unidata, however, may not be used in any advertising or publicity
 *  *  to endorse or promote any products or commercial entity unless specific
 *  *  written permission is obtained from UCAR/Unidata. The user also
 *  *  understands that UCAR/Unidata is not obligated to provide the user with
 *  *  any support, consulting, training or assistance of any kind with regard
 *  *  to the use, operation and performance of this software nor to provide
 *  *  the user with any updates, revisions, new versions or "bug fixes."
 *  *
 *  *  THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 *  *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  *  DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 *  *  INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 *  *  FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *  *  NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 *  *  WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 */

package thredds.motherlode;

import org.jdom2.input.SAXBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import ucar.nc2.util.IO;
import ucar.nc2.util.net.HTTPMethod;
import ucar.nc2.util.net.HTTPSession;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;
import java.util.Collection;


/**
 * test encoding works
 *
 * @author caron
 * @since 11/7/13
 */
@RunWith(Parameterized.class)

public class TestMotherlodeEncoding {

  @Parameterized.Parameters
 	public static Collection<Object[]> getTestParameters(){
 		return Arrays.asList(new Object[][]{
            {"/wcs/grib/NCEP/GFS/Global_2p5deg/best?service=WCS&version=1.0.0&request=GetCapabilities"},
            {"/wms/grib/NCEP/GFS/Global_2p5deg/best?request=GetCapabilities&service=WMS&version=1.3.0"},
    });
 	}

  String location;
  public TestMotherlodeEncoding(String location) {
    this.location = location;
  }

  @Test
  public void readWmsCapabilities() {
    String endpoint= TestMotherlodePing.server + location;
    System.out.printf("GetCapabilities req = '%s'%n", endpoint);

    HTTPSession session = null;
    try {
      session = new HTTPSession(endpoint);
      HTTPMethod method = HTTPMethod.Get(session);
      int statusCode = method.execute();

      assert (statusCode == 200) : statusCode;

      try {
        SAXBuilder builder = new SAXBuilder();
        org.jdom2.Document tdoc = builder.build(method.getResponseAsStream());
        org.jdom2.Element root = tdoc.getRootElement();

      } catch (Throwable t) {
        // if fail, go find where it barfs
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        IO.copy(method.getResponseAsStream(), bout);
        byte[] ba = bout.toByteArray();
        isValidUTF8(ba);
        assert false;
      }

    } catch (Exception e) {
      e.printStackTrace();
      assert false;

    } finally {
      if (session != null) session.close();
    }
  }

  // jesus youd think they could tell you where the problem is
  public static void isValidUTF8(byte[] input) {

    CharsetDecoder cs = Charset.forName("UTF-8").newDecoder();
    ByteBuffer bb = ByteBuffer.wrap(input);

    for (int pos = 0; pos < input.length; pos++) {
      bb.limit(pos);
      try {
        cs.decode(bb);
      } catch (CharacterCodingException e) {
        System.out.printf("barf at %d %s%n", pos, e.getMessage());

        bb.limit(input.length);
        int len = 100;
        byte[] dst = new byte[len];
        for (int i = 0; i < len; i++)
          dst[i] = bb.get(pos+i);
        String s = new String(dst);
        System.out.printf("before = %s%n", s);
        break;
      }
    }
  }
}