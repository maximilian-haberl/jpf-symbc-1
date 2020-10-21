/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import gov.nasa.jpf.vm.LocalVarInfo;
import gov.nasa.jpf.vm.MethodInfo;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author mxl
 */
public class TestCase {

  public Map<LocalVarInfo, Object> args;
  public MethodInfo method;
  public Object returnValue;
  public boolean didThrow;

  public TestCase(MethodInfo mi) {
    args = new HashMap<>();
    this.method = mi;
    didThrow = false;
  }
}
