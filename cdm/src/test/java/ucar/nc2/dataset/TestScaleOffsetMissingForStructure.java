/*
 * Copyright 1997-2007 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package ucar.nc2.dataset;
import junit.framework.*;

import ucar.ma2.*;
import ucar.nc2.*;

import java.io.IOException;

public class TestScaleOffsetMissingForStructure extends TestCase {
  //String topDir = ucar.nc2.TestAll.upcShareTestDataDir+ "point/netcdf/";
  public TestScaleOffsetMissingForStructure( String name) {
    super(name);
  }

  public void testNetcdfFile() throws IOException, InvalidRangeException {
    NetcdfFile ncfile = NetcdfDataset.openFile(TestAll.cdmTestDataDir +"testScaleRecord.nc", null);
    Variable v = ncfile.findVariable("testScale");
    assert null != v;
    assert v.getDataType() == DataType.SHORT;

    Array data = v.read();
    Index ima = data.getIndex();
    short val = data.getShort( ima);
    assert val == -999;

    assert v.getUnitsString().equals("m");
    v.addAttribute( new Attribute("units", "meters"));
    assert v.getUnitsString().equals("meters");

    ncfile.sendIospMessage(NetcdfFile.IOSP_MESSAGE_ADD_RECORD_STRUCTURE);
    Structure s = (Structure) ncfile.findVariable("record");
    assert (s != null);

    Variable v2 = s.findVariable("testScale");
    Attribute att = v2.findAttribute("units");
    assert att.getStringValue().equals("meters");
    assert v2.getUnitsString().equals("meters");

    StructureData sdata = s.readStructure(0);
    StructureMembers.Member m = sdata.findMember("testScale");
    assert null != m;
    assert m.getUnitsString().equals("meters");

    double dval = sdata.convertScalarDouble( m.getName());
    assert dval == -999.0;

    int count = 0;
    StructureDataIterator siter = s.getStructureIterator();
    while (siter.hasNext()) {
      sdata = siter.next();
      m = sdata.findMember("testScale");
      assert m.getUnitsString().equals("meters");

      assert null != m;
      dval = sdata.convertScalarDouble( m.getName());
      double expect = (count == 0) ? -999.0 : 13.0;
      assert dval == expect : dval + "!="+ expect ;
      count++;
    }

    ncfile.close();
  }

  public void testNetcdfDataset() throws IOException, InvalidRangeException {
    NetcdfDataset ncfile = NetcdfDataset.openDataset(TestAll.cdmTestDataDir +"testScaleRecord.nc");
    VariableDS v = (VariableDS) ncfile.findVariable("testScale");
    assert null != v;
    assert v.getDataType() == DataType.FLOAT;

    Array data = v.read();
    Index ima = data.getIndex();
    float val = data.getFloat( ima);
    assert v.isMissing(val);
    assert Float.isNaN(val) : val;

    ncfile.sendIospMessage(NetcdfFile.IOSP_MESSAGE_ADD_RECORD_STRUCTURE);
    Structure s = (Structure) ncfile.findVariable("record");
    assert (s != null);

    VariableEnhanced vm = (VariableEnhanced) s.findVariable("testScale");
    Array vmData = vm.read();
    if (vmData.hasNext()) {
      float vmval = vmData.nextFloat();
      assert vm.isMissing(vmval);
      assert Float.isNaN(vmval) : vmval;
    }

    StructureData sdata = s.readStructure(0);
    StructureMembers.Member m = sdata.findMember("testScale");
    assert null != m;

    // heres the problem where StructureData.getScalarXXX doesnt use enhanced values
    float dval = sdata.getScalarFloat( m.getName());
    assert Float.isNaN(dval) : dval;

    int count = 0;
    StructureDataIterator siter = s.getStructureIterator();
    while (siter.hasNext()) {
      sdata = siter.next();
      m = sdata.findMember("testScale");
      assert null != m;

      dval = sdata.getScalarFloat( m);
      if (count == 0)
        assert Float.isNaN(dval) : dval;
      else
        assert TestAll.closeEnough(dval, 1040.8407) : dval;
      count++;
    }

    ncfile.close();
  }

  public void testNetcdfDatasetAttributes() throws IOException, InvalidRangeException {
    NetcdfDataset ncfile = NetcdfDataset.openDataset(TestAll.cdmTestDataDir +"testScaleRecord.nc");
    VariableDS v = (VariableDS) ncfile.findVariable("testScale");
    assert null != v;
    assert v.getDataType() == DataType.FLOAT;

    assert v.getUnitsString().equals("m");
    v.addAttribute( new Attribute("units", "meters"));
    assert v.getUnitsString().equals("meters");

    ncfile.sendIospMessage(NetcdfFile.IOSP_MESSAGE_ADD_RECORD_STRUCTURE);
    Structure s = (Structure) ncfile.findVariable("record");
    assert (s != null);

    Variable v2 = s.findVariable("testScale");
    assert v2.getUnitsString().equals("meters");
    assert v2.getDataType() == DataType.FLOAT;

    StructureData sdata = s.readStructure(0);
    StructureMembers.Member m = sdata.findMember("testScale");
    assert null != m;
    assert m.getUnitsString().equals("meters") : m.getUnitsString();
    assert m.getDataType() == DataType.FLOAT;

    StructureDataIterator siter = s.getStructureIterator();
    while (siter.hasNext()) {
      sdata = siter.next();
      m = sdata.findMember("testScale");
      assert null != m;
      assert m.getUnitsString().equals("meters");
    }

    ncfile.close();
  }
}