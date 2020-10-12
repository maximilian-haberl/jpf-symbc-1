package gov.nasa.jpf.symbc;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.ListenerAdapter;
import gov.nasa.jpf.jvm.bytecode.JVMInvokeInstruction;
import gov.nasa.jpf.jvm.bytecode.JVMReturnInstruction;
import gov.nasa.jpf.report.ConsolePublisher;
import gov.nasa.jpf.report.Publisher;
import gov.nasa.jpf.symbc.bytecode.BytecodeUtils;
import gov.nasa.jpf.symbc.numeric.Expression;
import gov.nasa.jpf.symbc.numeric.IntegerExpression;
import gov.nasa.jpf.symbc.numeric.PCChoiceGenerator;
import gov.nasa.jpf.symbc.numeric.PathCondition;
import gov.nasa.jpf.symbc.numeric.RealExpression;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.vm.ChoiceGenerator;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.LocalVarInfo;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.Types;
import gov.nasa.jpf.vm.VM;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 *
 * @author Maximilian Haberl
 */
public class SymbolicTestGeneratorListener extends ListenerAdapter {

  private List<TestCase> models;
  private final JPFLogger logger = JPF.getLogger("gov.nasa.jpf.symbc.SymbolicTestGeneratorListener");
  private boolean optimize;

  public SymbolicTestGeneratorListener(Config conf, JPF jpf) {
    //fetching an optional abbreviation
    String abbreviation = conf.getString("SymbolicTestGeneratorListener.abbreviation", "SymbolicTestGeneratorListener");

    optimize = conf.getBoolean(abbreviation + ".optimize", false);

    jpf.addPublisherExtension(ConsolePublisher.class, this);
    models = new ArrayList<>();
  }

  @Override
  public void instructionExecuted(VM vm, ThreadInfo currentThread, Instruction nextInstruction, Instruction executedInstruction) {
    if (executedInstruction instanceof JVMInvokeInstruction) {
      JVMInvokeInstruction invoke = (JVMInvokeInstruction) executedInstruction;
      methodEntered(vm, currentThread, invoke);
    } else if (executedInstruction instanceof JVMReturnInstruction) {
      JVMReturnInstruction ret = (JVMReturnInstruction) executedInstruction;
      methodExited(vm, currentThread, ret);
    }
  }

  private void methodEntered(VM vm, ThreadInfo currentThread, JVMInvokeInstruction invoke) {
    MethodInfo mi = invoke.getInvokedMethod();

    if (BytecodeUtils.isMethodSymbolic(vm.getConfig(), mi.getFullName(), mi.getNumberOfArguments(), null)) {
      LocalVarInfo[] lvi = mi.getArgumentLocalVars();
      StackFrame frame = currentThread.getModifiableTopFrame();
      ArgumentSummary summary = new ArgumentSummary(mi);
      Object[] args = invoke.getArgumentValues(currentThread);

      assert lvi != null : "ERROR: No debug information available";

      //iterating over all arguments except 'this'
      int startIdx = 0;
      if (!mi.isStatic()) {
        startIdx = 1;
      }
      for (int i = startIdx; i < lvi.length; i++) {
        LocalVarInfo var = lvi[i];
        Expression expression = (Expression) frame.getLocalAttr(var.getSlotIndex(), Expression.class);

        if (expression != null) {
          //symbolic
          summary.symbolicArgs.add(var);
        } else {
          //concrete
          summary.concreteArgs.put(var, args[i]);
        }
      }

      frame.addFrameAttr(summary);
    }
  }

