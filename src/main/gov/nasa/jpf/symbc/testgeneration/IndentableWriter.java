/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.nasa.jpf.symbc.testgeneration;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author mxl
 */
public class IndentableWriter extends Writer {

  /**
   * Defines what a tab is for the indentation feature
   */
  private final String TAB = "   ";

  /**
   * Stores i indentations in the i-th element
   */
  private final List<String> indentations;

  /**
   * the wrapped writer
   */
  private final Writer writer;

  public IndentableWriter(Writer out) {
    super();
    indentations = new ArrayList<>();
    initIndents(10);
    writer = out;
  }

  private String getIndentation(int indents) {
    if (indents >= indentations.size()) {
      String indent = indentations.get(indentations.size() - 1);

      while (indentations.size() <= indents) {
        indent += TAB;
        indentations.add(indent);
      }
    }

    return indentations.get(indents);
  }

  private void initIndents(int amount) {
    String indent = "";

    for (int i = 0; i < amount; i++) {
      indentations.add(indent);
      indent += TAB;
    }
  }

  public IndentableWriter indent(int amount) {
    appendPriv(getIndentation(amount));
    return this;
  }

  public IndentableWriter append(Object o) {
    if (o == null) {
      appendPriv("null");
    } else {
      appendPriv(o.toString());
    }

    return this;
  }

  @Override
  public IndentableWriter append(CharSequence csq) {
    appendPriv(csq);
    return this;
  }

  private void appendPriv(CharSequence seq) {
    try {
      writer.append(seq);
    } catch (IOException ex) {
      Logger.getLogger(SymbolicTestGeneratorListener.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  @Override
  public void write(char[] chars, int i, int i1) throws IOException {
    writer.write(chars, i, i1);
  }

  @Override
  public void flush() throws IOException {
    writer.flush();
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }
}
