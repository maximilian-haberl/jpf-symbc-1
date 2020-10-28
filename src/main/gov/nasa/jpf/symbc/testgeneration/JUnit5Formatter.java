/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.LocalVarInfo;
import gov.nasa.jpf.vm.Types;
import java.io.CharArrayWriter;
import java.io.Writer;
import java.util.Map;
import java.util.logging.Level;

/**
 *
 * @author mxl
 */
public class JUnit5Formatter extends TestcaseFormatter {

  private final String NL = System.lineSeparator();
  private int testCaseCount = 0;
  private boolean fullArgs, assertMessage;
  private int indents;
  private JPFLogger logger = JPF.getLogger("gov.nasa.jpf.symbc.testgeneration.JUnit5Formatter");

  public JUnit5Formatter(Config config) {
    super(config);

    fullArgs = config.getBoolean("formatter.fullArgs", false);
    assertMessage = config.getBoolean("formatter.message", false);
  }

  @Override
  public void format(TestCase test, Writer writer) {
    formatTestCase(new IndentableWriter(writer), test);
  }

  @Override
  public String format(TestCase test) {
    CharArrayWriter cw = new CharArrayWriter();
    formatTestCase(new IndentableWriter(cw), test);
    return cw.toString();
  }

  /**
   * Formats a call to the method under test onto the writer. For dynamic
   * methods it is assumed that the instance is called 'instance' For static
   * methods: <code>[methodname]([args...])</code> For dynamic methods:
   * <code>instance.[methodname]([args...])</code>
   *
   * @param writer
   * @param test
   * @return the writer onto which the call was written
   */
  protected IndentableWriter formatCall(IndentableWriter writer, TestCase test) {
    boolean dynamic = !test.method.isStatic();
    int start = 0;

    if (dynamic) {
      writer.append("instance.");
      start = 1;
    }

    writer.append(test.method.getName()).append("(");
    LocalVarInfo[] arguments = test.method.getArgumentLocalVars();

    for (int i = start; i < arguments.length; i++) {
      LocalVarInfo var = arguments[i];
      if (i > start) {
        writer.append(", ");
      }

      if (Types.isReference(var.getSignature())) {
        //for reference types we assume they have been created beforehand with the same name as the argument
        writer.append(var.getName());
      } else {
        //for basic types we put the value directly into the call
        writer.append(test.args.get(var));
      }
    }

    writer.append(")");

    return writer;
  }

  protected IndentableWriter formatDeclaration(IndentableWriter writer, TestCase test) {
    writer.indent(indents).append("//Testing method ").append(test.method.getName()).append(NL);
    writer.indent(indents).append("@Test").append(NL);
    writer.indent(indents).append("public void test").append(testCaseCount++).append("(){").append(NL);
    return writer;
  }

  protected IndentableWriter formatAssertMessage(IndentableWriter writer, TestCase test) {
    writer.append("\"");
    writer.append("Ein Test für die Methode ").append(test.method.getName()).append(" ist fehlgeschlagen.");

    if (fullArgs) {
      //append the values of all variables
      writer.append(" Input:\\n");

      int start = 0;
      if (!test.method.isStatic()) {
        start = 1;
      }

      LocalVarInfo[] lvi = test.method.getArgumentLocalVars();
      for (int i = 0; i < lvi.length; i++) {
        LocalVarInfo var = lvi[i];
        if (i > start) {
          writer.append("\\n");
        }

        writer.append(var.getName()).append(":\\t").append(test.args.get(var));

      }
    }

    return writer.append("\"");
  }

  protected void formatArray(IndentableWriter writer, Object o) {
    if (!o.getClass().isArray()) {
      throw new IllegalArgumentException("Cannot format " + o.getClass().getCanonicalName() + " as an array");
    }

    Object[] array = (Object[]) o;

    writer.append("{");
    for (int i = 0; i < array.length; i++) {
      if (i > 0) {
        writer.append(", ");
      }
      writer.append(array[i]);
    }

    writer.append("}");
  }