  private void methodExited(VM vm, ThreadInfo currentThread, JVMReturnInstruction ret) {
    MethodInfo mi = ret.getMethodInfo();
    StackFrame frame = ret.getReturnFrame();
    ArgumentSummary summary = frame.getFrameAttr(ArgumentSummary.class);

    if (summary == null) {
      //this method is not symbolic, as no summary was attached
      return;
    }

    PCChoiceGenerator pccg = vm.getLastChoiceGeneratorOfType(PCChoiceGenerator.class);
    if (pccg == null || pccg.getCurrentPC() == null) {
      if (logger.isLoggable(Level.WARNING)) {
        logger.log(Level.WARNING, "No path condition for " + mi.getBaseName());
      }
      return;
    }

    PathCondition pc = pccg.getCurrentPC();
    pc.solve();

    if (logger.isFinerLogged()) {
      logger.finer(pc);
    }

    TestCase test = new TestCase(mi);
    test.args.putAll(summary.concreteArgs);

    for (LocalVarInfo var : summary.symbolicArgs) {
      //converting the solution into the correct type
      switch (Types.getTypeCode(var.getSignature())) {
        case Types.T_BYTE:
        case Types.T_CHAR:
        case Types.T_SHORT:
        case Types.T_INT:
        case Types.T_LONG:
          //solution returns a long, which is fine for every integer type
          IntegerExpression integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
          test.args.put(var, integer.solution());
          break;

        case Types.T_FLOAT:
        case Types.T_DOUBLE:
          RealExpression real = frame.getLocalAttr(var.getSlotIndex(), RealExpression.class);
          test.args.put(var, real.solution());
          break;

        case Types.T_BOOLEAN:
          integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
          boolean boolVal = integer.solution() == 1;
          test.args.put(var, boolVal);
          break;

        case Types.T_VOID:
        case Types.T_NONE:
          //TODO this is an error condition
          break;

        case Types.T_ARRAY:
        case Types.T_REFERENCE:
          //TODO handle references
          break;

        default:
        //Do nothing
      }
    }

    switch (mi.getReturnTypeCode()) {
      //a lot of fallthrough because all integers are handled the same
      case Types.T_BYTE:
      case Types.T_CHAR:
      case Types.T_SHORT:
      case Types.T_INT:
      case Types.T_LONG:
        IntegerExpression integer = (IntegerExpression) ret.getReturnAttr(currentThread, IntegerExpression.class);
        if (integer == null) {
          //concrete
          test.returnValue = ret.getReturnValue(currentThread);
        } else {
          //symbolic
          test.returnValue = integer.solution();
        }
        break;

      case Types.T_DOUBLE:
      case Types.T_FLOAT:
        RealExpression real = (RealExpression) ret.getReturnAttr(currentThread, RealExpression.class);
        if (real == null) {
          //concrete
          test.returnValue = ret.getReturnValue(currentThread);
        } else {
          //symbolic
          test.returnValue = real.solution();
        }
        break;

      case Types.T_BOOLEAN:
        integer = (IntegerExpression) ret.getReturnAttr(currentThread, IntegerExpression.class);
        if (integer == null) {
          //concrete
          Object o = ret.getReturnValue(currentThread);
          assert o instanceof Integer;
          test.returnValue = (Integer) o == 1;
        } else {
          //symbolic
          test.returnValue = integer.solution() == 1;
        }
        break;

      case Types.T_VOID:
        test.returnValue = null;
        break;

      case Types.T_ARRAY:
      case Types.T_REFERENCE:
      //dont know what to do yet so fallthrough
      default:
        test.returnValue = ret.getReturnValue(currentThread);

    }

    models.add(test);

    if (optimize) {
      //we dont want to run everything after this method every time
      for (ChoiceGenerator cg : vm.getChoiceGeneratorsOfType(PCChoiceGenerator.class)) {
        if (cg.hasMoreChoices()) {
          vm.getSystemState().setIgnored(true);
        }
      }
    }
  }

