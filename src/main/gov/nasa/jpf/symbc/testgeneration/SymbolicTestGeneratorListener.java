package gov.nasa.jpf.symbc.testgeneration;

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
import gov.nasa.jpf.symbc.numeric.SymbolicInteger;
import gov.nasa.jpf.symbc.numeric.SymbolicReal;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.vm.ChoiceGenerator;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.ExceptionHandler;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.LocalVarInfo;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.Types;
import gov.nasa.jpf.vm.VM;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 *
 * @author Maximilian Haberl
 */
public class SymbolicTestGeneratorListener extends ListenerAdapter {

  private Map<MethodInfo, Set<TestCase>> models;
  private final JPFLogger logger = JPF.getLogger("gov.nasa.jpf.symbc.testgeneration.SymbolicTestGeneratorListener");
  private String formatterClassName;
  private Config config;
  private boolean optimize;

  public SymbolicTestGeneratorListener(Config conf, JPF jpf) {
    config = conf;
    //fetching an optional abbreviation
    String abbreviation = conf.getString("SymbolicTestGeneratorListener.abbreviation", "SymbolicTestGeneratorListener");
    formatterClassName = conf.getString(abbreviation + ".formatter", "gov.nasa.jpf.symbc.testgeneration.JUnit5Formatter");

    optimize = conf.getBoolean(abbreviation + ".optimize", false);
    //TODO formatter

    jpf.addPublisherExtension(ConsolePublisher.class, this);
    models = new HashMap<>();
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
          //this reference is not passed in the args array
          summary.concreteArgs.put(var, args[i - startIdx]);
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
      if (logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, "No path condition for " + mi.getBaseName());
      }
      return;
    }

    PathCondition pc = pccg.getCurrentPC();
    if (!pc.solve()) {
      if (logger.isFineLogged()) {
        logger.fine("Path condition could not be solved:");
        logger.fine(pc);
      }
      return;
    }

    //logging the path condition at a very low level
    if (logger.isFinerLogged()) {
      logger.finer(pc);
    }

    TestCase test = setArguments(summary, frame);

    //TODO: remove
    //System.out.println(mi.getReturnTypeName());
    if (!mi.getReturnTypeName().equalsIgnoreCase("void")) {
      Expression returnExpression = ret.getReturnAttr(currentThread, Expression.class);
      if (returnExpression != null) {
        System.out.println("Return expression: " + returnExpression);
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
        if (integer == null || integer.solution() == SymbolicInteger.UNDEFINED) {
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
        if (real == null || real.solution() == SymbolicReal.UNDEFINED) {
          //concrete
          test.returnValue = ret.getReturnValue(currentThread);
        } else {
          //symbolic
          test.returnValue = real.solution();
        }
        break;

      case Types.T_BOOLEAN:
        integer = (IntegerExpression) ret.getReturnAttr(currentThread, IntegerExpression.class);
        if (integer == null || integer.solution() == SymbolicInteger.UNDEFINED) {
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

    addTestCase(test);

    if (optimize) {
      //we dont want to run everything after this method every time
      boolean ignore = false;

      //check if there is any other PCChoiceGenerator which has choices remaining
      for (ChoiceGenerator cg : vm.getChoiceGeneratorsOfType(PCChoiceGenerator.class)) {
        if (cg.hasMoreChoices()) {
          ignore = true;
        }
      }

      //check if there is another symbolic method that (in)directly called this method
      //in that case we have to continue execution, as the method that called us has not returned yet
      while (frame.getPrevious() != null) {
        frame = frame.getPrevious();
        if (frame.hasFrameAttr(ArgumentSummary.class)) {
          ignore = false;
        }
      }

      vm.getSystemState().setIgnored(ignore);
    }
  }

  @Override
  public void exceptionThrown(VM vm, ThreadInfo currentThread, ElementInfo thrownException) {
    StackFrame frame = currentThread.getModifiableTopFrame();
    MethodInfo mi = currentThread.getTopFrameMethodInfo();
    ClassInfo exceptionInfo = thrownException.getClassInfo();
    String exceptionName = thrownException.getClassInfo().getName();

    //for the time being, just store all the symbolic frames into a list 
    List<StackFrame> symbolicFrames = new ArrayList<>();

    //iterating over all stackframes
    boolean caught = false;
    while (!caught && frame != null) {
      MethodInfo info = frame.getMethodInfo();
      ExceptionHandler handler = info.getHandlerFor(exceptionInfo, frame.getPC());

      if (handler != null) {
        caught = true;
        //System.out.println(String.format("Caught %s in %s", exceptionInfo.getName(), mi.getLongName()));
      } else if (frame.hasFrameAttr(ArgumentSummary.class)) {
        symbolicFrames.add(frame);
      }

      frame = frame.getCallerFrame();
    }

    //check if we have a path condition
    PCChoiceGenerator pccg = vm.getLastChoiceGeneratorOfType(PCChoiceGenerator.class);
    if (pccg == null || pccg.getCurrentPC() == null) {
      if (logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, "No path condition for exception in {0}", mi.getLongName());
      }
      return;
    }

    //get and save pathcondition
    PathCondition pc = pccg.getCurrentPC();

    if (pc.solve()) {
      for (StackFrame symbolicFrame : symbolicFrames) {
        ArgumentSummary summary = symbolicFrame.getFrameAttr(ArgumentSummary.class);
        TestCase test = setArguments(summary, symbolicFrame);
        test.didThrow = true;
        test.returnValue = thrownException;
        addTestCase(test);
      }
    } else {
      //TODO
      System.out.println("Could not solve PC when an exception was thrown!");
    }

  }

  private TestCase setArguments(ArgumentSummary summary, StackFrame frame) {
    TestCase test = new TestCase(frame.getMethodInfo());
    test.args.putAll(summary.concreteArgs);

    //TODO put in its own method
    for (LocalVarInfo var : summary.symbolicArgs) {

      //converting the solution into the correct type
      byte type = Types.getTypeCode(var.getSignature());
      switch (type) {
        case Types.T_BYTE:
        case Types.T_CHAR:
        case Types.T_SHORT:
        case Types.T_INT:
        case Types.T_LONG:
          //solution returns a long, which is fine for every integer type
          IntegerExpression integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
          long integerSolution = integer.solution();
          if (integerSolution == SymbolicInteger.UNDEFINED) {
            //we use a default value
            test.args.put(var, 1);
            frame.getLocalAttr(var.getSlotIndex(), SymbolicInteger.class).solution = 1;
          } else {
            test.args.put(var, integerSolution);
          }
          break;

        case Types.T_FLOAT:
        case Types.T_DOUBLE:
          RealExpression real = frame.getLocalAttr(var.getSlotIndex(), RealExpression.class);
          double realSolution = real.solution();
          if (realSolution == SymbolicReal.UNDEFINED) {
            test.args.put(var, 1.0);
            frame.getLocalAttr(var.getSlotIndex(), SymbolicReal.class).solution = 1.0;
          } else {
            test.args.put(var, real.solution());
          }
          break;

        case Types.T_BOOLEAN:
          integer = frame.getLocalAttr(var.getSlotIndex(), IntegerExpression.class);
          if (integer.solution() == SymbolicInteger.UNDEFINED) {
            test.args.put(var, false);
            frame.getLocalAttr(var.getSlotIndex(), SymbolicInteger.class).solution = 0;
          } else {
            boolean boolVal = integer.solution() == 1;
            test.args.put(var, boolVal);
          }
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
        //TODO: error condition
      }
    }

    return test;
  }

  private void addTestCase(TestCase test) {
    if (!models.containsKey(test.method)) {
      models.put(test.method, new LinkedHashSet<>());
    }

    models.get(test.method).add(test);
  }

  @Override
  public void publishFinished(Publisher publisher) {
    TestcaseFormatter formatter = instanceFromClassname(formatterClassName, TestcaseFormatter.class, new Object[]{config});

    if (formatter == null) {
      if (logger.isLoggable(Level.SEVERE)) {
        logger.severe("Could not output any test cases as no formatter could be created.");
      }
      return;
    }
    publisher.publishTopicStart("Test cases");

    //print out everything
    PrintWriter pw = publisher.getOut();
    for (Map.Entry<MethodInfo, Set<TestCase>> entry : models.entrySet()) {
      MethodInfo mi = entry.getKey();
      Set<TestCase> testCases = entry.getValue();

      if (!mi.isStatic() && !hasTrivialConstructor(mi.getClassInfo())) {
        logger.log(Level.WARNING, String.format("Cannot generate test cases for %s as the declaring class %s does not have a trivial constructor", mi.getName(), mi.getClassName()));
        continue;
      }

      for (TestCase test : testCases) {
        formatter.format(test, pw);
      }
    }
    pw.flush();

    publisher.publishTopicEnd("Test cases");
  }

  private boolean hasTrivialConstructor(ClassInfo ci) {
    MethodInfo[] methods = ci.getDeclaredMethodInfos();
    for (MethodInfo method : methods) {
      if (method.isCtor() && method.getNumberOfArguments() == 0) {
        return true;
      }
    }

    return false;
  }

  private <T> T instanceFromClassname(String name, Class<T> superClass, Object[] args) {
    try {
      Class queried = Class.forName(name);

      if (!superClass.isAssignableFrom(queried)) {
        return null;
      }

      Class[] argClasses = new Class[args.length];
      for (int i = 0; i < args.length; i++) {
        argClasses[i] = args[i].getClass();
      }

      return (T) queried.getConstructor(argClasses).newInstance(args);
    } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
      logger.severe("Could not instantiate an instance of class " + name);
    }
    return null;
  }

  private class ArgumentSummary {

    /**
     * Mapping symbolic arguments to their concrete values
     */
    public List<LocalVarInfo> symbolicArgs;

    /**
     * Mapping concrete arguments to their values
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

}