  protected void formatReference(IndentableWriter writer, Object o) {
    writer.append("null");
    if (logger.isLoggable(Level.WARNING)) {
      logger.log(Level.WARNING, "Reference types other than arrays are not supported!");
    }
  }

  protected void formatReferenceTypes(IndentableWriter writer, TestCase test) {
    for (Map.Entry<LocalVarInfo, Object> entry : test.args.entrySet()) {
      LocalVarInfo var = entry.getKey();
      Object val = entry.getValue();

      if (Types.isReference(var.getSignature())) {
        writer.indent(indents).append(var.getType()).append(" ").append(var.getName()).append(" = ");

        if (Types.isArray(var.getSignature())) {
          formatArray(writer, val);
        } else {
          formatReference(writer, val);
        }

        writer.append(";").append(NL);
      }
    }
  }

  /**
   * Formats the test case body for a non void method, which did not throw an
   * exception.
   *
   * @param writer
   * @param test
   */
  protected void formatDefaultBody(IndentableWriter writer, TestCase test) {
    writer.indent(indents).append(test.method.getReturnTypeName()).append(" expected = ");
    if (Types.isArray(test.method.getReturnType())) {
      formatArray(writer, test.returnValue);
    } else if (Types.isBasicType(test.method.getReturnType())) {
      writer.append(test.returnValue);
    }
    writer.append(";").append(NL);

    writer.indent(indents).append(test.method.getReturnTypeName()).append(" result = ");
    formatCall(writer, test).append(";").append(NL);

    writer.indent(indents).append("assertEquals(expected, result");
    if (assertMessage) {
      writer.append(", ");
      formatAssertMessage(writer, test).append(");").append(NL);
    } else {
      writer.append(");").append(NL);
    }
  }

  protected void formatThrows(IndentableWriter writer, TestCase test) {
    //method threw an exception
    ElementInfo exception = (ElementInfo) test.returnValue;

    //formatting the assertThrows
    writer.indent(indents).append("assertThrows(").append(NL);
    writer.indent(indents + 2).append(exception.getClassInfo().getName()).append(".class,").append(NL);
    writer.indent(indents + 2).append("() -> {");
    formatCall(writer, test).append("}");

    if (assertMessage) {
      writer.append(",").append(NL).indent(indents + 2);
      formatAssertMessage(writer, test);
    }
    writer.append(NL).indent(indents).append(");").append(NL);
  }

  /**
   * Formats a void method that did not throw an exception in the oracle.
   *
   * @param writer
   * @param test
   */
  protected void formatVoid(IndentableWriter writer, TestCase test) {
    //no assertion , because no return value -> test can only fail if method throws exception
    writer.indent(indents);
    formatCall(writer, test).append(";").append(NL);
  }

  protected void formatTestCase(IndentableWriter writer, TestCase test) {
    StringBuilder builder = new StringBuilder();
    String className = test.method.getClassName();
    boolean dynamic = !test.method.isStatic();
    indents = 1;

    formatDeclaration(writer, test);

    //one additional indent for the method body
    indents++;

    formatReferenceTypes(writer, test);

    //creating an instance for dynamic methods
    if (dynamic) {
      writer.indent(indents).append(className).append(" instance = new ").append(className).append("();").append(NL);
    }

    if (test.didThrow) {
      formatThrows(writer, test);
    } else if (test.method.getReturnTypeCode() != Types.T_VOID) {
      formatDefaultBody(writer, test);
    } else {
      formatVoid(writer, test);
    }

    //end of method body
    indents--;
    writer.indent(indents).append("}").append(NL).append(NL);
  }

  /**
   * resets the test case counter to 0. The next test case formatted by this
   * formatter will be called test0.
   */
  public void resetTestCounter() {
    testCaseCount = 0;
  }

}