  @Override
  public void exceptionThrown(VM vm, ThreadInfo currentThread, ElementInfo thrownException) {
    StackFrame frame = currentThread.getModifiableTopFrame();
    MethodInfo mi = currentThread.getTopFrameMethodInfo();

    ArgumentSummary summary = frame.getFrameAttr(ArgumentSummary.class);

    //check if the exception was thrown in the symbolic method
    if (summary != null) {
      //get last symbolic CG
      PCChoiceGenerator pccg = vm.getLastChoiceGeneratorOfType(PCChoiceGenerator.class);
      if (pccg == null && logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, "No path condition for exception in {0}", mi.getLongName());
        return;
      }

      //get and save pathcondition
      PathCondition pc = pccg.getCurrentPC();

      if (pc.solve()) {
        TestCase test = new TestCase(mi);
        test.args.putAll(summary.concreteArgs);

        //TODO put in its own method
        for (LocalVarInfo var : summary.symbolicArgs) {
          //converting the solution into the correct type
          switch (Types.getTypeCode(var.getSignature())) {
            case Types.T_BYTE:
            case Types.T_CHAR:
            case Types.T_SHORT:
            case Types.T_INT:
            case Types.T_LONG:
              //solution returns a long, which is fine for every integer type
              IntegerExpression integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
              test.args.put(var, integer.solution());
              break;

            case Types.T_FLOAT:
            case Types.T_DOUBLE:
              RealExpression real = frame.getLocalAttr(var.getSlotIndex(), RealExpression.class);
              test.args.put(var, real.solution());
              break;

            case Types.T_BOOLEAN:
              integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
              boolean boolVal = integer.solution() == 1;
              test.args.put(var, boolVal);
              break;

            case Types.T_VOID:
            case Types.T_NONE:
              //TODO this is an error condition
              break;

            case Types.T_ARRAY:
            case Types.T_REFERENCE:
              //TODO handle references
              break;

            default:
            //Do nothing
          }
        }

        test.didThrow = true;
        //for maximum information we just attach the ElementInfo object of the thrown exception as the return value
        test.returnValue = thrownException;

      } else {
        //TODO
        System.out.println("Could not solve PC when an exception was thrown!");
      }
    }

  }

  @Override
  public void publishFinished(Publisher publisher) {
    publisher.publishTopicStart("Test cases");

    //print out everything
    int number = 0;
    PrintWriter pw = publisher.getOut();
    for (TestCase model : models) {
      model.format(pw, number++);
    }
    pw.flush();

    publisher.publishTopicEnd("Test cases");
  }

  /*
  @Override
  public void vmInitialized(VM vm) {
    System.out.println(String.join(", ", SymbolicInstructionFactory.dp));
  }
   */
  private class ArgumentSummary {

    /**
     * mapping argument name to symbolic name
     */
    public List<LocalVarInfo> symbolicArgs;

    /**
     * mapping argument name to value
     */
    public Map<LocalVarInfo, Object> concreteArgs;

    /**
     * name of the method
     */
    public MethodInfo info;

    public ArgumentSummary(MethodInfo info) {
      symbolicArgs = new ArrayList<>();
      concreteArgs = new HashMap<>();
      this.info = info;
    }

  }

  private class TestCase {

    public Map<LocalVarInfo, Object> args;
    public MethodInfo method;
    public Object returnValue;
    public boolean didThrow;

    public TestCase(MethodInfo mi) {
      args = new HashMap<>();
      this.method = mi;
      didThrow = false;
    }

    /**
     * Only works for static methods
     *
     * @param pw
     * @param number
     */
    public void format(PrintWriter pw, int number) {
      //test declaration
      pw.println("@Test");
      pw.print("public void ");
      pw.append(method.getName()).append("Test").append(Integer.toString(number)).append("(){");
      pw.println();

      //expected result
      pw.append('\t').append(method.getReturnTypeName()).append(" expected = ").append(returnValue.toString());
      pw.println(";");

      //actual result
      pw.append('\t').append(method.getReturnTypeName()).append(" result = ");

      //call
      pw.append(method.getName()).append("(");
      LocalVarInfo[] lvi = method.getArgumentLocalVars();
      for (int i = 0; i < lvi.length; i++) {
        LocalVarInfo var = lvi[i];
        if (i != 0) {
          pw.append(", ");
        }
        pw.print(args.get(var));
      }
      pw.println(");");

      //Assertion
      pw.println("\tassertEquals(expected, result);");

      pw.println("}");
    }
  }

  private interface TestcaseFormatter {

    /**
     * Creates a String representation of the given test case and prints it to the PrintWriter pw
     * @param testCase test case that should be formatted
     * @param pw PrintWriter the test case should be written to
     */
    public void format(TestCase test, PrintWriter pw);

    /**
     * Creates and returns a String representation of the given test case
     * @param test test case that should be formatted
     * @return String representation of the test case
     */
    public String format(TestCase test);
  }
  
  private class TestCaseFormatterImpl implements TestcaseFormatter{

    @Override
    public void format(TestCase test, PrintWriter pw) {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String format(TestCase test) {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
  }
}
