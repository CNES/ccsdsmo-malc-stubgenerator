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

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedList;
import java.util.List;

/**
 * 
 * Wrapper to a standard Writer which specializes the lines as statements.
 * If no Writer is provided, statements are kept in memory to be written afterwards.
 * 
 * @author lacourte
 *
 */
public class StatementWriter implements Appendable, Closeable, Flushable {

  private final String lineSeparator;
  
	// inner Writer
	protected Writer out;
	
	// list of statements
	protected List<String> statements;
	// currently generated statement
	protected StringBuilder buf;

	public StatementWriter(Writer out) throws IOException
	{
		this(out, "\n");
	}

	public StatementWriter(Writer out, String lineSeparator) throws IOException
	{
		this.lineSeparator = lineSeparator;
		this.out = out;
	}
	
	public StatementWriter() throws IOException
	{
		this("\n");
	}

	public StatementWriter(String lineSeparator) throws IOException
	{
		this.lineSeparator = lineSeparator;
		statements = new LinkedList<String>();
		buf = new StringBuilder();
	}
	
	public List<String> getStatements()
	{
		return statements;
	}
	
  public void addNewLine() throws IOException {
  	if (out != null)
  	{
  		out.append(lineSeparator);
  	}
  	else
  	{
  		statements.add(buf.toString());
  		buf.setLength(0);
  	}
  }

	@Override
  public void flush() throws IOException
  {
		if (out != null)
		{
			out.flush();
		}
  }
  
	@Override
	public void close() throws IOException {
		if (out != null)
		{
			out.flush();
		}
		else
		{
			addNewLine();
		}
	}

	@Override
	public Appendable append(CharSequence csq) throws IOException {
		if (out != null)
		{
			out.append(csq);
		}
		else
		{
			buf.append(csq);
		}
		return this;
	}

	@Override
	public Appendable append(CharSequence csq, int start, int end) throws IOException {
		if (out != null)
		{
			out.append(csq, start, end);
		}
		else
		{
			buf.append(csq, start, end);
		}
		return this;
	}

	@Override
	public Appendable append(char c) throws IOException {
		if (out != null)
		{
			out.append(c);
		}
		else
		{
			buf.append(c);
		}
		return this;
	}
}
