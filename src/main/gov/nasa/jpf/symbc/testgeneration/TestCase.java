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
import java.util.Objects;

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

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 71 * hash + Objects.hashCode(this.args);
    hash = 71 * hash + Objects.hashCode(this.method);
    hash = 71 * hash + Objects.hashCode(this.returnValue);
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final TestCase other = (TestCase) obj;
    if (!Objects.equals(this.args, other.args)) {
      return false;
    }
    if (!Objects.equals(this.method, other.method)) {
      return false;
    }
    if (!Objects.equals(this.returnValue, other.returnValue)) {
      return false;
    }
    return true;
  }

}
