/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import java.io.Writer;

/**
 *
 * @author mxl
 */
public interface TestcaseFormatter {

    /**
     * Creates a String representation of the given test case and prints it to
     * the PrintWriter pw
     *
     * @param testCase test case that should be formatted
     * @param pw PrintWriter the test case should be written to
     */
    public void format(TestCase test, Writer writer);

    /**
     * Creates and returns a String representation of the given test case
     *
     * @param test test case that should be formatted
     * @return String representation of the test case
     */
    public String format(TestCase test);
  }
