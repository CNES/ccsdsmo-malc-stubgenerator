/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2016 CNES
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package fr.cnes.mo.stubgen.go;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

import esa.mo.tools.stubgen.StubUtils;
import esa.mo.tools.stubgen.writers.AbstractWriter;


/**
 * 
 * Basic implementation of a go language writer that adds a few extra support methods.
 * 
 * @author lacourte
 *
 */
public class GoFileWriter extends AbstractWriter {

	// inner Writer
	protected StatementWriter out = null;
	// indentation counter
	private int tabCount = 0;
	// package name of the go file
	public String packageName = null;
	// <code>true</code> if the err variable has already been defined
	public boolean isErrDefined = false;
	
	public GoFileWriter() throws IOException
	{
		super();
	}

	public GoFileWriter(StatementWriter out) throws IOException
	{
		super();
		this.out = out;
	}

  public GoFileWriter(File folder, String baseName) throws IOException
  {
  	super();
    Writer file = StubUtils.createLowLevelWriter(folder, baseName, "go");
    out = new StatementWriter(file);
  }
  
  public void setPackageName(String packageName) {
		this.packageName = packageName;
  }
  
	@Override
  public void flush() throws IOException
  {
		if (out != null)
			out.flush();
  }
  
  /**
   * Finalize the file contents and close the underlying writer.
   * 
   * @throws IOException
   */
  public void close() throws IOException
  {
		if (out != null)
		{
			flush();
			out.close();
		}
  }
  
  public void addNewLine() throws IOException {
  	out.addNewLine();
  }

  public void addNewLine(int indentChange) throws IOException {
  	out.addNewLine();
  	tabCount += indentChange;
  }
  
  /**
   * Add proper indentation for the next statement.
   * Avoids the use of intermediate string buffers that would be required by the addFileStatement method.
   * 
   * @throws IOException
   */
  public void addIndent() throws IOException
  {
    for (int i = 0; i < tabCount; i++)
    {
      out.append("  ");
    }
  }
  public void addIndent(int indentChange) throws IOException
  {
  	tabCount += indentChange;
  	addIndent();
  }

  public void addSingleLineComment(String comment) throws IOException
  {
  	addIndent();
  	out.append("// ");
  	out.append(comment);
  	addNewLine();
  }

  /**
   * Open a #ifndef #define statement for the file.
   * Should be closed by function closeDefine.
   * 
   * @throws IOException If any problems writing to the file.
   */
  public void openDefine(String mark) throws IOException
  {
  	// #ifndef <mark>
  	// #define <mark>
  	out.append("#ifndef ");
  	out.append(mark);
  	addNewLine();
    out.append("#define ");
    out.append(mark);
  	addNewLine();
  	addNewLine();
  }

  /**
   * Close the #define statement for the file previously opened by function openDefine.
   * 
   * @throws IOException If any problems writing to the file.
   */
  public void closeDefine(String mark) throws IOException
  {
  	// #endif <mark>
  	addNewLine();
    out.append("#endif ");
    out.append(mark);
  	addNewLine();
  }

  /**
   * Open an extern C statement for the file.
   * Should be closed by function closeC.
   * 
   * @throws IOException If any problems writing to the file.
   */
  public void openC() throws IOException
  {
  	// #ifdef __cplusplus
  	// extern "C" {
  	// #endif __cplusplus
  	out.append("#ifdef __cplusplus");
  	addNewLine();
  	out.append("extern \"C\" {");
  	addNewLine();
  	out.append("#endif // __cplusplus");
  	addNewLine();
  	addNewLine();
  }

  /**
   * Close the extern C statement for the file previously opened by function openC.
   * 
   * @throws IOException If any problems writing to the file.
   */
  public void closeC() throws IOException
  {
  	// #ifdef __cplusplus
  	// }
  	// #endif __cplusplus
  	addNewLine();
  	out.append("#ifdef __cplusplus");
  	addNewLine();
  	out.append("}");
  	addNewLine();
  	out.append("#endif // __cplusplus");
  	addNewLine();
  }
  
