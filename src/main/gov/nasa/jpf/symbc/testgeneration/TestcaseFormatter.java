/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.vm.MethodInfo;
import java.io.Writer;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author mxl
 */
public abstract class TestcaseFormatter {

  /**
   * Creates a String representation of the given test case and prints it to the
   * PrintWriter pw
   *
   * @param test test case that should be formatted
   * @param writer PrintWriter the test case should be written to
   */
  public abstract void format(TestCase test, Writer writer);

  /**
   * Creates and returns a String representation of the given test case
   *
   * @param test test case that should be formatted
   * @return String representation of the test case
   */
  public abstract String format(TestCase test);
  
  public abstract void format(Map<MethodInfo, Set<TestCase>> tests, Writer writer);
  
  public TestcaseFormatter(Config config) {
  }
}
