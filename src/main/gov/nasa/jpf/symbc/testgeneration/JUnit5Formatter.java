/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.LocalVarInfo;
import gov.nasa.jpf.vm.Types;
import java.io.CharArrayWriter;
import java.io.Writer;

/**
 *
 * @author mxl
 */
public class JUnit5Formatter implements TestcaseFormatter {

  private final String NL = System.lineSeparator();
  private int testCaseCount = 0;
  private boolean fullArgs;

  @Override
  public void format(TestCase test, Writer pw) {
    IndentableWriter writer = new IndentableWriter(pw);
    formatInstance(writer, test);
  }

  @Override
  public String format(TestCase test) {
    CharArrayWriter cw = new CharArrayWriter();
    IndentableWriter writer = new IndentableWriter(cw);
    formatInstance(writer, test);
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
        writer.append(",");
      }
      writer.append(test.args.get(var));
    }

    writer.append(")");

    return writer;
  }

  private IndentableWriter formatComment(IndentableWriter writer, TestCase test) {
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

  private void formatInstance(IndentableWriter writer, TestCase test) {
    StringBuilder builder = new StringBuilder();
    String className = test.method.getClassName();
    boolean dynamic = !test.method.isStatic();
    int indent = 1;

    writer.indent(indent).append("//Testing method ").append(test.method.getName()).append(NL);
    writer.indent(indent).append("@Test").append(NL);

    writer.indent(indent).append("public void test").append(testCaseCount++).append("(){").append(NL);

    //we are writing the method body now
    indent++;

    if (dynamic) {
      writer.indent(indent).append(className).append(" instance = new ").append(className).append("();").append(NL);
    }

    if (test.didThrow) {
      //method threw an exception
      ElementInfo exception = (ElementInfo) test.returnValue;

      //formatting the assertThrows
      writer.indent(indent).append("assertThrows(").append(NL);
      writer.indent(indent + 2).append(exception.getClassInfo().getName()).append(".class,").append(NL);
      writer.indent(indent + 2).append("() -> {");
      if (dynamic) {
        builder.append("instance.");
      }
      formatCall(writer, test).append("},").append(NL);
      writer.indent(indent + 2);
      formatComment(writer, test).append(NL);
      writer.indent(indent).append(");").append(NL);
    } else if (test.method.getReturnTypeCode() != Types.T_VOID) {
      //non void method that did not throw an exception
      writer.indent(indent).append(test.method.getReturnTypeName()).append(" expected = ").append(test.returnValue).append(";").append(NL);

      writer.indent(indent).append(test.method.getReturnTypeName()).append(" result = ");
      if (dynamic) {
        builder.append("instance.");
      }
      formatCall(writer, test).append(";").append(NL);

      writer.indent(indent).append("assertEquals(expected, result, ");
      formatComment(writer, test).append(");").append(NL);

    } else {
      //void method that did not throw an exception
      writer.indent(indent);
      if (dynamic) {
        builder.append("instance.");
      }
      formatCall(writer, test).append(";").append(NL);
    }

    //end of method body
    indent--;
    writer.indent(indent).append("}").append(NL).append(NL);
  }

}
