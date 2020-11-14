package gov.nasa.jpf.symbc.testgeneration;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.ListenerAdapter;
import gov.nasa.jpf.jvm.bytecode.JVMInvokeInstruction;
import gov.nasa.jpf.jvm.bytecode.JVMReturnInstruction;
import gov.nasa.jpf.report.GenericFilePublisher;
import gov.nasa.jpf.report.Publisher;
import gov.nasa.jpf.symbc.arrays.ArrayExpression;
import gov.nasa.jpf.symbc.bytecode.BytecodeUtils;
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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;

/**
 *
 * @author Maximilian Haberl
 */
public class SymbolicTestGeneratorListener extends ListenerAdapter {

  private final Map<MethodInfo, Set<TestCase>> models;
  private final JPFLogger logger = JPF.getLogger("gov.nasa.jpf.symbc.testgeneration.SymbolicTestGeneratorListener");

  private final String formatterClassName;
  private final boolean optimize;
  private final boolean symarrays;

  private final VM vm;
  private final Config config;

  public SymbolicTestGeneratorListener(Config conf, JPF jpf) {
    config = conf;
    vm = jpf.getVM();

    //fetching an optional abbreviation
    String abbreviation = conf.getString("SymbolicTestGeneratorListener.abbreviation", "SymbolicTestGeneratorListener");
    formatterClassName = conf.getString(abbreviation + ".formatter", "gov.nasa.jpf.symbc.testgeneration.JUnit5Formatter");
    optimize = conf.getBoolean(abbreviation + ".optimize", false);
    symarrays = conf.getBoolean("symbolic.arrays", false);

    jpf.addPublisherExtension(GenericFilePublisher.class, this);

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
    StackFrame frame = currentThread.getModifiableTopFrame();

    /*
    we need to check if the stack frame for this method has already been created.
    In case we use fully symbolic arrays the invoke instruction will be executed twice.
    The first time the correct stack frame has not been created yet. We dont want to attach
    a summary in this case
     */
    boolean correctFrame = frame.getMethodInfo().getGlobalId() == mi.getGlobalId();
    Vector<String> symVars = new Vector<>();

    if (correctFrame && BytecodeUtils.isMethodSymbolic(vm.getConfig(), mi.getFullName(), mi.getNumberOfArguments(), symVars)) {
      LocalVarInfo[] lvi = mi.getArgumentLocalVars();
      ArgumentSummary summary = new ArgumentSummary(mi);
      Object[] args = invoke.getArgumentValues(currentThread);

      assert lvi != null : "ERROR: No debug information available";

      if (!isSupported(mi)) {
        if (logger.isLoggable(Level.WARNING)) {
          logger.log(Level.WARNING, "No tests are generated for " + mi.getName() + ", as it has a non array reference type");
        }
        return;
      }

      int startIdx = 0;
      if (!mi.isStatic()) {
        startIdx = 1;
      }

      for (int i = startIdx; i < lvi.length; i++) {
        LocalVarInfo var = lvi[i];
        int argIndex = i - startIdx;

        if (symVars.get(argIndex).equalsIgnoreCase("sym")) {
          //symbolic
          if (Types.isArray(var.getSignature()) && symarrays) {
            if (logger.isLoggable(Level.WARNING)) {
              logger.warning("Fully symbolic arrays are not supported. No tests generated for " + mi.getName());
            }
            return;
          } else {
            summary.symbolicArgs.put(var, SymbolicArgumentWrapper.wrapper(var, frame));
          }
        } else {
          //concrete
          //this reference is not passed in the args array
          Object val = concreteVar(var, args[argIndex]);
          summary.concreteArgs.put(var, val);
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

    TestCase test = setArguments(summary, frame, currentThread);

    switch (mi.getReturnTypeCode()) {
      //a lot of fallthrough because all integers are handled the same
      case Types.T_BYTE:
      case Types.T_CHAR:
      case Types.T_SHORT:
      case Types.T_INT:
      case Types.T_LONG:
        IntegerExpression integer = ret.getReturnAttr(currentThread, IntegerExpression.class);
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
        RealExpression real = ret.getReturnAttr(currentThread, RealExpression.class);
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
        ArrayExpression array = ret.getReturnAttr(currentThread, ArrayExpression.class);
        ElementInfo ei = (ElementInfo) ret.getReturnValue(currentThread);

        if (array != null) {
          //array fully symbolic
          test.returnValue = fullSymbolicArray();
        } else {
          //deals with arrays that contain some or all symbolic elements
          test.returnValue = partialSymbolicArray(ei);
        }
        break;

      case Types.T_REFERENCE:
      //dont know what to do yet so fallthrough
      default:
        throw new IllegalArgumentException();

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
        TestCase test = setArguments(summary, symbolicFrame, currentThread);
        test.didThrow = true;
        test.returnValue = thrownException;
        addTestCase(test);
        System.out.println("Symbolic method " + summary.info.getName() + " threw an exception");
      }
    } else {
      //TODO
      System.out.println("Could not solve PC when an exception was thrown!");
    }

  }

  private TestCase setArguments(ArgumentSummary summary, StackFrame frame, ThreadInfo th) {
    TestCase test = new TestCase(frame.getMethodInfo());
    test.args.putAll(summary.concreteArgs);

    for (Map.Entry<LocalVarInfo, SymbolicArgumentWrapper> entry : summary.symbolicArgs.entrySet()) {
      LocalVarInfo var = entry.getKey();
      SymbolicArgumentWrapper wrapper = entry.getValue();
      wrapper.setDefault();
      test.args.put(var, wrapper.solution());
    }

    return test;
  }

  private void addTestCase(TestCase test) {
    if (!models.containsKey(test.method)) {
      models.put(test.method, new LinkedHashSet<>());
    }

    models.get(test.method).add(test);
  }

  /**
   * Returns true, iff one of the arguments or the return type is a reference
   * type excluding arrays of basic types.
   *
   * @param mi method to check
   * @return
   */
  private boolean isSupported(MethodInfo mi) {
    LocalVarInfo[] arguments = mi.getArgumentLocalVars();

    //ignore 'this' for non-static methods
    int start = 1;
    if (mi.isStatic()) {
      start = 0;
    }

    for (int i = start; i < arguments.length; i++) {
      if (!isBasicOrBasicArray(arguments[i].getSignature())) {
        return false;
      }
    }

    return isBasicOrBasicArray(mi.getReturnType()) || mi.getReturnTypeCode() == Types.T_VOID;
  }
  
  private boolean isBasicOrBasicArray(String siganture) {
    String typename = Types.getTypeName(siganture);
    if (Types.isBasicType(typename)) {
      return true;
    } else if (Types.isArray(siganture)) {
      String elementTypename = Types.getTypeName(Types.getArrayElementType(siganture));
      return Types.isBasicType(elementTypename);
    } else {
      return false;
    }
  }

  private Object partialSymbolicArray(ElementInfo ei) {
    if (!ei.isArray()) {
      throw new IllegalArgumentException("Not an array: " + ei.getType());
    }

    String siganture = ei.getArrayType();
    if (Types.isArray(siganture)) {
      Object[] result = new Object[ei.arrayLength()];
      for (int i = 0; i < ei.arrayLength(); i++) {
        int ref = ei.getReferenceElement(i);
        ElementInfo info = vm.getElementInfo(ref);
        result[i] = partialSymbolicArray(info);
      }
      return result;
    } else if (Types.isBasicType(Types.getTypeName(siganture))) {
      IntegerExpression symint;
      RealExpression symreal;

      String type = Types.getTypeName(siganture);
      switch (type) {
        case "byte":
          Byte[] bytes = new Byte[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              bytes[i] = ei.getByteElement(i);
            } else {
              bytes[i] = (byte) symint.solution();
            }
          }
          return bytes;

        case "char":
          Character[] chars = new Character[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              chars[i] = ei.getCharElement(i);
            } else {
              chars[i] = (char) symint.solution();
            }
          }
          return chars;

        case "short":
          Short[] shorts = new Short[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              shorts[i] = ei.getShortElement(i);
            } else {
              shorts[i] = (short) symint.solution();
            }
          }
          return shorts;

        case "int":
          Integer[] ints = new Integer[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              ints[i] = ei.getIntElement(i);
            } else {
              ints[i] = (int) symint.solution();
            }
          }
          return ints;

        case "long":
          Long[] longs = new Long[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              longs[i] = ei.getLongElement(i);
            } else {
              longs[i] = symint.solution();
            }
          }
          return longs;