  /**
   * Add an #include statement.
   * 
   * @param fileName
   * @throws IOException
   */
  public void addInclude(String fileName) throws IOException
  {
  	// #include <fileName>
  	out.append("#include \"");
  	out.append(fileName);
  	out.append("\"");
    addNewLine();
  }

  /**
   * Add a #define statement.
   * 
   * @param variable
   * @param value
   * @throws IOException
   */
  public void addDefine(String variable, String value) throws IOException
  {
  	// #define <variable> <value>
  	out.append("#define ");
  	out.append(variable);
  	out.append(" ");
  	out.append(value);
    addNewLine();
  }
  
  /**
   * Add a typedef struct statement.
   * 
   * @param structName	structure name
   * @param typeName	name of the type to define
   * @throws IOException
   */
  public void addTypedefStruct(String structName, String typeName) throws IOException
  {
    // typedef struct [<structName> ]<typeName>;
  	addIndent();
  	out.append("typedef struct ");
  	out.append(structName);
  	out.append(" ");
		out.append(typeName);
  	out.append(";");
    addNewLine();
  }
  
  /**
   * First step of a typedef enum statement.
   * 
   * @param enumName	name of the enumeration, may be null
   * @throws IOException
   */
  public void openTypedefEnum(String enumName) throws IOException
  {
    // typedef enum [<enumName> ]{
  	addIndent();
  	out.append("typedef enum ");
  	if (enumName != null)
  	{
  		out.append(enumName);
  		out.append(" ");
  	}
		out.append("{");
    addNewLine();
  	tabCount ++;
  }
  
  /**
   * Second step of a typedef enum statement.
   * This step may be called multiple times with the last parameter set to false, then once with the last parameter set to true.
   * 
   * @param enumVariable	name of the enumerated variable
   * @param enumValue	optional value of the enumerated variable
   * @param last	true if this is the last variable in the enumeration
   * @throws IOException
   */
  public void addTypedefEnumElement(String enumVariable, String enumValue, boolean last) throws IOException {
  	// <enumVariable>[ = <enumValue>][,]
  	addIndent();
  	out.append(enumVariable);
  	if (enumValue != null)
  	{
  		out.append(" = ");
  		out.append(enumValue);
  	}
  	if (!last)
  	{
  		out.append(",");
  	}
    addNewLine();
  }

  /**
   * Third and final step of a typedef enum statement.
   * 
   * @param typeName	name of the type defined by the statement
   * @throws IOException
   */
  public void closeTypedefEnum(String typeName) throws IOException
  {
    // } <typeName>;
  	tabCount --;
  	addIndent();
  	out.append("} ");
		out.append(typeName);
  	out.append(";");
    addNewLine();
  }

  public void openBlock() throws IOException
  {
  	addStatement("{", 1);
  }
  
  public void closeBlock() throws IOException
  {
  	addStatement("}", -1, true);
  }
  
  /**
   * First step of a structure definition statement.
   * 
   * @param structName	name of the structure, may be null
   * @throws IOException
   */
  public void openStruct(String structName) throws IOException
  {
    // type <structName> struct {
  	addIndent();
  	out.append("type ")
  		.append(structName)
  		.append(" struct {");
    addNewLine();
  	tabCount ++;
  }
  
  /**
   * Second step of a structure definition statement.
   * This step may be called multiple times with the last parameter set to false, then once with the last parameter set to true.
   * 
   * @param fieldType	type of the structure field
   * @param fieldName	name of the structure field
   * @throws IOException
   */
  public void addStructField(String fieldType, String fieldName) throws IOException {
  	// <fieldName> <fieldType>
  	addVariableDeclare(fieldType, fieldName, null);
  }
  
  public void addVariableDeclare(String varType, String varName, String varValue) throws IOException {
  	// <varName>[ <varType>][ = <varValue>]
  	addIndent();
  	out.append(varName);
  	if (varType != null) {
  		out.append(" ");
  		out.append(varType);
  	} else if (varValue == null) {
  		throw new IllegalArgumentException("addVariableDeclare: varType or varValue must not be null");
  	}
  	if (varValue != null)
  	{
    	out.append(" = ");
    	out.append(varValue);
  	}
    addNewLine();
  }

  /**
   * Third and final step of a structure definition statement.
   * 
   * @throws IOException
   */
  public void closeStruct() throws IOException
  {
    // };
  	tabCount --;
  	addIndent();
  	out.append("}");
    addNewLine();
  }
//  
//  public void openFunctionPrototype(String funcType, String funcName, int paramNumber) throws IOException
//  {
//  	// <funcType> <funcName>([void]
//  	openFunction(funcType, funcName, paramNumber);
//  }
//  
//  public void closeFunctionPrototype() throws IOException
//  {
//  	// );
//  	out.append(");");
//  	addNewLine();
//  }
//  
  public static final String FUNC_RECEIVER = "receiver";  
  public void openFunction(String funcName, String receiver) throws IOException
  {
  	// [rec <receiver>] func <funcName>(
  	addIndent();
  	out.append("func ");
  	if (receiver != null) {
  		out.append("(")
  			.append(FUNC_RECEIVER)
  			.append(" ")
  			.append(receiver)
  			.append(") ");
  	}
  	out.append(funcName)
  		.append("(");
  }

  public void addFunctionParameter(String paramType, String paramName, boolean last) throws IOException
  {
  	// paramName <paramType>[, ]
  	out.append(paramName)
  		.append(" ")
  		.append(paramType);
  	if (!last)
  	{
  		out.append(", ");
  	}
  }

  public void addFunctionParameters(String[][] params) throws IOException
  {
	  final int size = params.length;
	  for (int i = 0 ; i < size ; i++) {
		  String paramType = params[i][0];
		  String paramName = params[i][1];
		  boolean last = (i == size - 1);
		  addFunctionParameter(paramType, paramName, last);
	  }
  }

  public void openFunctionBody(String[] funcType) throws IOException
  {
  	// ) [<funcType>] {
  	out.append(")");
  	if (funcType != null) {
  		out.append(" ");
  		if (funcType.length == 1) {
  			out.append(funcType[0]);
  		} else {
  			out.append("(");
  			for (int i = 0; i < (funcType.length-1); i++) {
  				out.append(funcType[i])
  					.append(", ");
  			}
  			out.append(funcType[funcType.length-1])
  				.append(")");
  		}
  	}
  	out.append(" {");
  	addNewLine();
  	tabCount ++;
  }

  public void addStatement(String statement) throws IOException
  {
  	// <statement>
  	if (! statement.isEmpty())
  	{
  		addIndent();
  		out.append(statement);
  	}
  	addNewLine();
  }
  
  public void addStatement(String statement, int indentChange) throws IOException
  {
  	addStatement(statement, indentChange, false);
  }

  public void addStatement(String statement, int indentChange, boolean indentFirst) throws IOException
  {
  	if (indentChange == 0)
  	{
    	addStatement(statement);
    	return;
  	}
  	if (indentFirst)
  	{
    	tabCount += indentChange;
  	}
  	addStatement(statement);
  	if (! indentFirst)
  	{
    	tabCount += indentChange;
  	}
  }

  public void addStatements(StatementWriter in) throws IOException {
  	List<String> inStatements = in.getStatements();
  	if (inStatements != null)
  	{
  		for (String st : inStatements)
  		{
  			addStatement(st);
  		}
  	}
  }
  
  public void closeFunctionBody() throws IOException
  {
  	// }
  	tabCount --;
  	addIndent();
  	out.append("}");
  	addNewLine();
  }
  
  public void openConstBlock() throws IOException
  {
  	// const (
  	addStatement("const (");
  	tabCount++;
  }

  public void closeConstBlock() throws IOException
  {
  	// )
  	tabCount--;
  	addStatement(")");
  }

  public void openVarBlock() throws IOException
  {
  	// var (
  	addStatement("var (");
  	tabCount++;
  }

  public void closeVarBlock() throws IOException
  {
  	// )
  	tabCount--;
  	addStatement(")");
  }

  public void openImportBlock() throws IOException
  {
  	// import (
  	addStatement("import (");
  	tabCount++;
  }

  public void closeImportBlock() throws IOException
  {
  	// )
  	tabCount--;
  	addStatement(")");
  }

}