        case "float":
          Float[] floats = new Float[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symreal = ei.getElementAttr(i, RealExpression.class);
            if (symreal == null) {
              floats[i] = ei.getFloatElement(i);
            } else {
              floats[i] = (float) symreal.solution();
            }
          }
          return floats;

        case "double":
          Double[] doubles = new Double[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symreal = ei.getElementAttr(i, RealExpression.class);
            if (symreal == null) {
              doubles[i] = ei.getDoubleElement(i);
            } else {
              doubles[i] = symreal.solution();
            }
          }
          return doubles;

        case "boolean":
          Boolean[] booleans = new Boolean[ei.arrayLength()];
          for (int i = 0; i < ei.arrayLength(); i++) {
            symint = ei.getElementAttr(i, IntegerExpression.class);
            if (symint == null) {
              booleans[i] = ei.getBooleanElement(i);
            } else {
              booleans[i] = symint.solution() == 1;
            }
          }
          return booleans;

        default:
          //should not happen, element type is basic
          throw new IllegalArgumentException("Unsuppported array type: " + siganture);
      }
    } else {
      throw new IllegalArgumentException("Unsupported array type: " + siganture);
    }
  }

  private Object fullSymbolicArray() {
    return new Object[0];
  }

  /**
   * Turns ElementInfo objects into an actual instance for reference types.
   * Returns the value for basic types.
   *
   * @param var
   * @param val
   * @return
   */
  private Object concreteVar(LocalVarInfo var, Object val) {
    if (Types.isBasicType(var.getType())) {
      return val;
    }

    ElementInfo ei = (ElementInfo) val;
    if (Types.isArray(var.getSignature())) {
      return partialSymbolicArray(ei);
    } else {
      if (logger.isLoggable(Level.WARNING)) {
        logger.log(Level.WARNING, "Reference types other than arrays are not supported yet!");
      }
      return null;
    }
  }

  @Override
  public void publishFinished(Publisher publisher) {
    //setting up the formatter
    Object instance = instanceFromClassname(formatterClassName, new Object[]{config});

    if (instance == null || !(instance instanceof TestcaseFormatter)) {
      if (logger.isLoggable(Level.SEVERE)) {
        logger.severe("Could not output any test cases as no formatter could be created.");
      }
      return;
    }

    TestcaseFormatter formatter = (TestcaseFormatter) instance;

    formatter.format(filterTests(models), publisher.getOut());
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

  private Map<MethodInfo, Set<TestCase>> filterTests(Map<MethodInfo, Set<TestCase>> tests) {
    Map<MethodInfo, Set<TestCase>> result = new HashMap<>();

    for (Map.Entry<MethodInfo, Set<TestCase>> entry : tests.entrySet()) {
      MethodInfo mi = entry.getKey();
      if (mi.isStatic() || hasTrivialConstructor(mi.getClassInfo())) {
        result.put(mi, entry.getValue());
      } else {
        logger.log(Level.WARNING, String.format("Cannot generate test cases for %s as the declaring class %s does not have a trivial constructor", mi.getName(), mi.getClassName()));
      }
    }

    return result;
  }

  private Object instanceFromClassname(String name, Object[] args) {
    try {
      Class<?> queried = Class.forName(name);

      Class[] argClasses = new Class[args.length];
      for (int i = 0; i < args.length; i++) {
        argClasses[i] = args[i].getClass();
      }

      return queried.getConstructor(argClasses).newInstance(args);
    } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
      logger.severe("Could not instantiate an instance of class " + name);
    }
    return null;
  }

  private class ArgumentSummary {

    /**
     * Mapping symbolic arguments to their symbols
     */
    public Map<LocalVarInfo, SymbolicArgumentWrapper> symbolicArgs;

    /**
     * Mapping concrete arguments to their values
     */
    public Map<LocalVarInfo, Object> concreteArgs;

    /**
     * name of the method
     */
    public MethodInfo info;

    public ArgumentSummary(MethodInfo info) {
      symbolicArgs = new HashMap<>();
      concreteArgs = new HashMap<>();
      this.info = info;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder("Summary for ");
      builder.append(info.getName()).append("{\n");

      if (symbolicArgs.size() > 0) {
        builder.append("\tSymbolic arguments:\n");
        for (Map.Entry<LocalVarInfo, SymbolicArgumentWrapper> entry : symbolicArgs.entrySet()) {
          LocalVarInfo var = entry.getKey();
          SymbolicArgumentWrapper wrapper = entry.getValue();
          builder.append('\t').append(var.getName()).append(":\t").append(wrapper.solution()).append("\n");
        }
      }

      if (concreteArgs.size() > 0) {
        builder.append("\tConcrete args:\n");
        for (Map.Entry<LocalVarInfo, Object> entry : concreteArgs.entrySet()) {
          LocalVarInfo var = entry.getKey();
          Object val = entry.getValue();
          builder.append("\t").append(var.getName()).append(":\t").append(val).append("\n");
        }
      }
      builder.append("}");
      return builder.toString();
    }

  }

  private static interface SymbolicArgumentWrapper {

    public Object solution();

    public void setDefault();

    public static SymbolicArgumentWrapper wrapper(LocalVarInfo var, StackFrame frame) {
      if (Types.isArray(var.getSignature())) {
        int ref = frame.getLocalVariable(var.getSlotIndex());
        ElementInfo ei = VM.getVM().getElementInfo(ref);
        return new PartialSymArrayWrapper(ei);
      } else if (Types.isBasicType(Types.getTypeName(var.getSignature()))) {
        switch (Types.getTypeName(var.getSignature())) {
          case "byte":
          case "char":
          case "short":
          case "int":
          case "long":
            return new SymIntWrapper(frame.getLocalAttr(var.getSlotIndex(), SymbolicInteger.class));

          case "boolean":
            return new SymBollWrapper(frame.getLocalAttr(var.getSlotIndex(), SymbolicInteger.class));

          case "float":
          case "double":
            return new SymRealWrapper(frame.getLocalAttr(var.getSlotIndex(), SymbolicReal.class));

          default:
            throw new IllegalArgumentException("Unknown type: " + var.getType());
        }
      } else {
        //Unsupported
        throw new IllegalArgumentException("Unsupported type: " + var.getType());
      }
    }
  }

  private static class SymIntWrapper implements SymbolicArgumentWrapper {

    private SymbolicInteger symint;

    public SymIntWrapper(SymbolicInteger symint) {
      this.symint = symint;
    }

    @Override
    public Object solution() {
      return symint.solution();
    }

    @Override
    public void setDefault() {
      if (symint.solution == SymbolicInteger.UNDEFINED) {
        symint.solution = 1;
      }
    }

  }

  private static class SymBollWrapper implements SymbolicArgumentWrapper {

    private SymbolicInteger symint;

    public SymBollWrapper(SymbolicInteger symint) {
      this.symint = symint;
    }

    @Override
    public Object solution() {
      return symint.solution() == 1;
    }

    @Override
    public void setDefault() {
      symint.solution = 1;
    }

  }

  private static class SymRealWrapper implements SymbolicArgumentWrapper {

    private SymbolicReal symreal;

    public SymRealWrapper(SymbolicReal symreal) {
      this.symreal = symreal;
    }

    @Override
    public Object solution() {
      return symreal.solution();
    }

    @Override
    public void setDefault() {
      if (symreal.solution == SymbolicReal.UNDEFINED) {
        symreal.solution = 1.0;
      }
    }

  }

  private static class SymArrayWrapper implements SymbolicArgumentWrapper {

    private ArrayExpression symArray;

    public SymArrayWrapper(ArrayExpression symArray) {
      this.symArray = symArray;
    }

    @Override
    public Object solution() {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setDefault() {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

  }

  private static class PartialSymArrayWrapper implements SymbolicArgumentWrapper {

    ElementInfo ei;
    SymbolicArgumentWrapper[] elements;

    public PartialSymArrayWrapper(ElementInfo ei) {
      this.ei = ei;
      elements = new SymbolicArgumentWrapper[ei.arrayLength()];
      collectVars();
    }

    private void collectVars() {
      String typename = Types.getTypeName(ei.getArrayType());

      if (Types.isBasicType(typename)) {
        switch (typename) {
          case "byte":
          case "char":
          case "short":
          case "int":
          case "long":
          case "boolean":
            for (int i = 0; i < ei.arrayLength(); i++) {
              SymbolicInteger symint = ei.getElementAttr(i, SymbolicInteger.class);
              elements[i] = new SymIntWrapper(symint);
            }
            break;

          case "float":
          case "double":
            for (int i = 0; i < ei.arrayLength(); i++) {
              SymbolicReal symreal = ei.getElementAttr(i, SymbolicReal.class);
              elements[i] = new SymRealWrapper(symreal);
            }
            break;

          default:
            //should not happen
            throw new AssertionError();
        }
      } else {
        throw new IllegalArgumentException("Unsupported array type:" + ei.getType());
      }
    }

    @Override
    public Object solution() {
      Object[] result = new Object[elements.length];
      for (int i = 0; i < elements.length; i++) {
        result[i] = elements[i].solution();
      }
      return result;
    }

    @Override
    public void setDefault() {
      for (SymbolicArgumentWrapper element : elements) {
        element.setDefault();
      }
    }
  }

}
