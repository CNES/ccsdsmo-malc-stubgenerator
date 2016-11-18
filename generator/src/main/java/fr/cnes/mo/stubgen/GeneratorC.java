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
package fr.cnes.mo.stubgen;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;

import esa.mo.tools.stubgen.GeneratorBase;
import esa.mo.tools.stubgen.GeneratorConfiguration;
import esa.mo.tools.stubgen.StubUtils;
import esa.mo.tools.stubgen.specification.AttributeTypeDetails;
import esa.mo.tools.stubgen.specification.CompositeField;
import esa.mo.tools.stubgen.specification.OperationSummary;
import esa.mo.tools.stubgen.specification.ServiceSummary;
import esa.mo.tools.stubgen.specification.StdStrings;
import esa.mo.tools.stubgen.specification.TypeInfo;
import esa.mo.tools.stubgen.specification.TypeUtils;
import esa.mo.tools.stubgen.writers.LanguageWriter;
import esa.mo.tools.stubgen.writers.TargetWriter;
import esa.mo.xsd.AreaType;
import esa.mo.xsd.AttributeType;
import esa.mo.xsd.CompositeType;
import esa.mo.xsd.ElementReferenceWithCommentType;
import esa.mo.xsd.EnumerationType;
import esa.mo.xsd.EnumerationType.Item;
import esa.mo.xsd.ErrorDefinitionType;
import esa.mo.xsd.ErrorReferenceType;
import esa.mo.xsd.FundamentalType;
import esa.mo.xsd.InvokeOperationType;
import esa.mo.xsd.OperationErrorList;
import esa.mo.xsd.OperationType;
import esa.mo.xsd.ProgressOperationType;
import esa.mo.xsd.PubSubOperationType;
import esa.mo.xsd.RequestOperationType;
import esa.mo.xsd.ServiceType;
import esa.mo.xsd.SpecificationType;
import esa.mo.xsd.SubmitOperationType;
import esa.mo.xsd.TypeReference;

/**
 * Generates code from MAL services specifications to use with the C API of the MAL from CNES.
 * This code uses the MAL code generation framework from ESA.
 */
public class GeneratorC extends GeneratorBase
{
  // list of all enumeration types, with their malbinary encoding size
  private final Map<TypeKey, MalbinaryEnumSize> enumTypesMBSize = new TreeMap<TypeKey, MalbinaryEnumSize>();
  
  // define how arrays are referenced : [] or *
  // TODO: remove this choice, [] does not compile
  private final String BRACKETS = "*";
  
	// generation for the transports malbinary and malsplitbinary
	// currently statically set to true
	private boolean generateTransportMalbinary;
	private static final String transportMalbinary = "malbinary";
	private boolean generateTransportMalsplitbinary;
	private static final String transportMalsplitbinary = "malsplitbinary";
	
	// specify a prefix for structure fields
	// so that they do not match C/C++ reserved keywords
	private static final String fieldPrefix = "f_";
	
	// generate all areas in a single zproject
	// and build the project.xml file
	private boolean singleZproject = true;
	private String zprojectName = "generated_areas";
	List<String> zareas;
	List<String> zclasses;
  
  /**
   * Constructor used by the StubGenerator main.
   *
   * @param logger The logger to use.
   */
  public GeneratorC(org.apache.maven.plugin.logging.Log logger)
  {
    super(logger, new GeneratorConfiguration("", "", "factory", "body", "_", "(Object[]) null",
                    "MALSendOperation",
                    "MALSubmitOperation",
                    "MALRequestOperation",
                    "MALInvokeOperation",
                    "MALProgressOperation",
                    "MALPubSubOperation"));
    zareas = new ArrayList<String>();
    zclasses = new ArrayList<String>();
    
    generateTransportMalbinary = Boolean.getBoolean("generateTransportMalbinary");
    generateTransportMalsplitbinary = Boolean.getBoolean("generateTransportMalsplitbinary");
    if (!generateTransportMalbinary && !generateTransportMalsplitbinary) {
    	// keep old behavior and generate both encoding
    	generateTransportMalbinary = true;
    	generateTransportMalsplitbinary = true;
    }
    
    zprojectName = System.getProperty("zprojectName", zprojectName);
  }

  @Override
  public String getShortName()
  {
    return "C";
  }

  @Override
  public String getDescription()
  {
  	return "Generates a C language mapping.";
  }
  
  @Override
  public void init(String destinationFolderName,
      boolean generateStructures,
      boolean generateCOM,
      Map<String, String> packageBindings,
      Map<String, String> extraProperties) throws IOException
  {
	  // the generateStructures field exists in the Generator interface, but it is unknown from the GeneratorBase base class
  	if (!generateStructures) {
  		throw new IOException("unsupported option generateStructures=false");
  	}
    super.init(destinationFolderName, true, generateCOM, packageBindings, extraProperties);

    // initializes the mapping of the attribute types
    // "pointer types" are identified with a trailing '*'
    // convention for the map type name is mal_<typeName.toLowerCase()>_t
    // generation code makes use of this convention, notably for the list type
    //
    // The following statements activate code from the base class GeneratorConfiguration.
    // We only use native types (3rd parameter true) so that complex code from this class is not used.
    addAttributeType(StdStrings.MAL, StdStrings.BLOB, true, "mal_blob_t *", "");
    addAttributeType(StdStrings.MAL, StdStrings.BOOLEAN, true, "mal_boolean_t", "false");
    addAttributeType(StdStrings.MAL, StdStrings.DOUBLE, true, "mal_double_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.DURATION, true, "mal_duration_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.FLOAT, true, "mal_float_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.INTEGER, true, "mal_integer_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.IDENTIFIER, true, "mal_identifier_t *", "");
    addAttributeType(StdStrings.MAL, StdStrings.LONG, true, "mal_long_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.OCTET, true, "mal_octet_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.SHORT, true, "mal_short_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.UINTEGER, true, "mal_uinteger_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.ULONG, true, "mal_ulong_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.UOCTET, true, "mal_uoctet_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.USHORT, true, "mal_ushort_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.STRING, true, "mal_string_t *", "");
    addAttributeType(StdStrings.MAL, StdStrings.TIME, true, "mal_time_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.FINETIME, true, "mal_finetime_t", "");
    addAttributeType(StdStrings.MAL, StdStrings.URI, true, "mal_uri_t *", "");
  }

  @Override
  public void postinit(String destinationFolderName,
      boolean generateStructures,
      boolean generateCOM,
      Map<String, String> packageBindings,
      Map<String, String> extraProperties) throws IOException
  {
	  // the generateStructures field exists in the Generator interface, but it is unknown from the GeneratorBase base class
  	if (!generateStructures) {
  		throw new IOException("unsupported option generateStructures=false");
  	}
    super.postinit(destinationFolderName, true, generateCOM, packageBindings, extraProperties);
    
    // the GeneratorLangs class declares here XML attribute types, seems unnecessary for our usage
  }

  @Override
  public void compile(String destinationFolderName, SpecificationType spec, JAXBElement rootNode) throws IOException, JAXBException
  {
    File destFolder = new File(destinationFolderName);
    // make sure the folder exists
    if (!destFolder.exists() && !destFolder.mkdirs())
    {
      throw new FileNotFoundException("Failed to create directory: " + destFolder.getPath());
    }
    
    for (AreaType area : spec.getArea())
    {
      processArea(destFolder, area);
    }
    
    // generate the zproject project.xml file
    if (singleZproject) {
    	generateZproject(destFolder);
    }
  }

	@Override
  public void close(String destinationFolderName) throws IOException
  {
  	super.close(destinationFolderName);
  }

  /**
   * Function used for debug.
   * Rename processArea as doProcessArea then uncomment.
   * 
  protected void processArea(File destinationFolder, AreaType area) throws IOException
  {
  	try
  	{
  		doProcessArea(destinationFolder, area);
  	}
  	catch (Exception exc)
  	{
  		System.out.println(exc);
  		if (exc.getCause() != null)
  		{
  			System.out.println("cause: " + exc.getCause());
  		}
  		exc.printStackTrace(System.out);
  	}
  }
   */
  
  protected void processArea(File destinationFolder, AreaType area) throws IOException
  {
    String comment;
    
    if ((!area.getName().equalsIgnoreCase(StdStrings.COM)) || (generateCOM()))
    {
      getLog().info("Processing area: " + area.getName());
      AreaContext areaContext = new AreaContext(destinationFolder, area);

      zareas.add(areaContext.areaNameL);
      // add area to the list of zproject classes
      // add first so that the <area>_library.h generated by zproject is correct
      zclasses.add(areaContext.areaNameL);
      
      // write the opening statements in the global files
      areaContext.areaH.openDefine();
      areaContext.areaH.openC();
      
      // initialize the .c file
      areaContext.areaC.addInclude(areaContext.areaNameL + ".h");
      
      // if area level types exist
      if (true && (null != area.getDataTypes()) && !area.getDataTypes().getFundamentalOrAttributeOrComposite().isEmpty())
      {
        // create area level data types
        for (Object oType : area.getDataTypes().getFundamentalOrAttributeOrComposite())
        {
        	// despite the name of the property, it looks like enumerations actually are in the list
          if (oType instanceof FundamentalType)
          {
          	/*
          	 * TODO Area defined types
          	 * If the stub generator should process the MAL specifications.
          	 */
          	throw new IllegalArgumentException("not yet implemented: area fundamental type");
          }
          else if (oType instanceof AttributeType)
          {
          	/*
          	 * TODO Area defined types
          	 * If the stub generator should process the MAL specifications.
          	 */
          	throw new IllegalArgumentException("not yet implemented: area attribute type");
          }
          else if (oType instanceof CompositeType)
          {
            createComposite(areaContext.areaFolder, areaContext, null, (CompositeType) oType);
          }
          else if (oType instanceof EnumerationType)
          {
            createEnumeration(areaContext.areaFolder, areaContext, null, (EnumerationType) oType);
          }
          else
          {
            throw new IllegalArgumentException("Unexpected area (" + area.getName() + ") level datatype of " + oType.getClass().getName());
          }
        }
      }
      // create services
      for (ServiceType service : area.getService())
      {
        processService(areaContext, service);
      }
      
    	areaContext.areaH.addInclude("mal.h");
      if (generateTransportMalbinary)
      {
      	areaContext.areaH.addInclude("malbinary.h");
      }
      if (generateTransportMalsplitbinary)
      {
      	areaContext.areaH.addInclude("malsplitbinary.h");
      }
      areaContext.areaH.addNewLine();

      // define the generic decoding function for the area (it is actually generic for the application)
      addGenericParamDecodingFunctions(areaContext);
      
      // write the standard area identifiers
      comment = "standard area identifiers";
      areaContext.areaH.addNewLine();
      areaContext.areaH.addSingleLineComment(comment);
      areaContext.areaH.addAreaDefine("AREA_NUMBER", String.valueOf(area.getNumber()));
      areaContext.areaH.addAreaDefine("AREA_VERSION", String.valueOf(area.getVersion()));

      // write the types part of the <area>.h file
      areaContext.areaH.addStatements(areaContext.areaHTypesW);

      // include the required areas definitions
      comment = "include required areas definitions";
      areaContext.areaH.addNewLine();
      areaContext.areaH.addSingleLineComment(comment);
      for (String reqArea : areaContext.reqAreas)
      {
      	// do not include mal.h and the currently generated <area>.h
      	if (! areaContext.area.getName().equals(reqArea) &&
      			! StdStrings.MAL.equals(reqArea))
      		areaContext.areaH.addInclude(reqArea.toLowerCase() + ".h");
      }
      
      // write the main content of the <area>.h file
      areaContext.areaH.addStatements(areaContext.areaHContentW);

      // test function
      addAreaTestFunction(areaContext);

      // include the area structures specific files
      areaContext.areaH.addNewLine();
      areaContext.areaH.addStatements(areaContext.structureIncludesW);
      
      // write the closing statements in the global files
      areaContext.areaH.closeC();
      areaContext.areaH.closeDefine();
      
      // finalize the area files
      areaContext.areaH.flush();
      areaContext.areaH.close();
      areaContext.areaC.flush();
      areaContext.areaC.close();
    }
  }

  protected void processService(AreaContext areaContext, ServiceType service) throws IOException
  {
    getLog().info("Processing service: " + service.getName());
  	ServiceContext serviceContext = new ServiceContext(areaContext, service);

    String comment = "standard service identifiers";
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
  	areaContext.areaHTypes.addDefine(areaContext.areaNameL.toUpperCase() + "_" + serviceContext.serviceNameL.toUpperCase() + "_SERVICE_NUMBER", String.valueOf(service.getNumber()));
  	
    // if service level types exist
    if ((null != service.getDataTypes()) && !service.getDataTypes().getCompositeOrEnumeration().isEmpty())
    {
      // all files are created in the same directory
      for (Object oType : service.getDataTypes().getCompositeOrEnumeration())
      {
        if (oType instanceof EnumerationType)
        {
          createEnumeration(serviceContext.serviceFolder, areaContext, serviceContext, (EnumerationType) oType);
        }
        else if (oType instanceof CompositeType)
        {
          createComposite(serviceContext.serviceFolder, areaContext, serviceContext, (CompositeType) oType);
        }
        else
        {
          throw new IllegalArgumentException("Unexpected service (" + areaContext.area.getName() + ":" + service.getName() + ") level datatype of " + oType.getClass().getName());
        }
      }
    }

    // don't create operation classes for COM as this is autogenerated in the specific services
    if (! serviceContext.summary.isComService())
    {
    	for (OperationSummary op : serviceContext.summary.getOperations())
      {
    		processOperation(serviceContext, op);
      }
    }

  }

  protected void processOperation(ServiceContext serviceContext, OperationSummary operation) throws IOException
  {
    getLog().info("Processing operation: " + operation.getName());
  	OperationContext opContext = new OperationContext(serviceContext, operation);
  	OperationErrorList errors = null;

    String comment = "generated code for operation " + opContext.qfOpNameL;
    CFileWriter areaH = serviceContext.areaContext.areaHContent;
    CFileWriter areaC = serviceContext.areaContext.areaC;
    areaH.addNewLine();
    areaH.addSingleLineComment(comment);
    areaC.addNewLine();
    areaC.addSingleLineComment(comment);

    areaH.addDefine(opContext.qfOpNameL.toUpperCase() + "_OPERATION_NUMBER", String.valueOf(operation.getNumber()));
  	
    switch (operation.getPattern())
    {
    	case SEND_OP:
    	{
    		addInitInteractionFunction(opContext, "send", operation.getArgTypes());
    		break;
    	}
    	case SUBMIT_OP:
    	{
    		addInitInteractionFunction(opContext, "submit", operation.getArgTypes());
    		addResultInteractionFunctions(opContext, "submit_ack", operation.getAckTypes());
    		OperationType opType = operation.getOriginalOp();
    		if (opType == null || ! (opType instanceof SubmitOperationType))
    		{
    			throw new IllegalStateException("Submit operation " + opContext.qfOpNameL + " is not typed SubmitOperationType");
    		}
    		errors = ((SubmitOperationType) opType).getErrors();
    		break;
    	}
    	case REQUEST_OP:
    	{
    		addInitInteractionFunction(opContext, "request", operation.getArgTypes());
    		addResultInteractionFunctions(opContext, "request_response", operation.getRetTypes());
    		OperationType opType = operation.getOriginalOp();
    		if (opType == null || ! (opType instanceof RequestOperationType))
    		{
    			throw new IllegalStateException("Request operation " + opContext.qfOpNameL + " is not typed RequestOperationType");
    		}
    		errors = ((RequestOperationType) opType).getErrors();
    		break;
    	}
    	case INVOKE_OP:
    	{
    		addInitInteractionFunction(opContext, "invoke", operation.getArgTypes());
    		addResultInteractionFunctions(opContext, "invoke_ack", operation.getAckTypes());
    		addResultInteractionFunctions(opContext, "invoke_response", operation.getRetTypes());
    		OperationType opType = operation.getOriginalOp();
    		if (opType == null || ! (opType instanceof InvokeOperationType))
    		{
    			throw new IllegalStateException("Invoke operation " + opContext.qfOpNameL + " is not typed InvokeOperationType");
    		}
    		errors = ((InvokeOperationType) opType).getErrors();
    		break;
    	}
    	case PROGRESS_OP:
    	{
    		addInitInteractionFunction(opContext, "progress", operation.getArgTypes());
    		addResultInteractionFunctions(opContext, "progress_ack", operation.getAckTypes());
    		addResultInteractionFunctions(opContext, "progress_update", operation.getUpdateTypes());
    		addResultInteractionFunctions(opContext, "progress_response", operation.getRetTypes());
    		OperationType opType = operation.getOriginalOp();
    		if (opType == null || ! (opType instanceof ProgressOperationType))
    		{
    			throw new IllegalStateException("Progress operation " + opContext.qfOpNameL + " is not typed ProgressOperationType");
    		}
    		errors = ((ProgressOperationType) opType).getErrors();
    		break;
    	}
    	case PUBSUB_OP:
    	{
    		generatePubSubEncodingRelatedParameters(opContext, "update", operation.getUpdateTypes());
    		addRegisterFunction(opContext, "register");
    		addPublishRegisterFunction(opContext, "publish_register");
    		addPublishFunction(opContext, "publish", operation.getUpdateTypes());
    		addDeregisterFunction(opContext, "deregister");
    		addPublishDeregisterFunction(opContext, "publish_deregister");
    		OperationType opType = operation.getOriginalOp();
    		if (opType == null || ! (opType instanceof PubSubOperationType))
    		{
    			throw new IllegalStateException("PubSub operation " + opContext.qfOpNameL + " is not typed PubSubOperationType");
    		}
    		errors = ((PubSubOperationType) opType).getErrors();
    		break;
    	}
    }
    if (errors != null)
    {
    	// declare the errors
    	processOpErrors(opContext, errors);

    	addInteractionErrorXcodingFunctions(opContext, errors);
    }
  }
  
  protected void processOpErrors(OperationContext opContext, OperationErrorList errors) throws IOException
  {
  	if (errors == null)
  		return;
  	List<Object> errorList = errors.getErrorOrErrorRef();
  	if (errorList.isEmpty())
  		return;

  	for (Object error : errorList)
  	{
  		if (error instanceof ErrorDefinitionType)
  		{
  			ErrorDefinitionType errorDef = (ErrorDefinitionType) error;
  			// declare the error in the area include file
  			// #define <AREA>_<SERVICE>_<OPERATION>_<ERROR>_ERROR_NUMBER <error number>
  			opContext.serviceContext.areaContext.areaH.addDefine(
  					opContext.qfOpNameL.toUpperCase() + "_" + errorDef.getName().toUpperCase() + "_ERROR_NUMBER",
  					Long.toString(errorDef.getNumber()));
  		}
  		else if (error instanceof ErrorReferenceType)
  		{
  			// should include the reference area
  			// currently unnecessary as all areas are included
  		}
  		else
  		{
  			throw new IllegalStateException("unexpected error structure: " + error.getClass().getName());
  		}
  	}
  }
  
  protected List<TypeReference> getOpErrorTypes(OperationErrorList errors)
  {
  	// error management is not optimal here
  	// we must generate encoding functions for all possible types, and an abstract decoding function.
  	// we have considered 3 strategies :
  	// 1- build a list of unique acceptable types and call the proper generation functions with those types
  	// 2- call the generation functions with all the error types, and do not generate codes for types already handled for this operation
  	// 3- call the generation functions for all the types in the area (i.e. MAL::Element)
  	// Strategy 3 is the simplest to implement, it has been chosen with a little optimizing
  	List<Object> errorList = errors.getErrorOrErrorRef();
  	if (errorList.isEmpty())
  	{
  		return null;
  	}
  	
  	List<TypeReference> opErrorTypes = new ArrayList<TypeReference>();
  	
  	for (Object error : errorList)
  	{
  		ElementReferenceWithCommentType errorType = null;
  		if (error instanceof ErrorDefinitionType)
  		{
  			errorType = ((ErrorDefinitionType) error).getExtraInformation();
  		}
  		else if (error instanceof ErrorReferenceType)
  		{
  			errorType = ((ErrorReferenceType) error).getExtraInformation();
  		}
  		else
  		{
  			throw new IllegalStateException("unexpected error structure: " + error.getClass().getName());
  		}
  		if (errorType != null)
  		{
			// Check that this type is not already in the list
			boolean contains = false;
			TypeReference errorTypeRef = errorType.getType();
			for (TypeReference typeReference : opErrorTypes) {
				// Test equality of the references
				if (typeReference == errorTypeRef)
					// instance equality
					contains = true;
				else if (typeReference.isList() == errorTypeRef.isList() &&
						Objects.equals(typeReference.getArea(), errorTypeRef.getArea()) &&
						Objects.equals(typeReference.getService(), errorTypeRef.getService()) &&
						Objects.equals(typeReference.getName(), errorTypeRef.getName()))
					// Values equality
					contains = true;
			}
			if (!contains)
				opErrorTypes.add(errorTypeRef);
  		}
  	}
	return opErrorTypes;
  }
  
  @Override
  protected CompositeField createCompositeElementsDetails(TargetWriter file, boolean checkType, String fieldName, TypeReference elementType, boolean isStructure, boolean canBeNull, String comment)
  {
  	// this function is currently called in the C generator with a null file parameter
  	// type dependencies are handled in another way
    CompositeField ele;

    String typeName = elementType.getName();

    if (checkType && !isKnownType(elementType))
    {
      getLog().warn("Unknown type (" + new TypeKey(elementType)
              + ") is being referenced as field (" + fieldName + ")");
    }

    if (elementType.isList())
    {
    	String fqTypeName;
    	if (isAttributeNativeType(elementType))
    	{
    		fqTypeName = createElementType((LanguageWriter) file, StdStrings.MAL, null, typeName + "List");
    	}
    	else
    	{
    		fqTypeName = createElementType((LanguageWriter) file, elementType, true) + "List";
    	}

    	String newCall = null;
    	String encCall = null;
    	if (!isAbstract(elementType))
    	{
    		newCall = "new " + fqTypeName + "()";
    		encCall = StdStrings.ELEMENT;
    	}

    	ele = new CompositeField(fqTypeName, elementType, fieldName, elementType.isList(), canBeNull, false, encCall, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, newCall, comment);
    }
    else
    {
      if (isAttributeType(elementType))
      {
        AttributeTypeDetails details = getAttributeDetails(elementType);
        String fqTypeName = createElementType((LanguageWriter) file, elementType, isStructure);
        ele = new CompositeField(details.getTargetType(), elementType, fieldName, elementType.isList(), canBeNull, false, typeName, "", typeName, false, "new " + fqTypeName + "()", comment);
      }
      else
      {
        TypeReference elementTypeIndir = elementType;

        // have to work around the fact that JAXB does not replicate the XML type name into Java in all cases
        if ("XML".equalsIgnoreCase(elementType.getArea()))
        {
          elementTypeIndir = TypeUtils.createTypeReference(elementType.getArea(), elementType.getService(), StubUtils.preCap(elementType.getName()), elementType.isList());
        }

        String fqTypeName = createElementType((LanguageWriter) file, elementTypeIndir, isStructure);

        if (isEnum(elementType))
        {
          EnumerationType typ = getEnum(elementType);
          String firstEle = fqTypeName + "." + typ.getItem().get(0).getValue();
          ele = new CompositeField(fqTypeName, elementType, fieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, firstEle, comment);
        }
        else if (StdStrings.ATTRIBUTE.equals(typeName))
        {
          ele = new CompositeField(fqTypeName, elementType, fieldName, elementType.isList(), canBeNull, false, StdStrings.ATTRIBUTE, "(" + fqTypeName + ") ", StdStrings.ATTRIBUTE, false, "", comment);
        }
        else if (StdStrings.ELEMENT.equals(typeName))
        {
          ele = new CompositeField(fqTypeName, elementType, fieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, false, "", comment);
        }
        else
        {
          ele = new CompositeField(fqTypeName, elementType, fieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, "new " + fqTypeName + "()", comment);
        }
      }
    }

    return ele;
  }

  protected void createEnumeration(File folder, AreaContext areaContext, ServiceContext serviceContext, EnumerationType enumeration) throws IOException
  {
  	String comment;
    String enumName = enumeration.getName();
    AreaType area = areaContext.area;
    ServiceType service = null;
    if (serviceContext != null)
    {
    	service = serviceContext.summary.getService();
    }
    String malEnumName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + enumName;

    getLog().info("Creating enumeration " + malEnumName);
    
    // fill in the enumTypesMBSize table
    int enumSize = enumeration.getItem().size();
    TypeReference enumTypeRef = new TypeReference();
    enumTypeRef.setArea(area.getName());
    if (service != null)
    {
    	enumTypeRef.setService(service.getName());
    }
    enumTypeRef.setName(enumeration.getName());
    MalbinaryEnumSize mbSize = getEnumTypeMBSize(enumTypeRef, enumeration);
    
    // define the enumeration type itself
    // the enumeration is defined only in the <area>.h file

    StringBuilder buf = new StringBuilder();
    buf.append(areaContext.areaNameL);
    buf.append("_");
    if (serviceContext != null)
    {
    	buf.append(serviceContext.serviceNameL);
    	buf.append("_");
    }
    buf.append(enumName.toLowerCase());
    String mapEnumNameL = buf.toString();
    String mapEnumNameU = mapEnumNameL.toUpperCase();

    comment = "generated code for enumeration " + mapEnumNameL;
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    
    // typedef enum {
    //	<AREA>_[<SERVICE>_]<ENUMERATION>_<ENUMERATED NAME>,
    //	...
    // } <area>_[<service>_]<enumeration>_t;
    areaContext.areaHTypes.openTypedefEnum(null);
    for (int i = 0; i < enumSize; i++)
    {
      Item item = enumeration.getItem().get(i);
      // the nValue of the enumerated variable is ignored at this stage
      // we keep the default value set by the C language, optimal for encoding
      areaContext.areaHTypes.addTypedefEnumElement(mapEnumNameU + "_" + item.getValue().toUpperCase(), null, i == (enumSize-1));
    }
    areaContext.areaHTypes.closeTypedefEnum(mapEnumNameL + "_t");
    
    // create an array holding the enumerated values in the <area>.c file
    // only the indexes of the enumerated variables are kept in the <area>.h
    //	int <AREA>_<SERVICE>_<ENUMERATION>_NUMERIC_VALUES[] = {
    //		<numeric value>,
    //		...
    //	}
    areaContext.areaC.addNewLine();
    areaContext.areaC.addStatement("int " + mapEnumNameL.toUpperCase() + "_NUMERIC_VALUES[] =");
    areaContext.areaC.addStatement("{", 1);
    for (int i = 0; i < enumSize; i++)
    {
      Item item = enumeration.getItem().get(i);
      areaContext.areaC.addStatement(Long.toString(item.getNvalue()) + (i == (enumSize-1) ? "" : ","));
    }
    areaContext.areaC.addStatement("};", -1, true);
    
    // create the type short form
    comment = "short form for enumeration type " + mapEnumNameL;
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    // #define <AREA>_[<SERVICE>_]<ENUMERATION>_SHORT_FORM <type absolute short form>
    long typeShortForm = getAbsoluteShortForm(
				area.getNumber(),
				service == null ? 0 : service.getNumber(),
				area.getVersion(),
				(int) enumeration.getShortFormPart());
    areaContext.areaHTypes.addDefine(
    		mapEnumNameU + "_SHORT_FORM",
    		"0x" + Long.toHexString(typeShortForm) + "L");
    
    // create the list type associated with the enumeration
    createEnumerationList(folder, areaContext, serviceContext, enumeration, mapEnumNameL, mbSize);
    
    // create the type short form
    comment = "short form for list of enumeration type " + mapEnumNameL;
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    // #define <AREA>_[<SERVICE>_]<ENUMERATION>_LIST_SHORT_FORM <type absolute short form>
    typeShortForm = getAbsoluteShortForm(
    		area.getNumber(),
    		service == null ? 0 : service.getNumber(),
				area.getVersion(),
				- (int) enumeration.getShortFormPart());
    areaContext.areaHTypes.addDefine(
    		mapEnumNameU + "_LIST_SHORT_FORM",
    		"0x" + Long.toHexString(typeShortForm) + "L");

    areaContext.areaHTypes.flush();
    areaContext.areaHContent.flush();
  }

  protected void createEnumerationList(File folder, AreaContext areaContext, ServiceContext serviceContext, EnumerationType enumeration, String mapEnumNameL, MalbinaryEnumSize mbSize) throws IOException
  {
  	String comment;
    String enumName = enumeration.getName();
    AreaType area = areaContext.area;
    ServiceType service = null;
    if (serviceContext != null)
    {
    	service = serviceContext.summary.getService();
    }
    String malEnumName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + enumName;

    getLog().info("Creating list type for enumeration " + malEnumName);

    // declare the type in <area>.h
    // typedef _<area>_[<service>_]<type>_list_t <area>_[<service>_]<type>_list_t;
    areaContext.areaHTypes.addTypedefStruct("_" + mapEnumNameL + "_list_t", mapEnumNameL + "_list_t");
    
    // we create an <enumeration>_list.h and an <enumeration>_list.c files
    // create the Writer structures
    String nameBase = mapEnumNameL + "_list";
    TypeListWriter enumListH = new TypeListWriter(folder, nameBase, "h");
    TypeListWriter enumListC = new TypeListWriter(folder, nameBase, "c");
    zclasses.add(nameBase);

    // write the opening statements in the global files
    enumListH.openDefine();
    enumListH.openC();

    // initialize the .c file
    // #include "<area>.h"
    enumListC.addInclude(areaContext.areaNameL + ".h");
    enumListC.addNewLine();

    // define the structure
    // struct _<area>_[<service>_]<enumeration>_list_t {
    //	unsigned int element_count;
    //	bool *presence_flags;
    //	<area>_[<service>_]<enumeration>_t *content;
    // };
    enumListC.openStruct("_" + mapEnumNameL + "_list_t");
    enumListC.addStructField("unsigned int", "element_count");
    enumListC.addStructField("bool " + BRACKETS, "presence_flags");
    enumListC.addStructField(mapEnumNameL + "_t " + BRACKETS, "content");
    enumListC.closeStruct();
    
    // declare the constructor prototype in the .h file and define it in the .c file
    comment = "default constructor";
    enumListH.addNewLine();
    enumListH.addSingleLineComment(comment);
    enumListC.addNewLine();
    enumListC.addSingleLineComment(comment);
    
    // <area>_[<service>_]<enumeration>_list_t *<area>_[<service>_]<enumeration>_list_new(unsigned int element_count);
    enumListH.openFunctionPrototype(mapEnumNameL + "_list_t *", mapEnumNameL + "_list_new", 1);
    enumListH.addFunctionParameter("unsigned int", "element_count", true);
    enumListH.closeFunctionPrototype();
    
    // <area>_[<service>_]<enumeration>_list_t *<area>_[<service>_]<enumeration>_list_new(unsigned int element_count) { 
    //		<area>_[<service>_]<enumeration>_list_t *self = (<area>_[<service>_]<enumeration>_list_t *) calloc(1, sizeof(<area>_[<service>_]<enumeration>_list_t));
    //		if (!self) return NULL;
    //		self->element_count = element_count;
    //		if (element_count == 0) return self;
    //		self->presence_flags = (bool *) calloc(element_count, sizeof(bool));
    //		if (!self->presence_flags) {
    //			free(self);
    //			return NULL;
    //		}
    //		self->content = (<area>_[<service>_]<enumeration>_t *) calloc(element_count, sizeof(<area>_[<service>_]<enumeration>_t));
    //		if (!self->content) {
    //			free(self->presence_flags);
    //			free(self);
    //			return NULL;
    //		}
    //		return self;
    // }
    enumListC.openFunction(mapEnumNameL + "_list_t *", mapEnumNameL + "_list_new", 1);
    enumListC.addFunctionParameter("unsigned int", "element_count", true);
    enumListC.openFunctionBody();
    enumListC.addStatement(mapEnumNameL + "_list_t *self = (" + mapEnumNameL + "_list_t *) calloc(1, sizeof(" + mapEnumNameL + "_list_t));");
    enumListC.addStatement("if (!self)", 1);
    enumListC.addStatement("return NULL;", -1);
    enumListC.addStatement("self->element_count = element_count;");
    enumListC.addStatement("if (element_count == 0)", 1);
    enumListC.addStatement("return self;", -1);
    enumListC.addStatement("self->presence_flags = (bool *) calloc(element_count, sizeof(bool));");
    enumListC.addStatement("if (!self->presence_flags)");
    enumListC.openBlock();
    enumListC.addStatement("free(self);");
    enumListC.addStatement("return NULL;");
    enumListC.closeBlock();
    enumListC.addStatement("self->content = (" + mapEnumNameL + "_t *) calloc(element_count, sizeof(" + mapEnumNameL + "_t));");
    enumListC.addStatement("if (!self->content)");
    enumListC.openBlock();
    enumListC.addStatement("free(self->presence_flags);");
    enumListC.addStatement("free(self);");
    enumListC.addStatement("return NULL;");
    enumListC.closeBlock();
    enumListC.addStatement("return self;");
    enumListC.closeFunctionBody();

    // declare the destructor prototype in the .h file and define it in the .c file
    comment = "destructor, free the list and its content";
    enumListH.addNewLine();
    enumListH.addSingleLineComment(comment);
    enumListC.addNewLine();
    enumListC.addSingleLineComment(comment);
    
    // void <area>_[<service>_]<enum>_list_destroy(<area>_[<service>_]<enum>_list_t **self_p);
    enumListH.openFunctionPrototype("void", mapEnumNameL + "_list_destroy", 1);
    enumListH.addFunctionParameter(mapEnumNameL + "_list_t **", "self_p", true);
    enumListH.closeFunctionPrototype();

    // void <area>_[<service>_]<enum>_list_destroy(<area>_[<service>_]<enum>_list_t **self_p) {
    //	if ((*self_p)->element_count > 0) {
    //		free((*self_p)->presence_flags);
    //		free((*self_p)->content);
    //	}
    //	free (*self_p);
    //	(*self_p) = NULL;
    // }
    enumListC.openFunction("void", mapEnumNameL + "_list_destroy", 1);
    enumListC.addFunctionParameter(mapEnumNameL + "_list_t **", "self_p", true);
    enumListC.openFunctionBody();
    enumListC.addStatement("if ((*self_p)->element_count > 0)");
    enumListC.openBlock();
    enumListC.addStatement("free((*self_p)->presence_flags);");
    enumListC.addStatement("free((*self_p)->content);");
    enumListC.closeBlock();
    enumListC.addStatement("free (*self_p);");
    enumListC.addStatement("(*self_p) = NULL;");
    enumListC.closeFunctionBody();
    
    // declare the accessors prototypes in the .h file and define them in the .c file
    comment = "fields accessors for enumeration list " + mapEnumNameL + "_list";
    enumListH.addNewLine();
    enumListH.addSingleLineComment(comment);
    enumListC.addNewLine();
    enumListC.addSingleLineComment(comment);
    
    // unsigned int <area>_[<service>_]<enumeration>_list_get_element_count(<area>_[<service>_]<enumeration>_list_t *self);
    enumListH.openFunctionPrototype("unsigned int", mapEnumNameL + "_list_get_element_count", 1);
    enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListH.closeFunctionPrototype();
    // bool *<area>_[<service>_]<enumeration>_list_get_presence_flags(<area>_[<service>_]<enumeration>_list_t *self);
    enumListH.openFunctionPrototype("bool " + BRACKETS, mapEnumNameL + "_list_get_presence_flags", 1);
    enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListH.closeFunctionPrototype();
    // <area>_[<service>_]<enumeration>_t *<area>_[<service>_]<enumeration>_list_get_content(<area>_[<service>_]<enumeration>_list_t *self);
    enumListH.openFunctionPrototype(mapEnumNameL + "_t " + BRACKETS, mapEnumNameL + "_list_get_content", 1);
    enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListH.closeFunctionPrototype();
    
    // unsigned int <area>_[<service>_]<enumeration>_list_get_element_count(<area>_[<service>_]<enumeration>_list_t *self) {
    //	return self->element_count;
    // }
    enumListC.openFunction("unsigned int", mapEnumNameL + "_list_get_element_count", 1);
    enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListC.openFunctionBody();
    enumListC.addStatement("return self->element_count;");
    enumListC.closeFunctionBody();
    // bool *<area>_[<service>_]<enumeration>_list_get_presence_flags(<area>_[<service>_]<enumeration>_list_t *self) {
    //	return self->presence_flags;
    // }
    enumListC.openFunction("bool " + BRACKETS, mapEnumNameL + "_list_get_presence_flags", 1);
    enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListC.openFunctionBody();
    enumListC.addStatement("return self->presence_flags;");
    enumListC.closeFunctionBody();
    // <area>_[<service>_]<enumeration>_t *<area>_[<service>_]<enumeration>_list_get_content(<area>_[<service>_]<enumeration>_list_t *self) {
    //	return self->content;
    // }
    enumListC.openFunction(mapEnumNameL + "_t " + BRACKETS, mapEnumNameL + "_list_get_content", 1);
    enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", true);
    enumListC.openFunctionBody();
    enumListC.addStatement("return self->content;");
    enumListC.closeFunctionBody();

    // declare the prototypes of the encoding functions in the .h file
    // and define them in the .c file
    if (generateTransportMalbinary || generateTransportMalsplitbinary)
    {
    	addEnumListEncodingFunctions(enumListH, enumListC, mapEnumNameL, mbSize);
    }

    // add a test function
    addEnumListTestFunction(enumListH, enumListC, mapEnumNameL);
    
    // write the closing statements in the global files
    enumListH.closeC();
    enumListH.closeDefine();
    
    // finalize the structure specific files
    enumListH.flush();
    enumListH.close();
    enumListC.flush();
    enumListC.close();
    
    // include the file in the main <area>.h
    // assumes that the file folder is the main folder for the area 
    areaContext.structureIncludes.addInclude(mapEnumNameL + "_list.h");
  }
  
  /**
   * Generate code for a composite type.
   * The type may be declared at the area level or at the service level.
   * @param folder folder to create the composite files in
   * @param areaContext context of the area defining the composite
   * @param serviceContext context of the service defining the composite, may be null
   * @param composite
   * @throws IOException
   */
  protected void createComposite(File folder, AreaContext areaContext, ServiceContext serviceContext, CompositeType composite) throws IOException
  {
  	String comment;
    AreaType area = areaContext.area;
    ServiceType service = null;
    if (serviceContext != null)
    {
    	service = serviceContext.summary.getService();
    }
    String malCompName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + composite.getName();

    getLog().info("Creating composite " + malCompName);

    // nothing is generated for abstract types
    boolean abstractComposite = (null == composite.getShortFormPart());
    if (abstractComposite)
    {
    	return;
    }
    
    comment = "generated code for composite " + malCompName;
    areaContext.areaHContent.addNewLine();
    areaContext.areaHContent.addSingleLineComment(comment);
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    
    CompositeContext compCtxt = new CompositeContext(areaContext, serviceContext, composite, folder);
    String mapCompNameL = compCtxt.mapCompNameL;
    String mapCompNameU = mapCompNameL.toUpperCase();

    // declare the type in <area>.h
    // typedef struct _<area>_[<service>_]<composite>_t <area>_[<service>_]<composite>_t;
    areaContext.areaHTypes.addTypedefStruct("_" + mapCompNameL + "_t", mapCompNameL + "_t");
    
    // we create a <composite>.h and a <composite>.c files
    // create the Writer structures
    CompositeHWriter compositeH = compCtxt.compositeH;
    CompositeCWriter compositeC = compCtxt.compositeC;
    zclasses.add(mapCompNameL);

    // include the file in the main <area>.h
    // assumes that the file folder is the main folder for the area 
    areaContext.structureIncludes.addInclude(mapCompNameL + ".h");
    
    // write the opening statements in the global files
    compositeH.openDefine();
    compositeH.openC();

    // initialize the .c file
    // #include "<area>.h"
    compositeC.addInclude(areaContext.areaNameL + ".h");
    compositeC.addNewLine();
    
    // generate all code related to the composite fields
    // and define the structure in the <composite>.c file
    processCompFields(compCtxt);

    // declare and define the composite constructor
    addCompositeConstructor(compCtxt);
    
    // declare the prototypes of the encoding functions in <composite>.h file
    // and define them in the <composite>.c file
    if (generateTransportMalbinary || generateTransportMalsplitbinary)
    {
    	addCompositeEncodingFunctions(compCtxt);
    }
    
    // declare and define the composite destructor
    addCompositeDestructor(compCtxt);
    
    // create the type short form in the <area>.h file
    comment = "short form for composite type " + malCompName;
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    // #define <AREA>_[<SERVICE>_]<COMPOSITE>_SHORT_FORM <type absolute short form>
    long typeShortForm = getAbsoluteShortForm(
				area.getNumber(),
				service == null ? 0 : service.getNumber(),
				area.getVersion(),
				composite.getShortFormPart().intValue());
    areaContext.areaHTypes.addDefine(
    		mapCompNameU + "_SHORT_FORM",
    		"0x" + Long.toHexString(typeShortForm) + "L");

    // create the list type associated to the composite
    createCompositeList(folder, compCtxt);
    
    // create the type short form
    comment = "short form for list of composite type " + malCompName;
    areaContext.areaHTypes.addNewLine();
    areaContext.areaHTypes.addSingleLineComment(comment);
    // #define <AREA>_[<SERVICE>_]<COMPOSITE>_LIST_SHORT_FORM <type absolute short form>
    typeShortForm = getAbsoluteShortForm(
    		area.getNumber(),
    		service == null ? 0 : service.getNumber(),
				area.getVersion(),
				-composite.getShortFormPart().intValue());
    areaContext.areaHTypes.addDefine(
    		mapCompNameU + "_LIST_SHORT_FORM",
    		"0x" + Long.toHexString(typeShortForm) + "L");

    // add a test function
    addCompositeTestFunction(compCtxt);
    
    // write the closing statements in the global files
    compositeH.closeC();
    compositeH.closeDefine();
    
    // finalize the structure specific files
    compositeH.flush();
    compositeH.close();
    compositeC.flush();
    compositeC.close();
  }
  
  protected void createCompositeList(File folder, CompositeContext compCtxt) throws IOException
  {
  	String comment;
  	StringBuilder buf = new StringBuilder();
  	buf.append(compCtxt.areaContext.area.getName());
  	if (compCtxt.serviceContext != null)
  	{
  		buf.append(":");
  		buf.append(compCtxt.serviceContext.summary.getService().getName());
  	}
		buf.append(":");
		buf.append(compCtxt.composite.getName());
    String malCompName = buf.toString();

    getLog().info("Creating list type for composite " + malCompName);

    // declare the type in <area>.h
    // typedef _<area>_[<service>_]<type>_list_t <area>_[<service>_]<type>_list_t;
    String mapCompListType = compCtxt.mapCompNameL + "_list_t";
    CFileWriter areaH = compCtxt.areaContext.areaHTypes;
    areaH.addTypedefStruct("_" + mapCompListType, mapCompListType);

    // we create a <composite>_list.h and a <composite>_list.c files
    // create the Writer structures
    String baseName = compCtxt.mapCompNameL + "_list";
    TypeListWriter compListH = new TypeListWriter(folder, baseName, "h");
    TypeListWriter compListC = new TypeListWriter(folder, baseName, "c");
    zclasses.add(baseName);

    // write the opening statements in the global files
    compListH.openDefine();
    compListH.openC();

    // initialize the .c file
    // #include "<area>.h"
    compListC.addInclude(compCtxt.areaContext.areaNameL + ".h");
    compListC.addNewLine();

    // define the structure
    // struct _<area>_[<service>_]<composite>_list_t {
    //	unsigned int element_count;
    //	<area>_[<service>_]<composite>_t **content;
    // };
    compListC.openStruct("_" + mapCompListType);
    compListC.addStructField("unsigned int", "element_count");
    compListC.addStructField(compCtxt.mapCompNameL + "_t *" + BRACKETS, "content");
    compListC.closeStruct();
    
    // declare the constructor prototype in the .h file and define it in the .c file
    comment = "default constructor";
    compListH.addNewLine();
    compListH.addSingleLineComment(comment);
    compListC.addNewLine();
    compListC.addSingleLineComment(comment);
    
    // <area>_[<service>_]<composite>_list_t *<area>_[<service>_]<composite>_list_new(unsigned int element_count);
    compListH.openFunctionPrototype(mapCompListType + " *", compCtxt.mapCompNameL + "_list_new", 1);
    compListH.addFunctionParameter("unsigned int", "element_count", true);
    compListH.closeFunctionPrototype();
    
    // <area>_[<service>_]<composite>_list_t *<area>_[<service>_]<composite>_list_new(unsigned int element_count) { 
    //		<area>_[<service>_]<composite>_list_t *self = (<area>_[<service>_]<composite>_list_t *) calloc(1, sizeof(<area>_[<service>_]<composite>_list_t));
    //		if (!self) return NULL;
    //		self->element_count = element_count;
    //		self->content = (<area>_[<service>_]<composite>_t **) calloc(element_count, sizeof(<area>_[<service>_]<composite>_t *));
    //		if (!self->content && (element_count > 0)) {
    //			free(self);
    //			return NULL;
    //		}
    //		return self;
    // }
    compListC.openFunction(mapCompListType + " *", compCtxt.mapCompNameL + "_list_new", 1);
    compListC.addFunctionParameter("unsigned int", "element_count", true);
    compListC.openFunctionBody();
    compListC.addStatement(mapCompListType + " *self = (" + mapCompListType + " *) calloc(1, sizeof(" + mapCompListType + "));");
    compListC.addStatement("if (!self)", 1);
    compListC.addStatement("return NULL;", -1);
    compListC.addStatement("self->element_count = element_count;");
    compListC.addStatement("self->content = (" + compCtxt.mapCompNameL + "_t **) calloc(element_count, sizeof(" + compCtxt.mapCompNameL + "_t *));");
    compListC.addStatement("if (!self->content && (element_count > 0))");
    compListC.openBlock();
    compListC.addStatement("free(self);");
    compListC.addStatement("return NULL;");
    compListC.closeBlock();
    compListC.addStatement("return self;");
    compListC.closeFunctionBody();

    // declare the destructor prototype in the .h file and define it in the .c file
    comment = "destructor, free the list, its content and its elements";
    compListH.addNewLine();
    compListH.addSingleLineComment(comment);
    compListC.addNewLine();
    compListC.addSingleLineComment(comment);
    
    // void <area>_[<service>_]<composite>_list_destroy(<area>_[<service>_]<composite>_list_t **self_p);
    compListH.openFunctionPrototype("void", compCtxt.mapCompNameL + "_list_destroy", 1);
    compListH.addFunctionParameter(mapCompListType + " **", "self_p", true);
    compListH.closeFunctionPrototype();

    // void <area>_[<service>_]<composite>_list_destroy(<area>_[<service>_]<composite>_list_t **self_p) {
    //	if ((*self_p)->element_count > 0) {
    //		for (int i = 0; i < (*self_p)->element_count; i++) {
    //			if ((*self_p)->content[i] != NULL)
    //				<area>_[<service>_]<composite>_destroy(&(*self_p)->content[i]);
    //		}
    //		free((*self_p)->content);
    //	}
    //	free (*self_p);
    //	(*self_p) = NULL;
    // }
    compListC.openFunction("void", compCtxt.mapCompNameL + "_list_destroy", 1);
    compListC.addFunctionParameter(mapCompListType + " **", "self_p", true);
    compListC.openFunctionBody();
    compListC.addStatement("if ((*self_p)->element_count > 0)");
    compListC.openBlock();
    compListC.addStatement("for (int i = 0; i < (*self_p)->element_count; i++)");
    compListC.openBlock();
    compListC.addStatement("if ((*self_p)->content[i] != NULL)", 1);
    compListC.addStatement(compCtxt.mapCompNameL + "_destroy(&(*self_p)->content[i]);", -1);
    compListC.closeBlock();
    compListC.addStatement("free((*self_p)->content);");
    compListC.closeBlock();
    compListC.addStatement("free (*self_p);");
    compListC.addStatement("(*self_p) = NULL;");
    compListC.closeFunctionBody();
    
    // declare the accessors prototypes in the .h file and define them in the .c file
    comment = "fields accessors for composite list " + compCtxt.mapCompNameL + "_list";
    compListH.addNewLine();
    compListH.addSingleLineComment(comment);
    compListC.addNewLine();
    compListC.addSingleLineComment(comment);
    
    // unsigned int <area>_[<service>_]<composite>_list_get_element_count(<area>_[<service>_]<composite>_list_t *self);
    compListH.openFunctionPrototype("unsigned int", compCtxt.mapCompNameL + "_list_get_element_count", 1);
    compListH.addFunctionParameter(mapCompListType + " *", "self", true);
    compListH.closeFunctionPrototype();
    // <area>_[<service>_]<composite>_t **<area>_[<service>_]<composite>_list_get_content(<area>_[<service>_]<composite>_list_t *self);
    compListH.openFunctionPrototype(compCtxt.mapCompNameL + "_t *" + BRACKETS, compCtxt.mapCompNameL + "_list_get_content", 1);
    compListH.addFunctionParameter(mapCompListType + " *", "self", true);
    compListH.closeFunctionPrototype();
    
    // unsigned int <area>_[<service>_]<composite>_list_get_element_count(<area>_[<service>_]<composite>_list_t *self) {
    //	return self->element_count;
    // }
    compListC.openFunction("unsigned int", compCtxt.mapCompNameL + "_list_get_element_count", 1);
    compListC.addFunctionParameter(mapCompListType + " *", "self", true);
    compListC.openFunctionBody();
    compListC.addStatement("return self->element_count;");
    compListC.closeFunctionBody();
    // <area>_[<service>_]<composite>_t **<area>_[<service>_]<composite>_list_get_content(
    //		<area>_[<service>_]<composite>_list_t *self) {
    //	return self->content;
    // }
    compListC.openFunction(compCtxt.mapCompNameL + "_t *" + BRACKETS, compCtxt.mapCompNameL + "_list_get_content", 1);
    compListC.addFunctionParameter(mapCompListType + " *", "self", true);
    compListC.openFunctionBody();
    compListC.addStatement("return self->content;");
    compListC.closeFunctionBody();

    // declare the prototypes of the encoding functions in the .h file
    // and define them in the .c file
    if (generateTransportMalbinary || generateTransportMalsplitbinary)
    {
    	addCompListEncodingFunctions(compListH, compListC, compCtxt);
    }

    // add a test function
    addCompositeListTestFunction(compListH, compListC, compCtxt);
    
    // write the closing statements in the global files
    compListH.closeC();
    compListH.closeDefine();
    
    // finalize the structure specific files
    compListH.flush();
    compListH.close();
    compListC.flush();
    compListC.close();
    
    // include the file in the main <area>.h
    // assumes that the file folder is the main folder for the area
    compCtxt.areaContext.structureIncludes.addInclude(compCtxt.mapCompNameL + "_list.h");
  }
  
  private void processCompFields(CompositeContext compCtxt) throws IOException
  {
  	// generate code in memory for some constructs of the <composite>.c file
  	// so that the algorithm is shared for all constructs

  	String comment;
  	CompositeHWriter compositeH = compCtxt.compositeH;
  	CompositeCWriter compositeC = compCtxt.compositeC;
  	
  	// generation of the structure definition
    StatementWriter compCStructDefW = new StatementWriter();
    CFileWriter compCStructDef = new CFileWriter(compCStructDefW);
    // open the structure definition
    // struct _<area>_[<service>_]<composite>_t {
    compCStructDef.openStruct("_" + compCtxt.mapCompNameL + "_t");
    
    // generation of the accessors
    StatementWriter compCStructAccessW = new StatementWriter();
    CFileWriter compCStructAccess = new CFileWriter(compCStructAccessW);
    
    // find the parent type, if not base Composite type
    TypeReference parentType = null;
    if ((null != compCtxt.composite.getExtends()) && (!StdStrings.COMPOSITE.equals(compCtxt.composite.getExtends().getType().getName())))
    {
      parentType = compCtxt.composite.getExtends().getType();
    }
    
    // build the list of the component fields, including the inherited ones
    // these functions call the createCompositeElementsDetails function which has not been adapted to the C generation
    List<CompositeField> compElements = createCompositeElementsList(null, compCtxt.composite);
    List<CompositeField> superCompElements = new LinkedList<CompositeField>();
    createCompositeSuperElementsList(null, parentType, superCompElements);
    List<List<CompositeField>> allCompElements = new ArrayList<List<CompositeField>>(2);
    allCompElements.add(superCompElements);
    allCompElements.add(compElements);

    comment = "fields accessors for composite " + compCtxt.mapCompNameL;
    compositeH.addNewLine();
    compositeH.addSingleLineComment(comment);
    
    for (List<CompositeField> lst : allCompElements)
    {
    	if (!lst.isEmpty())
    	{
    		for (CompositeField element : lst)
    		{
    			// keep the field area name for future include
    			compCtxt.areaContext.reqAreas.add(element.getTypeReference().getArea());
    			
    			// sets generation flags in a first step, filling in the CompositeFieldDetails structure
    			CompositeFieldDetails cfDetails = new CompositeFieldDetails();
    			cfDetails.fieldName = element.getFieldName().toLowerCase();
    			cfDetails.type = element.getTypeReference();
    	  	// in the code below we cannot use element.getTypeName which should be set in createCompositeElementsDetails
    	  	// according to the GeneratorLang framework

  	    	if (element.isCanBeNull())
  	    	{
  	    		compCtxt.holdsOptionalField = true;
  	    	}
  	    	
    	  	// a composite field may be of the following types: attribute, attribute list, enumeration, enumeration list, composite, composite list
    	  	// the type must be concrete except for a possible abstract attribute
    	  	
    	  	if (isAbstract(cfDetails.type))
    	  	{
    	  		// the only allowed abstract type is Attribute
    	  		if (! StdStrings.MAL.equals(cfDetails.type.getArea()) ||
    	  				! StdStrings.ATTRIBUTE.equals(cfDetails.type.getName()))
    	  		{
    	  			throw new IllegalStateException("abstract type " + cfDetails.type.getName() + " is not Attribute as a composite field");
    	  		}
    	  		cfDetails.isAbstractAttribute = true;
    	    	if (element.isCanBeNull())
    	    	{
    	    		cfDetails.isPresentField = true;
    	    	}
						cfDetails.isDestroyable = true;
    	  	}
    	  	else
    	  	{
  	  			// build the fully qualified name of the field type for the C mapping (lower case)
  	  	    // <area>_[<service>_]<field type>
    	  		// attribute type <attribute> naturally gives a qualified name mal_<attribute>
  	  	    StringBuilder buf = new StringBuilder();
  	  	    buf.append(cfDetails.type.getArea().toLowerCase());
  	  	    buf.append("_");
  	  	    if (cfDetails.type.getService() != null)
  	  	    {
  	  	    	buf.append(cfDetails.type.getService().toLowerCase());
  	  	    	buf.append("_");
  	  	    }
  	  	    buf.append(cfDetails.type.getName().toLowerCase());
  	  	    cfDetails.qfTypeNameL = buf.toString();

  	  			if (cfDetails.type.isList())
  	  			{
  	  				cfDetails.isList = true;
  						cfDetails.isDestroyable = true;
  	  				//	<qualified type>_list_t * <field>;
  	  				cfDetails.fieldType = cfDetails.qfTypeNameL + "_list_t *";
  	  			}
  	  			else if (isAttributeType(cfDetails.type))
    	  		{
  	  				cfDetails.isAttribute = true;
  	  				// fieldType is also <qfTypeNameL>_t, with an optional *
  	  				cfDetails.fieldType = getAttributeDetails(cfDetails.type).getTargetType();
	  					if (cfDetails.fieldType.endsWith("*"))
	  					{
	  						cfDetails.isDestroyable = true;
	  					}
	  					else if (element.isCanBeNull())
	  					{
  	  					// map type is not a pointer, declare the is_present field
	  						cfDetails.isPresentField = true;
  	  				}
    	  		}
  	  			else if (isEnum(cfDetails.type))
  	  			{
  	  				compCtxt.holdsEnumField = true;
  	  				cfDetails.isEnumeration = true;
  	  				//	<qualified field type>_t <field>;
  	  				cfDetails.fieldType = cfDetails.qfTypeNameL + "_t";
  	  				if (element.isCanBeNull()) {
  	  					cfDetails.isPresentField = true;
  	  				}
  	  			}
  	  			else if (isComposite(cfDetails.type))
  	  			{
  	  				cfDetails.isComposite = true;
  						cfDetails.isDestroyable = true;
  	  				//	<qualified field type>_t <field>;
  	  				cfDetails.fieldType = cfDetails.qfTypeNameL + "_t *";
  	  			}
  	  			else
  	  			{
  	  				throw new IllegalArgumentException("unexpected type " + cfDetails.type.toString() + " for composite field " + element.getFieldName());
    	  		}
    	  	}

    	  	// generate code in a second step

    	  	// generate variables definition and accessors
  	  		if (cfDetails.isPresentField)
  	  		{
  	  			// add present field definition
  					//	[bool <field>_is_present;]
  	    		compCStructDef.addStructField("bool", fieldPrefix + cfDetails.fieldName + "_is_present");
  	    		
  	    		// add present field accessors
  	    		addCompFieldPresentAccessors(compositeH, compCStructAccess, compCtxt.mapCompNameL, cfDetails.fieldName);
  	  		}
  	  		
  	  		if (cfDetails.isAbstractAttribute)
  	  		{
    	    	// add specific fields definition
    	  		//	unsigned char <field>_attribute_tag;
    	  		//	union mal_attribute_t <field>;
  	    		compCStructDef.addStructField("unsigned char", fieldPrefix + cfDetails.fieldName + "_attribute_tag");
  	    		compCStructDef.addStructField("union mal_attribute_t", fieldPrefix + cfDetails.fieldName);

  	    		// add field accessors
  	    		addAttributeFieldAccessors(compositeH, compCStructAccess, compCtxt.mapCompNameL, cfDetails.fieldName);
  	  		}
  	  		else
  	  		{
    	  		// add the field definition
  	    		compCStructDef.addStructField(cfDetails.fieldType, fieldPrefix + cfDetails.fieldName);

  	    		// add field accessors
  	    		addCompFieldAccessors(compositeH, compCStructAccess, compCtxt.mapCompNameL, cfDetails.fieldType, cfDetails.fieldName);
  	  		}

	    		// provision encoding code
  	  		if (generateTransportMalbinary || generateTransportMalsplitbinary)
  	  		{
  	  			addCompFieldMalbinaryEncoding(compCtxt, element, cfDetails);
  	  		}
  	  		
  	  		// provision destructor code
  	  		if (cfDetails.isDestroyable)
  	  		{
  	  			addCompFieldDestroy(compCtxt, element, cfDetails);
  	  		}
    		}
    	}
    }

    // close the structure definition
    // };
    compCStructDef.closeStruct();
    
    // write the structure definition in the <composite>.c file
    comment = "structure definition for composite " + compCtxt.mapCompNameL;
    compositeC.addNewLine();
    compositeC.addSingleLineComment(comment);
    compositeC.addStatements(compCStructDefW);

    // write the structure field accessors in the <composite>.c file
    comment = "fields accessors for composite " + compCtxt.mapCompNameL;
    compositeC.addNewLine();
    compositeC.addSingleLineComment(comment);
    compositeC.addStatements(compCStructAccessW);
  }

  private void addCompFieldPresentAccessors(
  		CFileWriter compositeH, CFileWriter compositeC,
  		String mapCompNameL, String fieldName) throws IOException
  {
  	//	bool <area>_[<service>_]<composite>_<field>_is_present(
  	//		<area>_[<service>_]<composite>_t *self);
  	compositeH.openFunctionPrototype("bool", mapCompNameL + "_" + fieldName + "_is_present", 1);
  	compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", true);
  	compositeH.closeFunctionPrototype();

  	//	void <area>_[<service>_]<composite>_<field>_set_present(
  	//		<area>_[<service>_]<composite>_t *self, bool is_present);
  	compositeH.openFunctionPrototype("void", mapCompNameL + "_" + fieldName + "_set_present", 2);
  	compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", false);
  	compositeH.addFunctionParameter("bool", "is_present", true);
  	compositeH.closeFunctionPrototype();
        
  	//	bool <area>_[<service>_]<composite>_<field>_is_present(
  	//		<area>_[<service>_]<composite>_t *self) {
  	//			return self-><f_><field>_is_present;
  	//	}
  	compositeC.openFunction("bool", mapCompNameL + "_" + fieldName + "_is_present", 1);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("return self->" + fieldPrefix + fieldName + "_is_present;");
  	compositeC.closeFunctionBody();

  	//	void <area>_[<service>_]<composite>_<field>_set_present(
  	//		<area>_[<service>_]<composite>_t *self, bool is_present) {
  	//			self-><f_><field>_is_present = is_present;
  	//	}
  	compositeC.openFunction("void", mapCompNameL + "_" + fieldName + "_set_present", 2);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", false);
  	compositeC.addFunctionParameter("bool", "is_present", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("self->" + fieldPrefix + fieldName + "_is_present = is_present;");
  	compositeC.closeFunctionBody();
  }
  
  private void addAttributeFieldAccessors(CFileWriter compositeH, CFileWriter compositeC, String mapCompNameL, String fieldName) throws IOException
  {
		// unsigned char <area>_[<service>_]<composite>_<field>_get_attribute_tag(<area>_[<service>_]<composite>_t * self);
		compositeH.openFunctionPrototype("unsigned char", mapCompNameL + "_" + fieldName + "_get_attribute_tag", 1);
		compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", true);
		compositeH.closeFunctionPrototype();
		// void <area>_[<service>_]<composite>_<field>_set_attribute_tag(<area>_[<service>_]<composite>_t * self, unsigned char attribute_tag);
		compositeH.openFunctionPrototype("void", mapCompNameL + "_" + fieldName + "_set_attribute_tag", 2);
		compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", false);
		compositeH.addFunctionParameter("unsigned char", "attribute_tag", true);
		compositeH.closeFunctionPrototype();
		
		// unsigned char <area>_[<service>_]<composite>_<field>_get_attribute_tag(
		//	<area>_[<service>_]<composite>_t *self) {
		//		return self-><f_><field>_attribute_tag;
		//	}
  	compositeC.openFunction("unsigned char", mapCompNameL + "_" + fieldName + "_get_attribute_tag", 1);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("return self->" + fieldPrefix + fieldName + "_attribute_tag;");
  	compositeC.closeFunctionBody();

		// void <area>_[<service>_]<composite>_<field>_set_attribute_tag(
		//	<area>_[<service>_]<composite>_t *self, 
		//	unsigned char attribute_tag) {
		//		self-><f_><field>_attribute_tag = attribute_tag;
		//	}
  	compositeC.openFunction("void", mapCompNameL + "_" + fieldName + "_set_attribute_tag", 2);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", false);
  	compositeC.addFunctionParameter("unsigned char", "attribute_tag", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("self->" + fieldPrefix + fieldName + "_attribute_tag = attribute_tag;");
  	compositeC.closeFunctionBody();
  }
  
  private void addCompFieldAccessors(CFileWriter compositeH, CFileWriter compositeC, String mapCompNameL, String fieldType, String fieldName) throws IOException
  {
  	//	<field type> <area>_[<service>_]<composite>_get_<field>(
  	//		<area>_[<service>_]<composite>_t *self);
  	compositeH.openFunctionPrototype(fieldType, mapCompNameL + "_get_" + fieldName, 1);
  	compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", true);
  	compositeH.closeFunctionPrototype();

  	//	void <area>_[<service>_]<composite>_set_<field>(
  	//		<area>_[<service>_]<composite>_t *self, <field type> <f_><field>);
  	compositeH.openFunctionPrototype("void", mapCompNameL + "_set_" + fieldName, 2);
  	compositeH.addFunctionParameter(mapCompNameL + "_t *", "self", false);
  	compositeH.addFunctionParameter(fieldType, fieldPrefix + fieldName, true);
  	compositeH.closeFunctionPrototype();
  		
  	//	<field type> <area>_[<service>_]<composite>_get_<field>(
  	//		<area>_[<service>_]<composite>_t *self) {
  	//		return self-><f_><field>;
  	//	}
  	compositeC.openFunction(fieldType, mapCompNameL + "_get_" + fieldName, 1);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("return self->" + fieldPrefix + fieldName + ";");
  	compositeC.closeFunctionBody();

  	//	void <area>_[<service>_]<composite>_set_<field>(
  	//		<area>_[<service>_]<composite>_t *self, <field type> <f_><field>) {
  	//		self-><f_><field> = <f_><field>;
  	//	}
  	compositeC.openFunction("void", mapCompNameL + "_set_" + fieldName, 2);
  	compositeC.addFunctionParameter(mapCompNameL + "_t *", "self", false);
  	compositeC.addFunctionParameter(fieldType, fieldPrefix + fieldName, true);
  	compositeC.openFunctionBody();
  	compositeC.addStatement("self->" + fieldPrefix + fieldName + " = " + fieldPrefix + fieldName + ";");
  	compositeC.closeFunctionBody();
  }

  private void addCompositeEncodingFunctions(CompositeContext compCtxt) throws IOException
  {
    if (generateTransportMalbinary || generateTransportMalsplitbinary)
    {
    	addCompositeMalbinaryEncodingFunctions(compCtxt);
    }
  }
  
  private void addCompositeMalbinaryEncodingFunctions(CompositeContext compCtxt) throws IOException
  {
  	CFileWriter compositeH = compCtxt.compositeH;
  	CFileWriter compositeC = compCtxt.compositeC;
  	
  	String comment = "encoding functions related to transport " + transportMalbinary;
    compositeH.addNewLine();
    compositeH.addSingleLineComment(comment);
    compositeC.addNewLine();
    compositeC.addSingleLineComment(comment);
    
    String selfType = compCtxt.mapCompNameL + "_t *";
    String funcName = compCtxt.mapCompNameL + "_add_encoding_length_" + transportMalbinary;
  	// int <area>_[<service>_]<composite>_add_encoding_length_<format>(
  	//	<area>_[<service>_]<composite>_t * self,
  	//	mal_encoder_t * encoder,
  	//	void *cursor);
    compositeH.openFunctionPrototype("int", funcName, 3);
    compositeH.addFunctionParameter(selfType, "self", false);
    compositeH.addFunctionParameter("mal_encoder_t *", "encoder", false);
    compositeH.addFunctionParameter("void *", "cursor", true);
    compositeH.closeFunctionPrototype();
    // int <area>_[<service>_]<composite>_add_encoding_length_malbinary(
    //	<area>_[<service>_]<composite>_t *self,
    //	mal_encoder_t *encoder,
    //	void *cursor) {
    //		int rc = 0;
    //	handle all fields
    //		return rc;
    // }
    compositeC.openFunction("int", funcName, 3);
    compositeC.addFunctionParameter(selfType, "self", false);
    compositeC.addFunctionParameter("mal_encoder_t *", "encoder", false);
    compositeC.addFunctionParameter("void *", "cursor", true);
    compositeC.openFunctionBody();
    compositeC.addStatement("int rc = 0;");
    compositeC.addStatements(compCtxt.encodingCode.lengthW);
    compositeC.addStatement("return rc;");
    compositeC.closeFunctionBody();

    funcName = compCtxt.mapCompNameL + "_encode_" + transportMalbinary;
  	// int <area>_[<service>_]<composite>_encode_<format>(
  	//	<area>_[<service>_]<composite>_t * self,
  	//	mal_encoder_t * encoder,
  	//	void * cursor);
    compositeH.openFunctionPrototype("int", funcName, 3);
    compositeH.addFunctionParameter(selfType, "self", false);
    compositeH.addFunctionParameter("mal_encoder_t *", "encoder", false);
    compositeH.addFunctionParameter("void *", "cursor", true);
    compositeH.closeFunctionPrototype();
    // int <area>_[<service>_]<composite>_encode_malbinary(
    //	<area>_[<service>_]<composite>_t *self,
    //	mal_encoder_t *encoder,
    //	void * cursor) {
    //		int rc = 0;
    //		[bool presence_flag;]
    //	handle all fields
    //		return rc;
    // }
    compositeC.openFunction("int", funcName, 3);
    compositeC.addFunctionParameter(selfType, "self", false);
    compositeC.addFunctionParameter("mal_encoder_t *", "encoder", false);
    compositeC.addFunctionParameter("void *", "cursor", true);
    compositeC.openFunctionBody();
    compositeC.addStatement("int rc = 0;");
    if (compCtxt.holdsOptionalField)
    {
    	compositeC.addVariableDeclare("bool", "presence_flag", null);
    }
    compositeC.addStatements(compCtxt.encodingCode.encodeW);
    compositeC.addStatement("return rc;");
    compositeC.closeFunctionBody();

    funcName = compCtxt.mapCompNameL + "_decode_" + transportMalbinary;
  	// int <area>_[<service>_]<composite>_decode_<format>(
  	//	<area>_[<service>_]<composite>_t * self,
  	//	mal_decoder_t * decoder,
  	//	void * cursor);
    compositeH.openFunctionPrototype("int", funcName, 3);
    compositeH.addFunctionParameter(selfType, "self", false);
    compositeH.addFunctionParameter("mal_decoder_t *", "decoder", false);
    compositeH.addFunctionParameter("void *", "cursor", true);
    compositeH.closeFunctionPrototype();
    // int <area>_[<service>_]<composite>_decode_malbinary(
    //	<area>_[<service>_]<composite>_t *self,
    //	mal_decoder_t *decoder,
    //	void * cursor) {
    //		int rc = 0;
    //		[bool presence_flag;]
    //		[int enumerated_value;]
    //	handle all fields
    //		return rc;
    // }
    compositeC.openFunction("int", funcName, 3);
    compositeC.addFunctionParameter(selfType, "self", false);
    compositeC.addFunctionParameter("mal_decoder_t *", "decoder", false);
    compositeC.addFunctionParameter("void *", "cursor", true);
    compositeC.openFunctionBody();
    compositeC.addStatement("int rc = 0;");
    if (compCtxt.holdsOptionalField)
    {
    	compositeC.addVariableDeclare("bool", "presence_flag", null);
    }
    if (compCtxt.holdsEnumField)
    {
    	compositeC.addVariableDeclare("int", "enumerated_value", null);
    }
    compositeC.addStatements(compCtxt.encodingCode.decodeW);
    compositeC.addStatement("return rc;");
    compositeC.closeFunctionBody();
  }

  private MalbinaryEnumSize getEnumTypeMBSize(TypeReference type, EnumerationType enumType) throws IOException
  {
  	GeneratorBase.TypeKey enumKey = new GeneratorBase.TypeKey(type);
    MalbinaryEnumSize enumMBSize = enumTypesMBSize.get(enumKey);
    if (enumMBSize != null)
    {
    	return enumMBSize;
    }
    // find the size and keep it in the table
    if (enumType == null)
    {
    	enumType = getEnum(type);
    	if (enumType == null)
    	{
    		throw new IllegalArgumentException("unknown enumeration type " + type.toString());
    	}
    }
		int enumSize = enumType.getItem().size();
		enumMBSize = MalbinaryEnumSize.MB_LARGE;
  	if (enumSize <= 256)
  		enumMBSize = MalbinaryEnumSize.MB_SMALL;
  	else if (enumSize <= 65536)
  		enumMBSize = MalbinaryEnumSize.MB_MEDIUM;
  	enumTypesMBSize.put(enumKey, enumMBSize);
		return enumMBSize;
  }

  private MalbinaryEnumSize getEnumTypeMBSize(TypeReference type) throws IOException
  {
		return getEnumTypeMBSize(type, null);
  }

  private void addCompFieldMalbinaryEncoding(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
  {
  	addCompFieldMalbinaryEncodingLength(compCtxt, element, cfDetails);
  	addCompFieldMalbinaryEncodingEncode(compCtxt, element, cfDetails);
  	addCompFieldMalbinaryEncodingDecode(compCtxt, element, cfDetails);
  }

  private void addCompFieldMalbinaryEncodingLength(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
  {
		CFileWriter codeLength = compCtxt.encodingCode.codeLength;
		
  	if (element.isCanBeNull())
  	{
  		String isPresent;
			if (cfDetails.isPresentField)
			{
    		// 	<f_><field>_is_present
				isPresent = "self->" + fieldPrefix + cfDetails.fieldName + "_is_present";
			}
			else
			{
				// element is a pointer
				//	(<f_><field> != NULL)
				isPresent = "(self->" + fieldPrefix + cfDetails.fieldName + " != NULL)";
			}
  		addMalbinaryEncodingLengthPresenceFlag(codeLength, isPresent);
  		// 	if (<field_is_present>) {
  		codeLength.addStatement("if (" + isPresent + ")");
			codeLength.openBlock();
  	}
  	
  	if (cfDetails.isAbstractAttribute)
  	{
  		String varName = "self->" + fieldPrefix + cfDetails.fieldName;
  		addMalbinaryEncodingLengthAbstractAttribute(codeLength, varName + "_attribute_tag", varName);
  	}
  	else if (cfDetails.isAttribute)
  	{
  		addMalbinaryEncodingLengthAttribute(codeLength, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.type.getName().toLowerCase());
  	}
  	else if (cfDetails.isComposite)
  	{
  		addMalbinaryEncodingLengthComposite(codeLength, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL);
  	}
  	else if (cfDetails.isList)
  	{
  		addMalbinaryEncodingLengthList(codeLength, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL);
  	}
  	else if (cfDetails.isEnumeration)
  	{
  		MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(cfDetails.type);
  		addMalbinaryEncodingLengthEnumeration(codeLength, "self->" + fieldPrefix + cfDetails.fieldName, enumMBSize);
  	}
  	else
  	{
  		throw new IllegalStateException("unexpected case generating encoding functions for composite field " + element.getTypeReference().toString() + ":" + cfDetails.fieldName);
  	}

  	if (element.isCanBeNull())
  	{
			//	}
  		codeLength.closeBlock();
  	}
  	
  }

  private void addCompFieldMalbinaryEncodingEncode(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
  {
		CFileWriter codeEncode = compCtxt.encodingCode.codeEncode;
		
  	if (element.isCanBeNull())
  	{
			if (cfDetails.isPresentField)
			{
				//	presence_flag = <f_><field>_is_present
				codeEncode.addStatement("presence_flag = self->" + fieldPrefix + cfDetails.fieldName + "_is_present;");
			}
			else
			{
				//	presence_flag = (<f_><field> != NULL);
				codeEncode.addStatement("presence_flag = (self->" + fieldPrefix + cfDetails.fieldName + " != NULL);");
			}
  		addMalbinaryEncodingEncodePresenceFlag(codeEncode, "presence_flag");
			
			//	if (presence_flag) {
			codeEncode.addStatement("if (presence_flag)");
			codeEncode.openBlock();
  	}
  	
  	if (cfDetails.isAbstractAttribute)
  	{
  		String varName = "self->" + fieldPrefix + cfDetails.fieldName;
  		addMalbinaryEncodingEncodeAbstractAttribute(codeEncode, varName + "_attribute_tag", varName);
  	}
  	else if (cfDetails.isAttribute)
  	{
  		addMalbinaryEncodingEncodeAttribute(codeEncode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.type.getName().toLowerCase());
  	}
  	else if (cfDetails.isComposite)
  	{
  		addMalbinaryEncodingEncodeComposite(codeEncode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL);
  	}
  	else if (cfDetails.isList)
  	{
  		addMalbinaryEncodingEncodeList(codeEncode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL);
  	}
  	else if (cfDetails.isEnumeration)
  	{
  		MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(cfDetails.type);
  		addMalbinaryEncodingEncodeEnumeration(codeEncode, "self->" + fieldPrefix + cfDetails.fieldName, enumMBSize);
  	}
  	else
  	{
  		throw new IllegalStateException("unexpected case generating encoding functions for composite field " + element.getTypeReference().toString() + ":" + cfDetails.fieldName);
  	}

  	if (element.isCanBeNull())
  	{
			//	}
  		codeEncode.closeBlock();
  	}
  	
  }

  private void addCompFieldMalbinaryEncodingDecode(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
  {
		CFileWriter codeDecode = compCtxt.encodingCode.codeDecode;
		
  	if (element.isCanBeNull())
  	{
  		addMalbinaryEncodingDecodePresenceFlag(codeDecode, "presence_flag");
  		//	if (presence_flag) {
  		codeDecode.addStatement("if (presence_flag)");
			codeDecode.openBlock();
  	}
  	
  	if (cfDetails.isAbstractAttribute)
  	{
  		String varName = "self->" + fieldPrefix + cfDetails.fieldName;
  		addMalbinaryEncodingDecodeAbstractAttribute(codeDecode, varName + "_attribute_tag", varName);
  	}
  	else if (cfDetails.isAttribute)
  	{
  		addMalbinaryEncodingDecodeAttribute(codeDecode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.type.getName().toLowerCase());
  	}
  	else if (cfDetails.isComposite)
  	{
  		addMalbinaryEncodingDecodeComposite(codeDecode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL, false);
  	}
  	else if (cfDetails.isList)
  	{
  		addMalbinaryEncodingDecodeList(codeDecode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL, false);
  	}
  	else if (cfDetails.isEnumeration)
  	{
  		MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(cfDetails.type);
  		addMalbinaryEncodingDecodeEnumeration(codeDecode, "self->" + fieldPrefix + cfDetails.fieldName, cfDetails.qfTypeNameL, enumMBSize);
  	}
  	else
  	{
  		throw new IllegalStateException("unexpected case generating encoding functions for composite field " + element.getTypeReference().toString() + ":" + cfDetails.fieldName);
  	}

  	if (element.isCanBeNull())
  	{
			//	}
  		codeDecode.closeBlock();

			if (cfDetails.isPresentField)
			{
				//	self-><f_><field>_is_present = presence_flag;
				codeDecode.addStatement("self->" + fieldPrefix + cfDetails.fieldName + "_is_present = presence_flag;");
			}
			else
			{
				//	else {
			  //		<element> = NULL;
				//	}
				codeDecode.addStatement("else");
				codeDecode.openBlock();
				codeDecode.addStatement("self->" + fieldPrefix + cfDetails.fieldName + " = NULL;");
	  		codeDecode.closeBlock();
			}
  	}
  }

	private void addEnumListEncodingFunctions(CFileWriter enumListH, CFileWriter enumListC, String mapEnumNameL, MalbinaryEnumSize mbSize) throws IOException
	{
		String comment = "encoding functions related to transport " + transportMalbinary;
		enumListH.addNewLine();
		enumListH.addSingleLineComment(comment);
		enumListC.addNewLine();
		enumListC.addSingleLineComment(comment);

		// int <area>_[<service>_]<enumeration>_list_add_encoding_length_<format>(
	  //	<area>_[<service>_]<enumeration>_list_t *self,
	  //	mal_encoder_t *encoder, void *cursor);
		enumListH.openFunctionPrototype("int", mapEnumNameL + "_list_add_encoding_length_" + transportMalbinary, 3);
		enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		enumListH.addFunctionParameter("void *", "cursor", true);
		enumListH.closeFunctionPrototype();
		// int <area>_[<service>_]<enumeration>_list_add_encoding_length_malbinary(
	  //	<area>_[<service>_]<enumeration>_list_t *self,
	  //	mal_encoder_t *encoder, void *cursor) {
	  //		int rc = 0;
	  //		unsigned int list_size = self->element_count;
		//		rc = mal_encoder_add_list_size_encoding_length(encoder, list_size, cursor);
		//		if (rc < 0) return rc;
	  //		for (int i = 0; i < list_size; i++) {
		//			bool presence_flag = self->presence_flags[i];
		//			rc = mal_encoder_add_presence_flag_encoding_length(encoder, presence_flag, cursor);
		//			if (rc < 0) return rc;
		//			<area>_[<service>_]<enumeration>_t element = self->content[i];
		//			if (presence_flag) {
		// add the enumerated value size
		//			}
		//		}
	  //		return rc;
		//	}
		enumListC.openFunction("int", mapEnumNameL + "_list_add_encoding_length_" + transportMalbinary, 3);
		enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		enumListC.addFunctionParameter("void *", "cursor", true);
		enumListC.openFunctionBody();
		enumListC.addStatement("int rc = 0;");
		enumListC.addStatement("unsigned int list_size = self->element_count;");
		enumListC.addStatement("rc = mal_encoder_add_list_size_encoding_length(encoder, list_size, cursor);");
		enumListC.addStatement("if (rc < 0)", 1);
		enumListC.addStatement("return rc;", -1);
		enumListC.addStatement("for (int i = 0; i < list_size; i++)");
		enumListC.openBlock();
		enumListC.addStatement("bool presence_flag = self->presence_flags[i];");
		enumListC.addStatement("rc = mal_encoder_add_presence_flag_encoding_length(encoder, presence_flag, cursor);");
		enumListC.addStatement("if (rc < 0)", 1);
		enumListC.addStatement("return rc;", -1);
		enumListC.addStatement("if (presence_flag)");
		enumListC.openBlock();
		addMalbinaryEncodingLengthEnumeration(enumListC, "self->content[i]", mbSize);
		enumListC.closeBlock();
		enumListC.closeBlock();
		enumListC.addStatement("return rc;");
		enumListC.closeFunctionBody();

		// int <area>_[<service>_]<enumeration>_list_encode_<format>(
	  //	<area>_[<service>_]<enumeration>_list_t *self, 
	  //	mal_encoder_t *encoder, void * cursor);
		enumListH.openFunctionPrototype("int", mapEnumNameL + "_list_encode_" + transportMalbinary, 3);
		enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		enumListH.addFunctionParameter("void *", "cursor", true);
		enumListH.closeFunctionPrototype();
		// int <area>_[<service>_]<enumeration>_list_encode_malbinary(
		//	<area>_[<service>_]<enumeration>_list_t *self,
	  //	mal_encoder_t *encoder, void * cursor) {
	  //		int rc = 0;
	  //		unsigned int list_size = self->element_count;
	  //		rc = mal_encoder_encode_list_size(encoder, cursor, list_size);
		//		if (rc < 0) return rc;
	  //		for (int i = 0; i < list_size; i++) {
		//			bool presence_flag = self->presence_flags[i];
		//			mal_encoder_encode_presence_flag(encoder, presence_flag, cursor);
		// encode the presence flag
		//			if (presence_flag) {
		// encode the enumerated value
		//			}
		//		}
	  //		return rc;
		//	}
		enumListC.openFunction("int", mapEnumNameL + "_list_encode_" + transportMalbinary, 3);
		enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		enumListC.addFunctionParameter("void *", "cursor", true);
		enumListC.openFunctionBody();
		enumListC.addStatement("int rc = 0;");
		enumListC.addStatement("unsigned int list_size = self->element_count;");
		enumListC.addStatement("rc = mal_encoder_encode_list_size(encoder, cursor, list_size);");
		enumListC.addStatement("if (rc < 0)", 1);
		enumListC.addStatement("return rc;", -1);
		enumListC.addStatement("for (int i = 0; i < list_size; i++)");
		enumListC.openBlock();
		enumListC.addStatement("bool presence_flag = self->presence_flags[i];");
		addMalbinaryEncodingEncodePresenceFlag(enumListC, "presence_flag");
		enumListC.addStatement("if (presence_flag)");
		enumListC.openBlock();
		addMalbinaryEncodingEncodeEnumeration(enumListC, "self->content[i]", mbSize);
		enumListC.closeBlock();
		enumListC.closeBlock();
		enumListC.addStatement("return rc;");
		enumListC.closeFunctionBody();

		// int <area>_[<service>_]<enumeration>_list_decode_<format>(
	  //	<area>_[<service>_]<enumeration>_t *self, 
	  //	mal_decoder_t *decoder, void * cursor);
		enumListH.openFunctionPrototype("int", mapEnumNameL + "_list_decode_" + transportMalbinary, 3);
		enumListH.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListH.addFunctionParameter("mal_decoder_t *", "decoder", false);
		enumListH.addFunctionParameter("void *", "cursor", true);
		enumListH.closeFunctionPrototype();
		// int <area>_[<service>_]<enumeration>_list_decode_malbinary(
	  //	<area>_[<service>_]<enumeration>_list_t *self,
	  //	mal_decoder_t *decoder, void * cursor) {
	  //		int rc = 0;
	  //		unsigned int list_size;
	  //		rc = mal_decoder_decode_list_size(decoder, cursor, &list_size);
		//		if (rc < 0) return rc;
		//		if (list_size == 0) {
		//				self->element_count = 0;
		//				self->presence_flags = NULL;
		//				self->content = NULL;
		//				return 0;
		//		}
		//		self->presence_flags = (bool *) calloc(self->element_count, sizeof(bool));
		//		if (self->presence_flags == NULL) return -1;
		//		self->content = (<area>_[<service>_]<enumeration_t *) calloc(self->element_count, sizeof(<area>_[<service>_]<enumeration_t));
		//		if (self->content == NULL) {
		//			free(self->presence_flags);
		//			self->presence_flags = NULL;
		//			return -1;
		//		}
		//		self->element_count = list_size;
	  //		for (int i = 0; i < list_size; i++) {
		//			bool presence_flag;
		//			int enumerated_value;
		// decode the presence flag
		//			self->presence_flags[i] = presence_flag;
		//			if (presence_flag) {
		// decode the enumerated value
		//			}
    //			self->content[i] = (<area>_[<service>_]<enumeration>_t) element;
		//		}
		//		return rc;
		//	}
		// NOTE: we could probably get rid of some intermediate local variables
		enumListC.openFunction("int", mapEnumNameL + "_list_decode_" + transportMalbinary, 3);
		enumListC.addFunctionParameter(mapEnumNameL + "_list_t *", "self", false);
		enumListC.addFunctionParameter("mal_decoder_t *", "decoder", false);
		enumListC.addFunctionParameter("void *", "cursor", true);
		enumListC.openFunctionBody();
		enumListC.addStatement("int rc = 0;");
		enumListC.addStatement("unsigned int list_size;");
		enumListC.addStatement("rc = mal_decoder_decode_list_size(decoder, cursor, &list_size);");
		enumListC.addStatement("if (rc < 0)", 1);
		enumListC.addStatement("return rc;", -1);
		enumListC.addStatement("if (list_size == 0)");
		enumListC.openBlock();
		enumListC.addStatement("self->element_count = 0;");
		enumListC.addStatement("self->presence_flags = NULL;");
		enumListC.addStatement("self->content = NULL;");
		enumListC.addStatement("return 0;");
		enumListC.closeBlock();
		enumListC.addStatement("self->presence_flags = (bool *) calloc(list_size, sizeof(bool));");
		enumListC.addStatement("if (self->presence_flags == NULL)", 1);
		enumListC.addStatement("return -1;", -1);
		enumListC.addStatement("self->content = (" + mapEnumNameL + "_t " + BRACKETS + ") calloc(list_size, sizeof(" + mapEnumNameL + "_t));");
		enumListC.addStatement("if (self->content == NULL)");
		enumListC.openBlock();
		enumListC.addStatement("free(self->presence_flags);");
		enumListC.addStatement("self->presence_flags = NULL;");
		enumListC.addStatement("return -1;");
		enumListC.closeBlock();
		enumListC.addStatement("self->element_count = list_size;");
		enumListC.addStatement("for (int i = 0; i < list_size; i++)");
		enumListC.openBlock();
		enumListC.addStatement("bool presence_flag;");
		enumListC.addStatement("int enumerated_value;");
		addMalbinaryEncodingDecodePresenceFlag(enumListC, "presence_flag");
		enumListC.addStatement("self->presence_flags[i] = presence_flag;");
		enumListC.addStatement("if (presence_flag)");
		enumListC.openBlock();
		addMalbinaryEncodingDecodeEnumeration(enumListC, "self->content[i]", mapEnumNameL, mbSize);
		enumListC.closeBlock();
		enumListC.closeBlock();
		enumListC.addStatement("return rc;");
		enumListC.closeFunctionBody();
	}

	private void addCompListEncodingFunctions(CFileWriter compListH, CFileWriter compListC, CompositeContext compCtxt) throws IOException
	{
		String comment = "encoding functions related to transport " + transportMalbinary;
		compListH.addNewLine();
		compListH.addSingleLineComment(comment);
		compListC.addNewLine();
		compListC.addSingleLineComment(comment);
    
		//	int <area>_[<service>_]<composite>_list_add_encoding_length_<format>(
		//		<area>_[<service>_]<composite>_list_t *self,
		//		mal_encoder_t *encoder, void *cursor);
		compListH.openFunctionPrototype("int", compCtxt.mapCompNameL + "_list_add_encoding_length_" + transportMalbinary, 3);
		compListH.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
		compListH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		compListH.addFunctionParameter("void *", "cursor", true);
		compListH.closeFunctionPrototype();
			
		//	int <area>_[<service>_]<composite>_list_add_encoding_length_malbinary(
		//		<area>_[<service>_]<composite>_list_t *self,
		//		mal_encoder_t *encoder, void *cursor) {
		//		int rc = 0;
		//		unsigned int list_size = self->element_count;
		// Encodage de la taille de la liste :
		//		rc = mal_encoder_add_list_size_encoding_length(encoder, list_size, cursor);
		//		if (rc < 0) return rc;
		// Encodage des éléments de la liste :
		//		for (int i = 0; i < list_size; i++) {
		//			<area>_[<service>_]<composite>_t *list_element = self->content[i];
		//			bool presence_flag = (list_element != NULL);
		// Tous les éléments de la liste peuvent être nuls. Un champ de présence doit donc être ajouté pour chacun d'eux
		//			rc = mal_encoder_add_presence_flag_encoding_length(encoder, presence_flag, cursor);
		//			if (rc < 0) return rc;
		//			if (presence_flag) {
		// Calcul de la taille d'encodage d'un champ Composite optionnel : voir section 11.1.1.
		//			}
		//		}
		//		return rc;
		//	}
		compListC.openFunction("int", compCtxt.mapCompNameL + "_list_add_encoding_length_" + transportMalbinary, 3);
		compListC.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
		compListC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		compListC.addFunctionParameter("void *", "cursor", true);
		compListC.openFunctionBody();
		compListC.addStatement("int rc = 0;");
		compListC.addStatement("unsigned int list_size = self->element_count;");
		compListC.addStatement("rc = mal_encoder_add_list_size_encoding_length(encoder, list_size, cursor);");
		compListC.addStatement("if (rc < 0)", 1);
		compListC.addStatement("return rc;", -1);
		compListC.addStatement("for (int i = 0; i < list_size; i++)");
		compListC.openBlock();
		compListC.addStatement(compCtxt.mapCompNameL + "_t * list_element = self->content[i];");
		compListC.addStatement("bool presence_flag = (list_element != NULL);");
		compListC.addStatement("rc = mal_encoder_add_presence_flag_encoding_length(encoder, presence_flag, cursor);");
		compListC.addStatement("if (rc < 0)", 1);
		compListC.addStatement("return rc;", -1);
		compListC.addStatement("if (presence_flag)");
		compListC.openBlock();
		addMalbinaryEncodingLengthComposite(compListC, "list_element", compCtxt.mapCompNameL);	
		compListC.closeBlock();
		compListC.closeBlock();
		compListC.addStatement("return rc;");
		compListC.closeFunctionBody();
			
		//	int <area>_[<service>_]<composite>_list_encode_<format>(
		//		<area>_[<service>_]<composite>_list_t *self, 
		//		mal_encoder_t *encoder, void * cursor);
		compListH.openFunctionPrototype("int", compCtxt.mapCompNameL + "_list_encode_" + transportMalbinary, 3);
		compListH.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
		compListH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		compListH.addFunctionParameter("void *", "cursor", true);
		compListH.closeFunctionPrototype();
			
		//	int <area>_[<service>_]<composite>_list_encode_malbinary(
		//		<area>_[<service>_]<composite>_list_t *self,
		//		mal_encoder_t *encoder, void * cursor) {
		//		int rc = 0;
		//		unsigned int list_size = self->element_count;
		//		rc = mal_encoder_encode_list_size(encoder, cursor, list_size);
		//		if (rc < 0) return rc;
		//		<area>_[<service>_]<composite>_t **content = self->content;
		//		for (int i = 0; i < list_size; i++) {
		//			<area>_[<service>_]<composite>_t *element = content[i];
		// Encodage d'un Composite optionnel : voir section 11.2.1.
		//		}
		//		return rc;
		//	}
		compListC.openFunction("int", compCtxt.mapCompNameL + "_list_encode_" + transportMalbinary, 3);
		compListC.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
		compListC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		compListC.addFunctionParameter("void *", "cursor", true);
		compListC.openFunctionBody();
		compListC.addStatement("int rc = 0;");
		compListC.addStatement("unsigned int list_size = self->element_count;");
		compListC.addStatement("rc = mal_encoder_encode_list_size(encoder, cursor, list_size);");
		compListC.addStatement("if (rc < 0)", 1);
		compListC.addStatement("return rc;", -1);
		compListC.addStatement(compCtxt.mapCompNameL + "_t *" + BRACKETS + " content = self->content;");
		compListC.addStatement("for (int i = 0; i < list_size; i++)");
		compListC.openBlock();
		compListC.addStatement(compCtxt.mapCompNameL + "_t *list_element = content[i];");
		compListC.addStatement("bool presence_flag = (list_element != NULL);");
		addMalbinaryEncodingEncodePresenceFlag(compListC, "presence_flag");
		compListC.addStatement("if (presence_flag)");
		compListC.openBlock();
		addMalbinaryEncodingEncodeComposite(compListC, "list_element", compCtxt.mapCompNameL);
		compListC.closeBlock();
		compListC.closeBlock();
		compListC.addStatement("return rc;");
		compListC.closeFunctionBody();

		//	int <area>_[<service>_]<composite>_list_decode_<format>(
		//		<area>_[<service>_]<composite>_t *self, 
		//		mal_decoder_t *decoder, void * cursor);
		compListH.openFunctionPrototype("int", compCtxt.mapCompNameL + "_list_decode_" + transportMalbinary, 3);
		compListH.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
		compListH.addFunctionParameter("mal_decoder_t *", "decoder", false);
		compListH.addFunctionParameter("void *", "cursor", true);
		compListH.closeFunctionPrototype();
			
		//	int <area>_[<service>_]<composite>_list_decode_malbinary(
		//		<area>_[<service>_]<composite>_list_t *self,
		//		mal_decoder_t *decoder, void * cursor) {
		//		int rc = 0;
		//		unsigned int list_size;
		//		rc = mal_decoder_decode_list_size(decoder, cursor, &list_size);
		//		if (rc < 0) return rc;
		//		if (list_size == 0) {
		//			self->element_count = 0;
		//			self->content = NULL;
		//			return 0;
		//		}
		//		self->content = (<area>_[<service>_]<composite>_t **) calloc(
		//			list_size, sizeof(<area>_[<service>_]<composite>_t *));
		//		if (self->content == NULL) return -1;
		//		self->element_count = list_size;
		//		for (int i = 0; i < list_size; i++) {
		// Decodage d'un Composite optionnel : voir section 11.3.1.
		// Decodage dans la structure: self->content[i]
		//		}
		//		return rc;
		//	}
			compListC.openFunction("int", compCtxt.mapCompNameL + "_list_decode_" + transportMalbinary, 3);
			compListC.addFunctionParameter(compCtxt.mapCompNameL + "_list_t *", "self", false);
			compListC.addFunctionParameter("mal_decoder_t *", "decoder", false);
			compListC.addFunctionParameter("void *", "cursor", true);
			compListC.openFunctionBody();
			compListC.addStatement("int rc = 0;");
			compListC.addStatement("unsigned int list_size;");
			compListC.addStatement("rc = mal_decoder_decode_list_size(decoder, cursor, &list_size);");
			compListC.addStatement("if (rc < 0)", 1);
			compListC.addStatement("return rc;", -1);
			compListC.addStatement("if (list_size == 0)");
			compListC.openBlock();
			compListC.addStatement("self->element_count = 0;");
			compListC.addStatement("self->content = NULL;");
			compListC.addStatement("return 0;");
			compListC.closeBlock();
			compListC.addStatement("self->content = (" + compCtxt.mapCompNameL + "_t *" + BRACKETS + ") calloc(list_size, sizeof(" + compCtxt.mapCompNameL + "_t *));");
			compListC.addStatement("if (self->content == NULL)", 1);
			compListC.addStatement("return -1;", -1);
			compListC.addStatement("self->element_count = list_size;");
			compListC.addStatement("for (int i = 0; i < list_size; i++)");
			compListC.openBlock();
			compListC.addStatement("bool presence_flag;");
			addMalbinaryEncodingDecodePresenceFlag(compListC, "presence_flag");
			compListC.addStatement("if (presence_flag)");
			compListC.openBlock();
			addMalbinaryEncodingDecodeComposite(compListC, "self->content[i]", compCtxt.mapCompNameL, false);
			compListC.closeBlock();
			compListC.closeBlock();
			compListC.addStatement("return rc;");
			compListC.closeFunctionBody();
	}
	
  private void addMalbinaryEncodingLengthPresenceFlag(CFileWriter codeLength, String varName) throws IOException
  {
  	// use the generic function
  	// rc = mal_encoder_add_presence_flag_encoding_length(encoder, <value>, cursor);
	  //	if (rc < 0) return rc;
		codeLength.addStatement("rc = mal_encoder_add_presence_flag_encoding_length(encoder, " + varName + ", cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodePresenceFlag(CFileWriter codeEncode, String varName) throws IOException
  {
  	// use the generic function
		//	rc = mal_encoder_encode_presence_flag(encoder, cursor, <presence_flag>);
	  //	if (rc < 0) return rc;
		codeEncode.addStatement("rc = mal_encoder_encode_presence_flag(encoder, cursor, " + varName + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodePresenceFlag(CFileWriter codeDecode, String varName) throws IOException
  {
  	// use the generic function
		//	rc = mal_decoder_decode_presence_flag(decoder, cursor, &<presence_flag>);
	  //	if (rc < 0) return rc;
  	StringBuilder buf = new StringBuilder();
  	buf.append("rc = mal_decoder_decode_presence_flag(decoder, cursor, ");
		if (varName.charAt(0) == '*')
		{
			buf.append(varName.substring(1));
		}
		else
		{
			buf.append("&").append(varName);
		}
		buf.append(");");
		codeDecode.addStatement(buf.toString());
		codeDecode.addStatement("if (rc < 0)", 1);
		codeDecode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthShortForm(CFileWriter codeLength, String shortForm) throws IOException
  {
  	// use the generic function
  	// rc = mal_encoder_add_short_form_encoding_length(encoder, <value>, cursor);
	  //	if (rc < 0) return rc;
		codeLength.addStatement("rc = mal_encoder_add_short_form_encoding_length(encoder, " + shortForm + ", cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeShortForm(CFileWriter codeEncode, String shortForm) throws IOException
  {
		//	rc = mal_encoder_encode_short_form(encoder, cursor, <AREA>_[<SERVICE>_]<TYPE>_SHORT_FORM);
	  //	if (rc < 0) return rc;
		codeEncode.addStatement("rc = mal_encoder_encode_short_form(encoder, cursor, " + shortForm + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthAbstractAttribute(CFileWriter codeLength, String tagName, String varName) throws IOException
  {
		//		rc = mal_encoder_add_attribute_tag_encoding_length(encoder, <attribute_tag>, cursor);
		//		if (rc < 0) return rc;
		//		rc = mal_encoder_add_attribute_encoding_length(encoder, <attribute_tag>, <element>, cursor);
		//		if (rc < 0) return rc;
		codeLength.addStatement("rc = mal_encoder_add_attribute_tag_encoding_length(encoder, " + tagName + ", cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
		codeLength.addStatement("rc = mal_encoder_add_attribute_encoding_length(encoder, " + tagName + ", " + varName + ", cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeAbstractAttribute(CFileWriter codeEncode, String tagName, String varName) throws IOException
  {
		//		rc = mal_encoder_encode_attribute_tag(encoder, cursor, <attribute_tag>);
		//		if (rc < 0) return rc;
  	//		rc = mal_encoder_encode_attribute(encoder, cursor, <attribute_tag>, <element>);
		//		if (rc < 0) return rc;
		codeEncode.addStatement("rc = mal_encoder_encode_attribute_tag(encoder, cursor, " + tagName + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
		codeEncode.addStatement("rc = mal_encoder_encode_attribute(encoder, cursor, " + tagName + ", " + varName + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeAbstractAttribute(CFileWriter codeDecode, String tagName, String varName) throws IOException
  {
  	//		rc = mal_decoder_decode_attribute_tag(decoder, cursor, &<attribute_tag>);
  	//		if (rc < 0) return rc;
  	//		switch (<attribute_tag>) {
  	//		case <ATTRIBUTE>:
  	//			rc = mal_decoder_decode_<attribute>(decoder, cursor, &<element>.<attribute>_value);
  	//			break;
  	//		...
  	//		}
  	//		if (rc < 0) return rc;
	  StringBuilder buf = new StringBuilder();
	  buf.append("rc = mal_decoder_decode_attribute_tag(decoder, cursor, ");
	  if (tagName.charAt(0) == '*')
	  {
		  buf.append(tagName.substring(1));
	  }
	  else
	  {
		  buf.append("&").append(tagName);
	  }
	  buf.append(");");
	  codeDecode.addStatement(buf.toString());
	  codeDecode.addStatement("if (rc < 0)", 1);
	  codeDecode.addStatement("return rc;", -1);
	  codeDecode.addStatement("rc = mal_decoder_decode_attribute(decoder, cursor, " + tagName + ", " + varName + ");");
	  codeDecode.addStatement("if (rc < 0)", 1);
	  codeDecode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthAttribute(CFileWriter codeLength, String varName, String varType) throws IOException
  {
  	//		rc = mal_encoder_add_<attribute>_encoding_length(encoder, <element>, cursor);
  	//		if (rc < 0) return rc;
  	codeLength.addStatement("rc = mal_encoder_add_" + varType + "_encoding_length(encoder, " + varName + ", cursor);");
  	codeLength.addStatement("if (rc < 0)", 1);
  	codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeAttribute(CFileWriter codeEncode, String varName, String varType) throws IOException
  {
  	//		rc = mal_encoder_encode_<attribute>(encoder, cursor, <element>);
  	//		if (rc < 0) return rc;
		codeEncode.addStatement("rc = mal_encoder_encode_" + varType + "(encoder, cursor, " + varName + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeAttribute(CFileWriter codeDecode, String varName, String varType) throws IOException
  {
  	//		rc = mal_decoder_decode_<attribute>(decoder, cursor, &<element>);
  	//		if (rc < 0) return rc;
  	StringBuilder buf = new StringBuilder();
  	buf.append("rc = mal_decoder_decode_").append(varType).append("(decoder, cursor, ");
		if (varName.charAt(0) == '*')
		{
			buf.append(varName.substring(1));
		}
		else
		{
			buf.append("&").append(varName);
		}
		buf.append(");");
		codeDecode.addStatement(buf.toString());
		codeDecode.addStatement("if (rc < 0)", 1);
		codeDecode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthComposite(CFileWriter codeLength, String varName, String varType) throws IOException
  {
		//		rc = <area>_[<service>_]<composite>_add_encoding_length_malbinary(<element>, encoder, cursor);
		//		if (rc < 0) return rc;
		codeLength.addStatement("rc = " + varType + "_add_encoding_length_" + transportMalbinary + "(" + varName + ", encoder, cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeComposite(CFileWriter codeEncode, String varName, String varType) throws IOException
  {
  	//		rc = <area>_[<service>_]<composite>_encode_malbinary(<element>, encoder, cursor);
		//		if (rc < 0) return rc;
		codeEncode.addStatement("rc = " + varType + "_encode_" + transportMalbinary + "(" + varName + ", encoder, cursor);");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeComposite(CFileWriter codeDecode, String varName, String varType, boolean doCast) throws IOException
  {
  	//		<element> = <area>_[<service>_]<composite>_new();
  	//		if (<element> == NULL) return -1;
  	//		rc = <area>_[<service>_]<composite>_decode_malbinary(<element>, decoder, cursor);
    //		if (rc < 0) return rc;
  	codeDecode.addStatement(varName + " = " + varType + "_new();");
  	codeDecode.addStatement("if (" + varName + " == NULL) return -1;");
  	if (doCast)
  		codeDecode.addStatement("rc = " + varType + "_decode_" + transportMalbinary + "((" + varType + "_t *)" + varName + ", decoder, cursor);");
  	else
  		codeDecode.addStatement("rc = " + varType + "_decode_" + transportMalbinary + "(" + varName + ", decoder, cursor);");
  	codeDecode.addStatement("if (rc < 0)", 1);
  	codeDecode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthList(CFileWriter codeLength, String varName, String varType) throws IOException
  {
		//		rc = <area>_[<service>_]<type>_list_add_encoding_length_malbinary(<element>, encoder, cursor);
		//		if (rc < 0) return rc;
		codeLength.addStatement("rc = " + varType + "_list_add_encoding_length_" + transportMalbinary + "(" + varName + ", encoder, cursor);");
		codeLength.addStatement("if (rc < 0)", 1);
		codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeList(CFileWriter codeEncode, String varName, String varType) throws IOException
  {
  	//		rc = <area>_[<service>_]<type>_list_encode_malbinary(<element>, encoder, cursor);
		//		if (rc < 0) return rc;
  	codeEncode.addStatement("rc = " + varType + "_list_encode_" + transportMalbinary + "(" + varName + ", encoder, cursor);");
  	codeEncode.addStatement("if (rc < 0)", 1);
  	codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeList(CFileWriter codeDecode, String varName, String varType, boolean doCast) throws IOException
  {
  	//		<element> = <area>_[<service>_]<type>_list_new(0);
  	//		if (<element> == NULL) return -1;
  	//		rc = <area>_[<service>_]<type>_list_decode_malbinary(<element>, decoder, cursor);
  	codeDecode.addStatement(varName + " = " + varType + "_list_new(0);");
  	codeDecode.addStatement("if (" + varName + " == NULL) return -1;");
  	if (doCast) {
  		codeDecode.addStatement("rc = " + varType + "_list_decode_" + transportMalbinary + "((" + varType + "_list_t *)" + varName + ", decoder, cursor);");
  	} else {
  		codeDecode.addStatement("rc = " + varType + "_list_decode_" + transportMalbinary + "(" + varName + ", decoder, cursor);");
  	}
  	codeDecode.addStatement("if (rc < 0)", 1);
  	codeDecode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthEnumeration(CFileWriter codeLength, String varName, MalbinaryEnumSize enumMBSize) throws IOException
  {
  	//		rc = mal_encoder_add_[small|medium|large]_enum_encoding_length(encoder, <element>, cursor);
  	//		if (rc < 0) return rc;
  	codeLength.addStatement("rc = mal_encoder_add_" + enumMBSize.getCgenPrefix() + "_enum_encoding_length(encoder, " + varName + ", cursor);");
  	codeLength.addStatement("if (rc < 0)", 1);
  	codeLength.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeEnumeration(CFileWriter codeEncode, String varName, MalbinaryEnumSize enumMBSize) throws IOException
  {
  	//		rc = mal_encoder_encode_[small|medium|large]_enum(encoder, cursor, <element>);
		//		if (rc < 0) return rc;
		codeEncode.addStatement("rc = mal_encoder_encode_" + enumMBSize.getCgenPrefix() + "_enum(encoder, cursor, " + varName + ");");
		codeEncode.addStatement("if (rc < 0)", 1);
		codeEncode.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeEnumeration(CFileWriter codeDecode, String varName, String varType, MalbinaryEnumSize enumMBSize) throws IOException
  {
  	// assumes a local enumerated_value variable has been declared
  	//		rc = mal_decoder_decode_[small|medium|large]_enum(decoder, cursor, &enumerated_value);
		//		if (rc < 0) return rc;
  	//		<element> = (<area>_[<service>_]<enum>_t) enumerated_value;
  	codeDecode.addStatement("rc = mal_decoder_decode_" + enumMBSize.getCgenPrefix() + "_enum(decoder, cursor, &enumerated_value);");
  	codeDecode.addStatement("if (rc < 0)", 1);
  	codeDecode.addStatement("return rc;", -1);
  	codeDecode.addStatement(varName + " = (" + varType + "_t) enumerated_value;");
  }

  private void addCompositeConstructor(CompositeContext compCtxt) throws IOException
  {
    // declare the constructor prototype in the <composite>.h file and define it in the <composite>.c file
  	CFileWriter compositeH = compCtxt.compositeH;
  	CFileWriter compositeC = compCtxt.compositeC;
  	
    String comment = "default constructor";
    compositeH.addNewLine();
    compositeH.addSingleLineComment(comment);
    compositeC.addNewLine();
    compositeC.addSingleLineComment(comment);
    
    // <area>_[<service>_]<composite>_t * <area>_[<service>_]<composite>_new(void);
    compositeH.openFunctionPrototype(compCtxt.mapCompNameL + "_t *", compCtxt.mapCompNameL + "_new", 0);
    compositeH.closeFunctionPrototype(); 

    // <area>_[<service>_]<composite>_t * <area>_[<service>_]<composite>_new(void) {
    //	<area>_[<service>_]<composite>_t *self = (<area>_[<service>_]<composite>_t *) calloc(1, sizeof(<area>_[<service>_]<composite>_t));
    //	if (!self)
    //		return NULL;
    //	return self;
    // }
    compositeC.openFunction(compCtxt.mapCompNameL + "_t *", compCtxt.mapCompNameL + "_new", 0);
    compositeC.openFunctionBody();
    compositeC.addStatement(compCtxt.mapCompNameL + "_t *self = (" + compCtxt.mapCompNameL + "_t *) calloc(1, sizeof(" + compCtxt.mapCompNameL + "_t));");
    compositeC.addStatement("if (!self)", 1);
    compositeC.addStatement("return NULL;", -1);
    compositeC.addStatement("return self;");
    compositeC.closeFunctionBody();
  }
  
  private void addCompositeDestructor(CompositeContext compCtxt) throws IOException
  {
  	CFileWriter compositeH = compCtxt.compositeH;
  	CFileWriter compositeC = compCtxt.compositeC;
  	
  	String comment = "destructor";
  	compositeH.addNewLine();
  	compositeH.addSingleLineComment(comment);
  	compositeC.addNewLine();
  	compositeC.addSingleLineComment(comment);
  	
  	// void <area>_[<service>_]<composite>_destroy(<area>_[<service>_]<composite>_t **self_p);
  	compositeH.openFunctionPrototype("void", compCtxt.mapCompNameL + "_destroy", 1);
  	compositeH.addFunctionParameter(compCtxt.mapCompNameL + "_t **", "self_p", true);
  	compositeH.closeFunctionPrototype();
  	
  	// void <area>_[<service>_]<composite>_destroy(<area>_[<service>_]<composite>_t **self_p) {
  	// destroy the relevant fields
  	//	free(*self_p);
  	//	(*self_p) = NULL;
  	// }
  	compositeC.openFunction("void", compCtxt.mapCompNameL + "_destroy", 1);
  	compositeC.addFunctionParameter(compCtxt.mapCompNameL + "_t **", "self_p", true);
  	compositeC.openFunctionBody();
  	compositeC.addStatements(compCtxt.destroyCodeW);
  	compositeC.addStatement("free(*self_p);");
  	compositeC.addStatement("(*self_p) = NULL;");
  	compositeC.closeFunctionBody();
  }
  
  private void addCompFieldDestroy(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
  {
  	CFileWriter destroyCode = compCtxt.destroyCode;
  	
		if (cfDetails.isAbstractAttribute)
		{
			//	[if ((*self_p)-><f_><field>_is_present)]
			//		mal_attribute_destroy(&(*self_p)-><f_><field>, (*self_p)-><f_><field>_attribute_tag);
			if (cfDetails.isPresentField)
			{
				destroyCode.addStatement("if ((*self_p)->" + fieldPrefix + cfDetails.fieldName + "_is_present)");
				destroyCode.openBlock();
			}
			destroyCode.addStatement("mal_attribute_destroy(&(*self_p)->" + fieldPrefix + cfDetails.fieldName + ", (*self_p)->" + fieldPrefix + cfDetails.fieldName + "_attribute_tag);");
			if (cfDetails.isPresentField)
			{
				destroyCode.closeBlock();
			}
		}
		else
		{
			//	if ((*self_p)-><f_><field>!= NULL)
	    //		<qftype>_[list_]destroy(&(*self_p)-><f_><field>);
	  	String destructor;
			if (cfDetails.type.isList()) {
				destructor = cfDetails.qfTypeNameL + "_list_destroy";
			} else {
				destructor = cfDetails.qfTypeNameL + "_destroy";
			}
			destroyCode.addStatement("if ((*self_p)->" + fieldPrefix + cfDetails.fieldName + " != NULL)");
			destroyCode.openBlock();
			destroyCode.addStatement(destructor + "(& (*self_p)->" + fieldPrefix + cfDetails.fieldName + ");");
			destroyCode.closeBlock();
		}
  }
  
  private void addAreaTestFunction(AreaContext areaContext) throws IOException
  {
    String comment = "test function";
    areaContext.areaH.addNewLine();
    areaContext.areaH.addSingleLineComment(comment);
    areaContext.areaC.addNewLine();
    areaContext.areaC.addSingleLineComment(comment);
    // void <area>_test(bool verbose);
    areaContext.areaH.openFunctionPrototype("void", areaContext.areaNameL + "_test", 1);
    areaContext.areaH.addFunctionParameter("bool", "verbose", true);
    areaContext.areaH.closeFunctionPrototype();
    // void <area>_test(bool verbose) {
    //	printf(" * <area>: ");
    //	if (verbose)
    //		printf("\n");
    //	printf("OK\n");
    // }
    areaContext.areaC.openFunctionPrototype("void", areaContext.areaNameL + "_test", 1);
    areaContext.areaC.addFunctionParameter("bool", "verbose", true);
    areaContext.areaC.openFunctionBody();
    areaContext.areaC.addStatement("printf(\" * " + areaContext.areaNameL + ": \");");
    areaContext.areaC.addStatement("if (verbose)", 1);
    areaContext.areaC.addStatement("printf(\"\\n\");", -1);
    areaContext.areaC.addStatement("printf(\"OK\\n\");", -1);
    areaContext.areaC.closeFunctionBody();
  }

  private void addCompositeTestFunction(CompositeContext compCtxt) throws IOException
  {
  	CFileWriter compositeH = compCtxt.compositeH;
  	CFileWriter compositeC = compCtxt.compositeC;
    String comment = "test function";
    compositeH.addNewLine();
    compositeH.addSingleLineComment(comment);
    compositeC.addNewLine();
    compositeC.addSingleLineComment(comment);
    // void <area>_[<service>_]<composite>_test(bool verbose);
    compositeH.openFunctionPrototype("void", compCtxt.mapCompNameL + "_test", 1);
    compositeH.addFunctionParameter("bool", "verbose", true);
    compositeH.closeFunctionPrototype();
    // void <area>_[<service>_]<composite>_test(bool verbose) {
    //	printf(" * <area>:[<service>:]<composite>: ");
    //	if (verbose)
    //		printf("\n");
    //	printf("OK\n");
    // }
    compositeC.openFunctionPrototype("void", compCtxt.mapCompNameL + "_test", 1);
    compositeC.addFunctionParameter("bool", "verbose", true);
    compositeC.openFunctionBody();
    StringBuilder buf = new StringBuilder();
    buf.append(compCtxt.areaContext.area.getName());
    if (compCtxt.serviceContext != null)
    {
    	buf.append(":").append(compCtxt.serviceContext.summary.getService().getName());
    }
    buf.append(":").append(compCtxt.composite.getName());
    compositeC.addStatement("printf(\" * " + buf.toString() + ": \");");
    compositeC.addStatement("if (verbose)", 1);
    compositeC.addStatement("printf(\"\\n\");", -1);
    compositeC.addStatement("printf(\"OK\\n\");", -1);
    compositeC.closeFunctionBody();
  }

  private void addEnumListTestFunction(CFileWriter enumListH, CFileWriter enumListC, String mapEnumNameL) throws IOException
  {
    String comment = "test function";
    enumListH.addNewLine();
    enumListH.addSingleLineComment(comment);
    enumListC.addNewLine();
    enumListC.addSingleLineComment(comment);
    // void <area>_[<service>_]<enumeration>_list_test(bool verbose);
    enumListH.openFunctionPrototype("void", mapEnumNameL + "_list_test", 1);
    enumListH.addFunctionParameter("bool", "verbose", true);
    enumListH.closeFunctionPrototype();
    // void <area>_[<service>_]<enumeration>_list_test(bool verbose) {
    //	printf(" * list of <area>_[<service>_]<enumeration>: ");
    //	if (verbose)
    //		printf("\n");
    //	printf("OK\n");
    // }
    enumListC.openFunction("void", mapEnumNameL + "_list_test", 1);
    enumListC.addFunctionParameter("bool", "verbose", true);
    enumListC.openFunctionBody();
    enumListC.addStatement("printf(\" * list of " + mapEnumNameL + ": \");");
    enumListC.addStatement("if (verbose)", 1);
    enumListC.addStatement("printf(\"\\n\");", -1);
    enumListC.addStatement("printf(\"OK\\n\");", -1);
    enumListC.closeFunctionBody();
  }

  private void addCompositeListTestFunction(CFileWriter compListH, CFileWriter compListC, CompositeContext compCtxt) throws IOException
  {
    String comment = "test function";
    compListH.addNewLine();
    compListH.addSingleLineComment(comment);
    compListC.addNewLine();
    compListC.addSingleLineComment(comment);
    // void <area>_[<service>_]<composite>_list_test(bool verbose);
    compListH.openFunctionPrototype("void", compCtxt.mapCompNameL + "_list_test", 1);
    compListH.addFunctionParameter("bool", "verbose", true);
    compListH.closeFunctionPrototype();
    // void <area>_[<service>_]<composite>_list_test(bool verbose) {
    //	printf(" * list of <area>_[<service>_]<composite>: ");
    //	if (verbose)
    //		printf("\n");
    //	printf("OK\n");
    // }
    compListC.openFunction("void", compCtxt.mapCompNameL + "_list_test", 1);
    compListC.addFunctionParameter("bool", "verbose", true);
    compListC.openFunctionBody();
    compListC.addStatement("printf(\" * list of " + compCtxt.mapCompNameL + ": \");");
    compListC.addStatement("if (verbose)", 1);
    compListC.addStatement("printf(\"\\n\");", -1);
    compListC.addStatement("printf(\"OK\\n\");", -1);
    compListC.closeFunctionBody();
  }

  private void addInitInteractionFunction(OperationContext opContext, String opStage, List<TypeInfo> parameters) throws IOException
  {
  	OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, parameters);
  	addInteractionFunction(opStageCtxt);
  }

  private void addResultInteractionFunctions(OperationContext opContext, String opStage, List<TypeInfo> parameters) throws IOException
  {
  	OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, false, parameters);
  	addInteractionFunction(opStageCtxt);
  }
  
  private void addInteractionFunction(OpStageContext opStageCtxt) throws IOException
  {
  	// declare the function in the <area>.h file and define it in the <area>.c file
  	CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
  	CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
  	areaC.addNewLine();
  	StringBuilder buf;
  	
  	// int <area>_<service>_<operation>_<first stage>(mal_endpoint_t *endpoint, mal_message_t *message, 
		// init -> mal_uri_t *provider_uri
  	// result-> mal_message_t *result_message, bool is_error_message
  	// );
  	areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, (opStageCtxt.isInit ? 3 : 4));
  	areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
  	areaH.addFunctionParameter("mal_message_t *", "init_message", false);
  	if (opStageCtxt.isInit)
  	{
  		areaH.addFunctionParameter("mal_uri_t *", "provider_uri", true);
  	}
  	else
  	{
  		areaH.addFunctionParameter("mal_message_t *", "result_message", false);
  		areaH.addFunctionParameter("bool", "is_error_message", true);
  	}
  	areaH.closeFunctionPrototype();

  	// int <area>_<service>_<operation>_<result stage>(mal_endpoint_t *endpoint, mal_message_t *init_message,
  	// init -> mal_uri_t *provider_uri
  	// result -> mal_message_t *result_message, bool is_error_message
  	// ) {
    //	int rc = 0;
  	//	mal_message_init([init|result]_message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
    //		<AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_<IP>, MAL_IP_STAGE_<STAGE>);
  	// init ->		rc = mal_endpoint_init_operation(endpoint, init_message, true);
  	// result ->	rc = mal_endpoint_return_operation(endpoint, init_message, result_message, is_error_message);
    //	return rc;
  	// }
  	areaC.openFunction("int", opStageCtxt.qfOpStageNameL, (opStageCtxt.isInit ? 3 : 4));
  	areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
  	areaC.addFunctionParameter("mal_message_t *", "init_message", false);
  	if (opStageCtxt.isInit)
  	{
  		areaC.addFunctionParameter("mal_uri_t *", "provider_uri", true);
  	}
  	else
  	{
  		areaC.addFunctionParameter("mal_message_t *", "result_message", false);
  		areaC.addFunctionParameter("bool", "is_error_message", true);
  	}
  	areaC.openFunctionBody();
  	areaC.addStatement("int rc = 0;");
  	String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
  	String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
    buf = new StringBuilder();
    buf.append("mal_message_init(" + (opStageCtxt.isInit ? "init" : "result") + "_message, ");
    buf.append(areaNameU).append("_AREA_NUMBER, ");
    buf.append(areaNameU).append("_AREA_VERSION, ");
    buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
    buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
    buf.append("MAL_INTERACTIONTYPE_");
    switch (opStageCtxt.opContext.operation.getPattern())
    {
    case SEND_OP:
    	buf.append("SEND");
    	break;
    case SUBMIT_OP:
    	buf.append("SUBMIT");
    	break;
    case REQUEST_OP:
    	buf.append("REQUEST");
    	break;
    case INVOKE_OP:
    	buf.append("INVOKE");
    	break;
    case PROGRESS_OP:
    	buf.append("PROGRESS");
    	break;
    	default:
    		throw new IllegalStateException("unexpected IP for operation " + opStageCtxt.opContext.qfOpNameL);
    }
    buf.append(", MAL_IP_STAGE_").append(opStageCtxt.opStage.toUpperCase());
    buf.append(");");
    areaC.addStatement(buf.toString());
    if (opStageCtxt.isInit)
    {
    	areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, init_message, provider_uri, true);");
    }
    else
    {
    	areaC.addStatement("rc = mal_endpoint_return_operation(endpoint, init_message, result_message, is_error_message);");
    }
    areaC.addStatement("return rc;");
  	areaC.closeFunctionBody();
  	
    // generate all encoding code related to the parameters
    List<TypeInfo> parameters = opStageCtxt.parameters;
    if (parameters != null)
    {
    	int paramsNumber = parameters.size();
    	int paramIndex = 0;
    	for (TypeInfo param : parameters)
    	{
    		TypeReference paramType = param.getSourceType();

    		// keep the parameter area name for future include
    		opStageCtxt.opContext.serviceContext.areaContext.reqAreas.add(paramType.getArea());
			
    		ParameterDetails paramDetails = new ParameterDetails();
    		paramDetails.type = paramType;
    		paramDetails.paramName = param.getFieldName();
    		paramDetails.paramIndex = paramIndex;
    		if (paramIndex == paramsNumber - 1)
    		{
    			paramDetails.isLast = true;
    		}
    		if (isAbstract(paramType))
    		{
    			paramDetails.isAbstract = true;
    			// check for abstract Attribute type
    			if (StdStrings.MAL.equals(paramType.getArea()) &&
    					StdStrings.ATTRIBUTE.equals(paramType.getName()))
    			{
  					paramDetails.isAbstractAttribute = true;
    			}
    			if (paramDetails.isAbstractAttribute &&
    					! paramType.isList()) {
    					paramDetails.isPresenceFlag = true;

    					addInteractionParamXcodingFunctions(opStageCtxt, paramDetails);
    			}
    			else
    			{
    				if (! paramDetails.isLast)
    				{
    					throw new IllegalArgumentException("non Attribute abstract type for a non terminal parameter " + paramDetails.paramName + " in function " + opStageCtxt.qfOpStageNameL);
    				}
      			try {
      				addInteractionAbstractParamXcodingFunctions(opStageCtxt, paramDetails, paramType);
      			} catch (IllegalArgumentException exc) {
      				throw new IllegalArgumentException("for parameter " + param.getFieldName() + " in operation " + opStageCtxt.qfOpStageNameL, exc);
      			}
    			}
    		}
    		else
    		{
    			try {
    				fillInteractionParamDetails(paramDetails, paramType);
        		addInteractionParamXcodingFunctions(opStageCtxt, paramDetails);
    			} catch (IllegalArgumentException exc) {
    				throw new IllegalArgumentException("for parameter " + param.getFieldName() + " in operation " + opStageCtxt.qfOpStageNameL, exc);
    			}
    		}
    	
    		paramIndex ++;
    	}
    }
  }
  
  /**
   * Fill in the ParameterDetails structure from the paramType parameter.
   * paramType refers to a concrete type.
   */
  private void fillInteractionParamDetails(ParameterDetails paramDetails, TypeReference paramType) throws IOException {
		// build the fully qualified name of the parameter type for the C mapping (lower case)
		// <area>_[<service>_]<param type>
		// attribute type <attribute> naturally gives a qualified name mal_<attribute>
  	StringBuilder buf = new StringBuilder();
		buf.append(paramType.getArea().toLowerCase());
		buf.append("_");
		if (paramType.getService() != null)
		{
			buf.append(paramType.getService().toLowerCase());
			buf.append("_");
		}
		buf.append(paramType.getName().toLowerCase());
		paramDetails.qfTypeNameL = buf.toString();
		
		if (paramType.isList())
		{
			paramDetails.isList = true;
			//	<qualified type>_list_t * <field>;
			paramDetails.paramType = paramDetails.qfTypeNameL + "_list_t *";
		}
		else if (isAttributeType(paramType))
		{
			paramDetails.isAttribute = true;
			// fieldType is also <qfTypeNameL>_t, with an optional *
			paramDetails.paramType = getAttributeDetails(paramType).getTargetType();
			// if map type is not a pointer, declare the is_present field
			if (! paramDetails.paramType.endsWith("*"))
			{
				paramDetails.isPresenceFlag = true;
			}
		}
		else if (isEnum(paramType))
		{
			// compCtxt.holdsEnumField = true;
			paramDetails.isEnumeration = true;
			//	<qualified type>_t <field>;
			paramDetails.paramType = paramDetails.qfTypeNameL + "_t";
			paramDetails.isPresenceFlag = true;
		}
		else if (isComposite(paramType))
		{
			paramDetails.isComposite = true;
			//	<qualified field type>_t <field>;
			paramDetails.paramType = paramDetails.qfTypeNameL + "_t *";
		}
		else
		{
			throw new IllegalArgumentException("unexpected type " + paramType.toString());
		}
  }

  private void addInteractionParamXcodingFunctions(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
  	addInteractionParamEncodingFunctions(opStageContext, paramDetails);
  	addInteractionParamEncodingDecodeFunction(opStageContext, paramDetails);
  }

  private void addInteractionParamEncodingFunctions(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
  	addInteractionParamEncodingLengthFunction(opStageContext, paramDetails);
  	addInteractionParamEncodingEncodeFunction(opStageContext, paramDetails);
  }
  
  private void addInteractionErrorXcodingFunctions(OperationContext opContext, OperationErrorList errors) throws IOException
  {
  	// the algorithm reuses the standard generation functions for operation parameters
  	// we must fill in the expected data structures, as it is done in addInteractionFunction

	OpStageContext opStageCtxt = new OpStageContext(opContext, "error", false, null);
	List<TypeReference> errorTypes = getOpErrorTypes(errors);
	for (TypeReference errorType : errorTypes) {
		addInteractionErrorEncodingFunctions(opStageCtxt, errorType);

		if (StdStrings.MAL.equals(errorType.getArea()) &&
				StdStrings.ELEMENT.equals(errorType.getName()) &&
				!errorType.isList())
		{
			// default case, also generate MAL::Attribute encoding functions
			TypeReference attType = new TypeReference();
			attType.setArea(StdStrings.MAL);
			attType.setName(StdStrings.ATTRIBUTE);
			addInteractionErrorEncodingFunctions(opStageCtxt, attType);
		}
	}

  	// decoding an error is similar to decoding a MAL::Element
		ParameterDetails paramDetails = new ParameterDetails();
		paramDetails.type = new TypeReference();
		paramDetails.type.setArea(StdStrings.MAL);
		paramDetails.type.setName(StdStrings.ELEMENT);
		paramDetails.isError = true;
		paramDetails.paramName = null;
		paramDetails.paramIndex = -1;
		paramDetails.isAbstract = true;
  	addInteractionParamEncodingDecodeFunction(opStageCtxt, paramDetails);
  }
  
  private void addInteractionErrorEncodingFunctions(OpStageContext opStageCtxt, TypeReference errorType) throws IOException
  {
  	
		// keep the error area name for future include
		opStageCtxt.opContext.serviceContext.areaContext.reqAreas.add(errorType.getArea());
	
		ParameterDetails paramDetails = new ParameterDetails();
		paramDetails.type = errorType;
		paramDetails.isError = true;
		paramDetails.paramName = null;
		paramDetails.paramIndex = -1;
		if (isAbstract(errorType))
		{
			paramDetails.isAbstract = true;
			// check for abstract Attribute type
			if (StdStrings.MAL.equals(errorType.getArea()) &&
					StdStrings.ATTRIBUTE.equals(errorType.getName()))
			{
				paramDetails.isAbstractAttribute = true;
			}
			if (paramDetails.isAbstractAttribute &&
					! errorType.isList()) {
					paramDetails.isPresenceFlag = true;

					try {
						// we need qfTypeNameL to be set
						paramDetails.qfTypeNameL = "mal_attribute";
		    		addInteractionParamEncodingFunctions(opStageCtxt, paramDetails);
					} catch (IllegalArgumentException exc) {
						throw new IllegalArgumentException("for error type " + new TypeKey(errorType) + " in operation " + opStageCtxt.qfOpStageNameL, exc);
					}
			}
			else
			{
  			try {
  				addInteractionAbstractParamEncodingFunctions(opStageCtxt, paramDetails, errorType);
  			} catch (IllegalArgumentException exc) {
  				throw new IllegalArgumentException("for error type " + new TypeKey(errorType) + " in operation " + opStageCtxt.qfOpStageNameL, exc);
  			}
			}
		}
		else
		{
			try {
				fillInteractionParamDetails(paramDetails, errorType);
    		addInteractionParamEncodingFunctions(opStageCtxt, paramDetails);
			} catch (IllegalArgumentException exc) {
				throw new IllegalArgumentException("for error type " + new TypeKey(errorType) + " in operation " + opStageCtxt.qfOpStageNameL, exc);
			}
		}
  }
  
  private void addInteractionAbstractParamXcodingFunctions(OpStageContext opStageContext, ParameterDetails paramDetails, TypeReference abstractType) throws IOException
  {
  	addInteractionAbstractParamEncodingFunctions(opStageContext, paramDetails, abstractType);
  	addInteractionParamEncodingDecodeFunction(opStageContext, paramDetails);
  }

  private void addInteractionAbstractParamEncodingFunctions(OpStageContext opStageContext, ParameterDetails paramDetails, TypeReference abstractType) throws IOException
  {
		// find all compatible concrete types
		
		if (paramDetails.isAbstractAttribute)
		{
			if (! abstractType.isList())
			{
				throw new IllegalStateException("abstract attribute type should be list at this point");
			}
			// List of MAL::Attribute
			// generate code for all List of concrete Attribute type
			Set <TypeKey> keys = attributeTypesMap.keySet();
			for (TypeKey key : keys) {
				// generate functions for the attribute type
				ParameterDetails cpDetails = new ParameterDetails();
				cpDetails.type = key.getTypeReference(true);
				cpDetails.paramName = paramDetails.paramName;
				cpDetails.paramIndex = paramDetails.paramIndex;
				cpDetails.isLast = true;
				cpDetails.isPolymorph = true;
				cpDetails.isError = paramDetails.isError;
				
				fillInteractionParamDetails(cpDetails, cpDetails.type);
				addInteractionParamEncodingLengthFunction(opStageContext, cpDetails);
				addInteractionParamEncodingEncodeFunction(opStageContext, cpDetails);
			}
		}
		else if (StdStrings.MAL.equals(abstractType.getArea()))
		{
			// MAL::Element -> any concrete type or list of concrete type
			// List of MAL::Element -> any list of concrete type
			// MAL::Composite -> any concrete composite type or list of any concrete type
			// List of MAL::Composite -> any list of concrete composite type
			
			boolean isElement = StdStrings.ELEMENT.equals(abstractType.getName());
			boolean isComposite = StdStrings.COMPOSITE.equals(abstractType.getName());
			boolean isList = abstractType.isList();
			int found = 0;
			
			if (!isElement && !isComposite)
			{
				throw new IllegalStateException("abstract attribute type from MAL area should be Element or Composite");
			}
			
			if (isComposite)
			{
				// MAL::Composite -> get any concrete composite type
				// List of MAL::Composite -> get any list of concrete composite type
				Set <TypeKey> keys = compositeTypesMap.keySet();
				for (TypeKey key : keys) {
					if (abstractTypesSet.contains(key))
					{
						// ignore abstract types
						continue;
					}
					CompositeType theType = compositeTypesMap.get(key);
					if (theType == null)
					{
						throw new IllegalStateException("unknown value for key " + key + " in table compositeTypesMap");
					}
					found ++;
					// generate functions for theType
					ParameterDetails cpDetails = new ParameterDetails();
					cpDetails.type = key.getTypeReference(isList);
					cpDetails.paramName = paramDetails.paramName;
					cpDetails.paramIndex = paramDetails.paramIndex;
					cpDetails.isLast = true;
					cpDetails.isPolymorph = true;
					cpDetails.isError = paramDetails.isError;
			    		
					fillInteractionParamDetails(cpDetails, cpDetails.type);
					addInteractionParamEncodingLengthFunction(opStageContext, cpDetails);
					addInteractionParamEncodingEncodeFunction(opStageContext, cpDetails);
				}
			}
			
			if (!isComposite || !isList)
			{
				// MAL::Element -> get any concrete type or list of concrete type
				// List of MAL::Element -> get any list of concrete type
				// MAL::Composite -> get any list of any concrete type
				Set <TypeKey> keys = allTypesMap.keySet();
				for (TypeKey key : keys) {
					if (abstractTypesSet.contains(key))
					{
						// ignore abstract types
						continue;
					}
					found ++;
					
					// generate functions for List of key type
					ParameterDetails cpDetails = new ParameterDetails();
					cpDetails.type = key.getTypeReference(true);
					cpDetails.paramName = paramDetails.paramName;
					cpDetails.paramIndex = paramDetails.paramIndex;
					cpDetails.isLast = true;
					cpDetails.isPolymorph = true;
					cpDetails.isError = paramDetails.isError;
			    		
					fillInteractionParamDetails(cpDetails, cpDetails.type);
					addInteractionParamEncodingLengthFunction(opStageContext, cpDetails);
					addInteractionParamEncodingEncodeFunction(opStageContext, cpDetails);
					
					if (isElement) {
						cpDetails = new ParameterDetails();
						cpDetails.type = key.getTypeReference(false);
						cpDetails.paramName = paramDetails.paramName;
						cpDetails.paramIndex = paramDetails.paramIndex;
						cpDetails.isLast = true;
						cpDetails.isPolymorph = true;
						cpDetails.isError = paramDetails.isError;
				    		
						fillInteractionParamDetails(cpDetails, cpDetails.type);
						addInteractionParamEncodingLengthFunction(opStageContext, cpDetails);
						addInteractionParamEncodingEncodeFunction(opStageContext, cpDetails);
					}
				}
			}
			
			if (found == 0)
			{
				throw new IllegalStateException("Found no compatible type for " + new TypeKey(abstractType));
			}
		}
		else
		{
			// Abstract Composite and List of Abstract Composite
			int found = 0;
			Set <TypeKey> keys = compositeTypesMap.keySet();
			for (TypeKey key : keys) {
				if (abstractTypesSet.contains(key))
				{
					// ignore abstract types
					continue;
				}
				CompositeType theType = compositeTypesMap.get(key);
				if (theType == null)
				{
					throw new IllegalStateException("unknown value for key " + key + " in table compositeTypesMap");
				}
				// check for abstractType in theType type hierarchy
				CompositeType ptype = theType;
				parentTypeLoop:
					while (true)
					{
						if (ptype.getExtends() == null)
						{
							// should never occur if we get out of the loop at MAL::Composite
							ptype = null;
							break parentTypeLoop;
						}
						TypeReference parent = ptype.getExtends().getType();
						if (StdStrings.MAL.equals(parent.getArea()) && StdStrings.COMPOSITE.equals(parent.getName()))
						{
							// we must check this exit case as MAL::Composite is not in the compositeTypesMap table
							ptype = null;
							break parentTypeLoop;
						}
						if (abstractType.getName().equals(parent.getName()) &&
								abstractType.getArea().equals(parent.getArea()) &&
								(abstractType.getService() == null ? parent.getService() == null :
									abstractType.getService().equals(parent.getService())))
						{
							// abstractType is a parent of theType
							break parentTypeLoop;
						}
						TypeKey pkey = new TypeKey(parent);
						ptype = compositeTypesMap.get(pkey);
						if (ptype == null)
						{
							throw new IllegalStateException("Type " + pkey + " not found in table compositeTypesMap");
						}
					}
				if (null != ptype)
				{
					found ++;
					// generate functions for theType
					ParameterDetails cpDetails = new ParameterDetails();
					cpDetails.type = key.getTypeReference(abstractType.isList());
					cpDetails.paramName = paramDetails.paramName;
					cpDetails.paramIndex = paramDetails.paramIndex;
					cpDetails.isLast = true;
					cpDetails.isPolymorph = true;
					cpDetails.isError = paramDetails.isError;
		    		
					fillInteractionParamDetails(cpDetails, cpDetails.type);
			  	addInteractionParamEncodingLengthFunction(opStageContext, cpDetails);
			  	addInteractionParamEncodingEncodeFunction(opStageContext, cpDetails);
				}
			}
			if (found == 0)
			{
				throw new IllegalStateException("Found no compatible type for " + new TypeKey(abstractType));
			}
			
		}
		// Generate for mal_element_holder_t
		addInteractionParamGenericEncodingLengthFunction(opStageContext, paramDetails);
  }
  
  private void addInteractionParamEncodingLengthFunction(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
  	CFileWriter areaH = opStageContext.opContext.serviceContext.areaContext.areaHContent;
  	CFileWriter areaC = opStageContext.opContext.serviceContext.areaContext.areaC;
		areaC.addNewLine();
    StringBuilder buf = new StringBuilder();
    buf.append(opStageContext.qfOpStageNameL);
    buf.append("_add_encoding_length");
    if (! paramDetails.isError)
    {
    	buf.append("_").append(paramDetails.paramIndex);
    }
    if (paramDetails.isPolymorph || paramDetails.isError)
    {
    	buf.append("_").append(paramDetails.qfTypeNameL);
      if (paramDetails.isList)
      {
      	buf.append("_list");
      }
    }
    String encodeFuncNameL = buf.toString();
    
  	if (paramDetails.isAbstractAttribute && !paramDetails.isList)
  	{
  		// the !isList test is not necessary as the type should not be polymorphic at this point
  		// int <qfop>_<stage|error>_add_encoding_length_<index>(
  		//	mal_encoder_t *encoder, bool presence_flag,
  		//	unsigned char attribute_tag, union mal_attribute_t element, 
  		//	void *cursor);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 5);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter("bool", "presence_flag", false);
  		areaH.addFunctionParameter("unsigned char", "attribute_tag", false);
  		areaH.addFunctionParameter("union mal_attribute_t", "element", false);
  		areaH.addFunctionParameter("void *", "cursor", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_add_encoding_length[_<index>](
  	  //	mal_encoder_t *encoder, bool presence_flag,
  	  //	unsigned char attribute_tag, union mal_attribute_t element, 
  	  //	void *cursor) {
  		areaC.openFunction("int", encodeFuncNameL, 5);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter("bool", "presence_flag", false);
  		areaC.addFunctionParameter("unsigned char", "attribute_tag", false);
  		areaC.addFunctionParameter("union mal_attribute_t", "element", false);
  		areaC.addFunctionParameter("void *", "cursor", true);
  		areaC.openFunctionBody();
  	}
  	else if (paramDetails.isAbstract)
  	{
  		// should never happen, as the function should be called with all possible concrete types
  		throw new IllegalArgumentException("addInteractionParamEncodingEncodeFunction called with abstract type for operation " + opStageContext.qfOpStageNameL + " and parameter " + paramDetails.paramName);
  	}
  	else if (paramDetails.isPresenceFlag)
  	{
  		// int <qfop>_<stage|error>_add_encoding_length_<index>[_<qftype>](
  	  //	mal_encoder_t *encoder, bool presence_flag, 
  	  //	<qftype>_t element, void *cursor);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 4);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter("bool", "presence_flag", false);
  		areaH.addFunctionParameter(paramDetails.paramType, "element", false);
  		areaH.addFunctionParameter("void *", "cursor", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_add_encoding_length[_<index>][_<qftype>](
  	  //	mal_encoder_t *encoder, bool presence_flag, 
  	  //	<qftype>_t element, void *cursor) {
  		areaC.openFunction("int", encodeFuncNameL, 4);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter("bool", "presence_flag", false);
  		areaC.addFunctionParameter(paramDetails.paramType, "element", false);
  		areaC.addFunctionParameter("void *", "cursor", true);
  		areaC.openFunctionBody();
  	}
  	else
  	{
  		// int <qfop>_<stage|error>_add_encoding_length_<index>[_<qftype>](
  	  //	mal_encoder_t *encoder, <qftype>_[list_]t *element,
  	  //	void * cursor);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 3);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter(paramDetails.paramType, "element", false);
  		areaH.addFunctionParameter("void *", "cursor", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_add_encoding_length[_<index>][_<qftype>](
  	  //	mal_encoder_t *encoder, <qftype>_[list_]t *element,
  	  //	void *cursor) {
  		areaC.openFunction("int", encodeFuncNameL, 3);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter(paramDetails.paramType, "element", false);
  		areaC.addFunctionParameter("void *", "cursor", true);
  		areaC.openFunctionBody();
  	}
  	
		//	int rc = 0;
		//	switch (encoder->encoding_format_code) {
  	//	case <FORMAT>_FORMAT_CODE: {
  	// format specific code
  	//		break;
  	//	}
  	//	default:
    //		rc = -1;
  	//	}
  	//	return rc;
  	// }
  	areaC.addStatement("int rc = 0;");
  	areaC.addStatement("switch (encoder->encoding_format_code)");
  	areaC.openBlock();
  	if (generateTransportMalbinary || generateTransportMalsplitbinary)
  	{
    	if (generateTransportMalbinary)
    	{
    		areaC.addStatement("case " + transportMalbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	if (generateTransportMalsplitbinary)
    	{
    		areaC.addStatement("case " + transportMalsplitbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	areaC.openBlock();
    	// TODO: null parameter in a PubSub operation
    	// an operation parameter is always nullable, except for PubSub
    	// a null PubSub parameter cannot be encoded with a presence flag
    	// the alternative is to encode it as an empty list
    	// however if the parameter type is abstract, we must find a compatible concrete type
  		String isPresent;
			if (paramDetails.isPresenceFlag)
			{
    		// 	presence_flag
				isPresent = "presence_flag";
			}
			else
			{
				// element is a pointer
				//	(element != NULL)
				isPresent = "(element != NULL)";
			}
    	addMalbinaryEncodingLengthPresenceFlag(areaC, isPresent);
  		areaC.addStatement("if (" + isPresent + ")");
    	areaC.openBlock();
    	if (paramDetails.isAbstractAttribute)
    	{
    		addMalbinaryEncodingLengthAbstractAttribute(areaC, "attribute_tag", "element");
    	}
    	else
    	{
    		if (paramDetails.isPolymorph)
    		{
    			String shortForm = paramDetails.qfTypeNameL.toUpperCase() +
    					(paramDetails.isList ? "_LIST_SHORT_FORM" : "_SHORT_FORM");
    			addMalbinaryEncodingLengthShortForm(areaC, shortForm);
    		}
    		if (paramDetails.isAttribute)
    		{
    			addMalbinaryEncodingLengthAttribute(areaC, "element", paramDetails.type.getName().toLowerCase());
    		}
    		else if (paramDetails.isComposite)
    		{
    			addMalbinaryEncodingLengthComposite(areaC, "element", paramDetails.qfTypeNameL);
    		}
    		else if (paramDetails.isList)
    		{
    			addMalbinaryEncodingLengthList(areaC, "element", paramDetails.qfTypeNameL);
    		}
    		else if (paramDetails.isEnumeration)
    		{
    			MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(paramDetails.type);
    			addMalbinaryEncodingLengthEnumeration(areaC, "element", enumMBSize);
    		}
    		else
    		{
    			throw new IllegalStateException("unexpected type for operation " + opStageContext.opContext.qfOpNameL + " parameter " + paramDetails.paramName + ": " + paramDetails.qfTypeNameL);
    		}
    	}
    	areaC.closeBlock();
    	areaC.addStatement("break;");
    	areaC.closeBlock();
  	}
  	areaC.addStatement("default:");
  	areaC.addStatement("rc = -1;");
  	areaC.closeBlock();
  	areaC.addStatement("return rc;");
  	areaC.closeFunctionBody();
  }
  
  private void addInteractionParamGenericEncodingLengthFunction(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
	  final AreaContext areaContext = opStageContext.opContext.serviceContext.areaContext;
	  CFileWriter areaH = areaContext.areaHContent;
	  CFileWriter areaC = areaContext.areaC;
	  areaC.addNewLine();
	  StringBuilder buf = new StringBuilder();
	  buf.append(opStageContext.qfOpStageNameL);
	  buf.append("_add_encoding_length");
	  buf.append("_").append(paramDetails.paramIndex);
	  String encodeFuncNameL = buf.toString();

	  // int <qfop>_<stage|error>_add_encoding_length_<index>(
	  //	mal_encoder_t *encoder, mal_element_holder_t *element,
	  //	void * cursor);
	  areaH.openFunctionPrototype("int", encodeFuncNameL, 3);
	  areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
	  areaH.addFunctionParameter("mal_element_holder_t *", "element", false);
	  areaH.addFunctionParameter("void *", "cursor", true);
	  areaH.closeFunctionPrototype();

	  // int <qfop>_<stage|error>_add_encoding_length_<index>(
	  //	mal_encoder_t *encoder, mal_element_holder_t *element,
	  //	void *cursor) {
	  areaC.openFunction("int", encodeFuncNameL, 3);
	  areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
	  areaC.addFunctionParameter("mal_element_holder_t *", "element", false);
	  areaC.addFunctionParameter("void *", "cursor", true);
	  areaC.openFunctionBody();

	  //	int rc = 0;
	  //	switch (encoder->encoding_format_code) {
	  //	case <FORMAT>_FORMAT_CODE: {
	  // format specific code
	  //		break;
	  //	}
	  //	default:
	  //		rc = -1;
	  //	}
	  //	return rc;
	  // }
	  areaC.addStatement("int rc = 0;");
	  areaC.addStatement("switch (encoder->encoding_format_code)");
	  areaC.openBlock();
	  if (generateTransportMalbinary || generateTransportMalsplitbinary)
	  {
		  if (generateTransportMalbinary)
		  {
			  areaC.addStatement("case " + transportMalbinary.toUpperCase() + "_FORMAT_CODE:");
		  }
		  if (generateTransportMalsplitbinary)
		  {
			  areaC.addStatement("case " + transportMalsplitbinary.toUpperCase() + "_FORMAT_CODE:");
		  }
		  areaC.openBlock();
		  final String isPresent = "(element != NULL && element->presence_flag)";
		  addMalbinaryEncodingLengthPresenceFlag(areaC, isPresent);
		  areaC.addStatement("if (" + isPresent + ")");
		  areaC.openBlock();

		  addMalbinaryEncodingLengthElement(areaC, areaContext, "element");

		  areaC.closeBlock();
		  areaC.addStatement("break;");
		  areaC.closeBlock();
	  }
	  areaC.addStatement("default:");
	  areaC.addStatement("rc = -1;");
	  areaC.closeBlock();
	  areaC.addStatement("return rc;");
	  areaC.closeFunctionBody();
  }

  private void addInteractionParamEncodingEncodeFunction(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
  	CFileWriter areaH = opStageContext.opContext.serviceContext.areaContext.areaHContent;
  	CFileWriter areaC = opStageContext.opContext.serviceContext.areaContext.areaC;
		areaC.addNewLine();
    StringBuilder buf = new StringBuilder();
    buf.append(opStageContext.qfOpStageNameL);
    buf.append("_encode");
    if (! paramDetails.isError)
    {
    	buf.append("_").append(paramDetails.paramIndex);
    }
    if (paramDetails.isPolymorph || paramDetails.isError)
    {
    	buf.append("_").append(paramDetails.qfTypeNameL);
      if (paramDetails.isList)
      {
      	buf.append("_list");
      }
    }
    String encodeFuncNameL = buf.toString();

  	if (paramDetails.isAbstractAttribute && !paramDetails.isList)
  	{
  		// the !isList test is not necessary as the type should not be polymorphic at this point
  		// int <qfop>_<stage|error>_encode_<index>(
  		//	char *cursor,
  		//	mal_encoder_t *encoder, bool presence_flag, unsigned char attribute_tag,
  		//	union mal_attribute_t element);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 5);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter("bool", "presence_flag", false);
  		areaH.addFunctionParameter("unsigned char", "attribute_tag", false);
  		areaH.addFunctionParameter("union mal_attribute_t", "element", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_encode[_<index>](
  	  //	void *cursor,
  	  //	mal_encoder_t *encoder, bool presence_flag, unsigned char attribute_tag,
  	  //	union mal_attribute_t element) {
  		areaC.openFunction("int", encodeFuncNameL, 5);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter("bool", "presence_flag", false);
  		areaC.addFunctionParameter("unsigned char", "attribute_tag", false);
  		areaC.addFunctionParameter("union mal_attribute_t", "element", true);
  		areaC.openFunctionBody();
  	}
  	else if (paramDetails.isAbstract)
  	{
  		// should never happen, as the function should be called with all possible concrete types
  		throw new IllegalArgumentException("addInteractionParamEncodingEncodeFunction called with abstract type for operation " + opStageContext.qfOpStageNameL + " and parameter " + paramDetails.paramName);
  	}
  	else if (paramDetails.isPresenceFlag)
  	{
  		// int <qfop>_<stage|error>_encode_<index>[_<qftype>](
  	  //	void *cursor, 
  	  //	mal_encoder_t *encoder, bool presence_flag, <qftype>_t element);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 4);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter("bool", "presence_flag", false);
  		areaH.addFunctionParameter(paramDetails.paramType, "element", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_encode[_<index>][_<qftype>](
  	  //	void *cursor,
  	  //	mal_encoder_t *encoder, bool presence_flag, <qftype>_t element) {
  		areaC.openFunction("int", encodeFuncNameL, 4);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter("bool", "presence_flag", false);
  		areaC.addFunctionParameter(paramDetails.paramType, "element", true);
  		areaC.openFunctionBody();
  	}
  	else
  	{
  		// int <qfop>_<stage|error>_encode_<index>[_<qftype>](
  	  //	void *cursor, 
  		//	mal_encoder_t *encoder, <qftype>_[list_]t *element);
  		areaH.openFunctionPrototype("int", encodeFuncNameL, 3);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaH.addFunctionParameter(paramDetails.paramType, "element", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_encode[_<index>][_<qftype>](
  	  //	void *cursor, 
  	  //	mal_encoder_t *encoder, <qftype>_[list_]t *element) {
  		areaC.openFunction("int", encodeFuncNameL, 3);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
  		areaC.addFunctionParameter(paramDetails.paramType, "element", true);
  		areaC.openFunctionBody();
  	}

		//	int rc = 0;
		//	switch (encoder->encoding_format_code) {
  	//	case <FORMAT>_FORMAT_CODE: {
  	// format specific code
  	//		break;
  	//	}
  	//	default:
    //		rc = -1;
  	//	}
  	//	return rc;
  	// }
  	areaC.addStatement("int rc = 0;");
  	areaC.addStatement("switch (encoder->encoding_format_code)");
  	areaC.openBlock();
  	if (generateTransportMalbinary || generateTransportMalsplitbinary)
  	{
    	if (generateTransportMalbinary)
    	{
    		areaC.addStatement("case " + transportMalbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	if (generateTransportMalsplitbinary)
    	{
    		areaC.addStatement("case " + transportMalsplitbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	areaC.openBlock();
    	// TODO: null parameter in a PubSub operation, cf above
    	if (! paramDetails.isPresenceFlag)
    	{
    	  //	bool presence_flag = (element != NULL);
    		areaC.addStatement("bool presence_flag = (element != NULL);");
    	}
    	addMalbinaryEncodingEncodePresenceFlag(areaC, "presence_flag");
    	areaC.addStatement("if (presence_flag)");
    	areaC.openBlock();
    	if (paramDetails.isAbstractAttribute)
    	{
    		addMalbinaryEncodingEncodeAbstractAttribute(areaC, "attribute_tag", "element");
    	}
    	else
    	{
    		if (paramDetails.isPolymorph)
    		{
    			String shortForm = paramDetails.qfTypeNameL.toUpperCase() +
    					(paramDetails.isList ? "_LIST_SHORT_FORM" : "_SHORT_FORM");
    			addMalbinaryEncodingEncodeShortForm(areaC, shortForm);
    		}
    		if (paramDetails.isAttribute)
    		{
    			addMalbinaryEncodingEncodeAttribute(areaC, "element", paramDetails.type.getName().toLowerCase());
    		}
    		else if (paramDetails.isComposite)
    		{
    			addMalbinaryEncodingEncodeComposite(areaC, "element", paramDetails.qfTypeNameL);
    		}
    		else if (paramDetails.isList)
    		{
    			addMalbinaryEncodingEncodeList(areaC, "element", paramDetails.qfTypeNameL);
    		}
    		else if (paramDetails.isEnumeration)
    		{
    			MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(paramDetails.type);
    			addMalbinaryEncodingEncodeEnumeration(areaC, "element", enumMBSize);
    		}
    		else
    		{
    			throw new IllegalStateException("unexpected type for operation " + opStageContext.opContext.qfOpNameL + " parameter " + paramDetails.paramName + ": " + paramDetails.qfTypeNameL);
    		}
    	}
    	areaC.closeBlock();
    	areaC.addStatement("break;");
    	areaC.closeBlock();
  	}
  	areaC.addStatement("default:");
  	areaC.addStatement("rc = -1;");
  	areaC.closeBlock();
  	areaC.addStatement("return rc;");
  	areaC.closeFunctionBody();
  }

  private void addInteractionParamEncodingDecodeFunction(OpStageContext opStageContext, ParameterDetails paramDetails) throws IOException
  {
  	CFileWriter areaH = opStageContext.opContext.serviceContext.areaContext.areaHContent;
  	CFileWriter areaC = opStageContext.opContext.serviceContext.areaContext.areaC;
		areaC.addNewLine();
    StringBuilder buf = new StringBuilder();
    buf.append(opStageContext.qfOpStageNameL);
    buf.append("_decode");
    if (! paramDetails.isError)
    {
    	buf.append("_").append(paramDetails.paramIndex);
    }
    String decodeFuncNameL = buf.toString();
    
  	if (paramDetails.isAbstractAttribute && !paramDetails.isList)
  	{
  		// int <qfop>_<stage>_decode_[<index>](
  	  //	void *cursor,
  		//	mal_decoder_t *decoder, bool *presence_flag_res, 
  	  //	unsigned char *attribute_tag_res, union mal_attribute_t *element_res);
  		areaH.openFunctionPrototype("int", decodeFuncNameL, 5);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaH.addFunctionParameter("bool *", "presence_flag_res", false);
  		areaH.addFunctionParameter("unsigned char *", "attribute_tag_res", false);
  		areaH.addFunctionParameter("union mal_attribute_t *", "element_res", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage>_decode[_<index>](
  	  //	void *cursor,
  	  //	mal_decoder_t *decoder, bool *presence_flag_res, 
  	  //	unsigned char *attribute_tag_res, union mal_attribute_t *element_res) {
  		areaC.openFunction("int", decodeFuncNameL, 5);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaC.addFunctionParameter("bool *", "presence_flag_res", false);
  		areaC.addFunctionParameter("unsigned char *", "attribute_tag_res", false);
  		areaC.addFunctionParameter("union mal_attribute_t *", "element_res", true);
  		areaC.openFunctionBody();
  	}
  	else if (paramDetails.isAbstract)
  	{
  		// int <qfop>_<stage|error>_decode[_<index>](
  	  //	void *cursor,
  		//	mal_decoder_t *decoder, mal_element_holder_t *element_holder);
  		areaH.openFunctionPrototype("int", decodeFuncNameL, 3);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaH.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
  		areaH.closeFunctionPrototype();

  		// int <qfop>_<stage|error>_decode[_<index>](
  	  //	void *cursor,
  		//	mal_decoder_t *decoder, mal_element_holder_t *element_holder) {
  		areaC.openFunction("int", decodeFuncNameL, 3);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaC.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
  		areaC.openFunctionBody();
  	}
  	else if (paramDetails.isPresenceFlag)
  	{
  		// int <qfop>_<stage|error>_decode_[<index>](
  	  //	void *cursor,
  	  //	mal_decoder_t *decoder, bool *presence_flag_res, <qftype>_t *element_res);
  		areaH.openFunctionPrototype("int", decodeFuncNameL, 4);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaH.addFunctionParameter("bool *", "presence_flag_res", false);
  		areaH.addFunctionParameter(paramDetails.paramType + " *", "element_res", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_decode[_<index>](
  		//	void *cursor,
  	  //	mal_decoder_t *decoder, bool *presence_flag_res, <qftype>_t *element_res) {
  		areaC.openFunction("int", decodeFuncNameL, 4);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaC.addFunctionParameter("bool *", "presence_flag_res", false);
  		areaC.addFunctionParameter(paramDetails.paramType + " *", "element_res", true);
  		areaC.openFunctionBody();
  	}
  	else
  	{
  		// int <qfop>_<stage|error>_decode_[<index>](
  	  //	void *cursor,
  	  //	mal_decoder_t *decoder, <qftype>_[list_]t **element_res);
  		areaH.openFunctionPrototype("int", decodeFuncNameL, 3);
  		areaH.addFunctionParameter("void *", "cursor", false);
  		areaH.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaH.addFunctionParameter(paramDetails.paramType + "*", "element_res", true);
  		areaH.closeFunctionPrototype();
  		
  		// int <qfop>_<stage|error>_decode[_<index>](
  	  //	void *cursor,
  	  //	mal_decoder_t *decoder, <qftype>_[list_]t **element_res) {
  		areaC.openFunction("int", decodeFuncNameL, 3);
  		areaC.addFunctionParameter("void *", "cursor", false);
  		areaC.addFunctionParameter("mal_decoder_t *", "decoder", false);
  		areaC.addFunctionParameter(paramDetails.paramType + "*", "element_res", true);
  		areaC.openFunctionBody();
  	}
  	
		//	int rc = 0;
		//	switch (decoder->encoding_format_code) {
  	//	case <FORMAT>_FORMAT_CODE: {
  	// format specific code
  	//		break;
  	//	}
  	//	default:
    //		rc = -1;
  	//	}
  	//	return rc;
  	// }
  	areaC.addStatement("int rc = 0;");
  	areaC.addStatement("switch (decoder->encoding_format_code)");
  	areaC.openBlock();
  	if (generateTransportMalbinary || generateTransportMalsplitbinary)
  	{
  		boolean isPolymorphic = false;
    	if (generateTransportMalbinary)
    	{
    		areaC.addStatement("case " + transportMalbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	if (generateTransportMalsplitbinary)
    	{
    		areaC.addStatement("case " + transportMalsplitbinary.toUpperCase() + "_FORMAT_CODE:");
    	}
    	areaC.openBlock();
    	// use a local variable as the flag should not be set while the element has not been successfully decoded
    	areaC.addStatement("bool presence_flag;");
    	addMalbinaryEncodingDecodePresenceFlag(areaC, "presence_flag");
    	areaC.addStatement("if (presence_flag)");
    	areaC.openBlock();
    	if (paramDetails.isAbstract)
    	{
    		if (paramDetails.isAbstractAttribute && !paramDetails.isList)
      	{
      		addMalbinaryEncodingDecodeAbstractAttribute(areaC, "*attribute_tag_res", "*element_res");
      	}
    		else
    		{
    			isPolymorphic = true;
    			// use a generic decoding function
    			addMalbinaryEncodingDecodeElement(areaC, opStageContext.opContext.serviceContext.areaContext, "element_holder");
    		}
    	}
    	else if (paramDetails.isAttribute)
    	{
    		addMalbinaryEncodingDecodeAttribute(areaC, "*element_res", paramDetails.type.getName().toLowerCase());
    	}
    	else if (paramDetails.isComposite)
    	{
    		addMalbinaryEncodingDecodeComposite(areaC, "*element_res", paramDetails.qfTypeNameL, false);
    	}
    	else if (paramDetails.isList)
    	{
    		addMalbinaryEncodingDecodeList(areaC, "*element_res", paramDetails.qfTypeNameL, false);
    	}
    	else if (paramDetails.isEnumeration)
    	{
    		areaC.addStatement("int enumerated_value;");
    		MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(paramDetails.type);
    		addMalbinaryEncodingDecodeEnumeration(areaC, "*element_res", paramDetails.qfTypeNameL, enumMBSize);
    	}
    	else
    	{
    		throw new IllegalStateException("unexpected type for operation " + opStageContext.opContext.qfOpNameL + " parameter " + paramDetails.paramName + ": " + paramDetails.qfTypeNameL);
    	}
    	if (isPolymorphic)
    	{
      	areaC.closeBlock();
    		areaC.addStatement("mal_element_holder_set_presence_flag(element_holder, presence_flag);");
    	}
    	else if (paramDetails.isPresenceFlag)
    	{
    		// (*presence_flag_res) = presence_flag;
    		areaC.addStatement("(*presence_flag_res) = presence_flag;");
      	areaC.closeBlock();
    	}
    	else
    	{
      	areaC.closeBlock();
    		areaC.addStatement("else");
    		areaC.openBlock();
    		areaC.addStatement("*element_res = NULL;");
    		areaC.closeBlock();
    	}
    	areaC.addStatement("break;");
    	areaC.closeBlock();
  	}
  	areaC.addStatement("default:");
  	areaC.addStatement("rc = -1;");
  	areaC.closeBlock();
  	areaC.addStatement("return rc;");
  	areaC.closeFunctionBody();
  }

  private void addGenericParamDecodingFunctions(AreaContext areaContext) throws IOException
  {
  	if (generateTransportMalbinary)
  	{
  		addMalbinaryEncodingLengthElementFunction(areaContext);
  		addMalbinaryEncodingEncodeElementFunction(areaContext);
  		addMalbinaryEncodingDecodeElementFunction(areaContext);
  	}
  }

  private void addMalbinaryEncodingLengthElement(CFileWriter code, AreaContext areaContext, String varName) throws IOException
  {
	  //        rc = <area>_malbinary_add_mal_element_encoding_length(element, encoder, cursor);
	  //        if (rc < 0)
	  //          return rc;
	  code.addStatement("rc = " + areaContext.areaNameL + "_" + transportMalbinary + "_add_mal_element_encoding_length(encoder, " + varName + ", cursor);");
	  code.addStatement("if (rc < 0)", 1);
	  code.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingLengthElementFunction(AreaContext areaContext) throws IOException
  {
		// int <area>_malbinary_add_mal_element_encoding_length(
	  	//	mal_encoder_t *encoder, void *cursor,
		//	mal_element_holder_t *element_holder);
		areaContext.areaH.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_add_mal_element_encoding_length", 3);
		areaContext.areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		areaContext.areaH.addFunctionParameter("mal_element_holder_t *", "element_holder", false);
		areaContext.areaH.addFunctionParameter("void *", "cursor", true);
		areaContext.areaH.closeFunctionPrototype();

		// int <area>_malbinary_add_mal_element_encoding_length(
		//	mal_encoder_t *encoder, void *cursor,
		//	mal_element_holder_t *element_holder) {
		areaContext.areaC.addNewLine();
		areaContext.areaC.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_add_mal_element_encoding_length", 3);
		areaContext.areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		areaContext.areaC.addFunctionParameter("mal_element_holder_t *", "element_holder", false);
		areaContext.areaC.addFunctionParameter("void *", "cursor", true);
		areaContext.areaC.openFunctionBody();

		//	int rc = 0;
		areaContext.areaC.addStatement("int rc = 0;");
		
		areaContext.areaC.addSingleLineComment("Encoding abstract mal_element require encoding short form");
		// rc = mal_encoder_add_short_form_encoding_length(encoder, element->short_form, cursor);
		// if (rc < 0)
		//   return rc;
		areaContext.areaC.addStatement("rc = mal_encoder_add_short_form_encoding_length(encoder, element_holder->short_form, cursor);");
		areaContext.areaC.addStatement("if (rc < 0)", 1);
		areaContext.areaC.addStatement("return rc;", -1);

		// type specific decoding depending on the short form
		Set <TypeKey> keys = allTypesMap.keySet();
		boolean first = true;
		for (TypeKey key : keys) {
			if (abstractTypesSet.contains(key))
			{
				// ignore abstract types
				continue;
			}
			// [else] if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_SHORT_FORM) {
			TypeReference ptype = key.getTypeReference(false);
			StringBuilder buf = new StringBuilder();
			buf.append(ptype.getArea().toLowerCase());
			buf.append("_");
			if (ptype.getService() != null)
			{
				buf.append(ptype.getService().toLowerCase());
				buf.append("_");
			}
			buf.append(ptype.getName().toLowerCase());
			String qfTypeNameL = buf.toString();
			String qfTypeNameU = qfTypeNameL.toUpperCase();

			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_SHORT_FORM)");
			areaContext.areaC.openBlock();
			if (isAttributeType(ptype))
			{
				String varType = ptype.getName().toLowerCase();
				addMalbinaryEncodingLengthAttribute(areaContext.areaC, "element_holder->value." + varType + "_value", varType);
			}
			else if (isComposite(ptype))
			{
				addMalbinaryEncodingLengthComposite(areaContext.areaC, "element_holder->value.composite_value", qfTypeNameL);
			}
			else if (isEnum(ptype))
			{
				MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(ptype);
				addMalbinaryEncodingLengthEnumeration(areaContext.areaC, "element_holder->value.enumerated_value", enumMBSize);
			}
			else
			{
				throw new IllegalStateException("addMalbinaryEncodingLengthElement: unexpected type " + key);
			}
			areaContext.areaC.closeBlock();
			first = false;
			
			// else if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_LIST_SHORT_FORM) {
			// 	<length element>
			// }
			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_LIST_SHORT_FORM)");
			areaContext.areaC.openBlock();
			addMalbinaryEncodingLengthList(areaContext.areaC, "element_holder->value.list_value", qfTypeNameL);
			areaContext.areaC.closeBlock();
			
			areaContext.reqAreas.add(ptype.getArea());
		}

		// else return -1;
		areaContext.areaC.addStatement("else", 1);
		areaContext.areaC.addStatement("return -1;", -1);
				
		areaContext.areaC.addStatement("return rc;");
		areaContext.areaC.closeFunctionBody();
  }

  private void addMalbinaryEncodingEncodeElement(CFileWriter code, AreaContext areaContext, String varName) throws IOException
  {
	  //        rc = <area>_malbinary_encode_mal_element(encoder, cursor, element);
	  //        if (rc < 0)
	  //          return rc;
	  code.addStatement("rc = " + areaContext.areaNameL + "_" + transportMalbinary + "_encode_mal_element(encoder, cursor, " + varName + ");");
	  code.addStatement("if (rc < 0)", 1);
	  code.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingEncodeElementFunction(AreaContext areaContext) throws IOException
  {
		// int <area>_malbinary_encode_mal_element(
	  	//	mal_encoder_t *encoder, void *cursor,
		//	mal_element_holder_t *element_holder);
		areaContext.areaH.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_encode_mal_element", 3);
		areaContext.areaH.addFunctionParameter("mal_encoder_t *", "encoder", false);
		areaContext.areaH.addFunctionParameter("void *", "cursor", false);
		areaContext.areaH.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
		areaContext.areaH.closeFunctionPrototype();

		// int <area>_malbinary_encode_mal_element(
		//	mal_encoder_t *encoder, void *cursor,
		//	mal_element_holder_t *element_holder) {
		areaContext.areaC.addNewLine();
		areaContext.areaC.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_encode_mal_element", 3);
		areaContext.areaC.addFunctionParameter("mal_encoder_t *", "encoder", false);
		areaContext.areaC.addFunctionParameter("void *", "cursor", false);
		areaContext.areaC.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
		areaContext.areaC.openFunctionBody();

		//	int rc = 0;
		areaContext.areaC.addStatement("int rc = 0;");

		areaContext.areaC.addSingleLineComment("Encoding abstract mal_element require encoding short form");
		//  rc = mal_encoder_encode_short_form(encoder, cursor, element_holder->short_form);
		//	if (rc < 0) return rc;
		areaContext.areaC.addStatement("rc = mal_encoder_encode_short_form(encoder, cursor, element_holder->short_form);");
		areaContext.areaC.addStatement("if (rc < 0)", 1);
		areaContext.areaC.addStatement("return rc;", -1);
		
		// type specific decoding depending on the short form
		Set <TypeKey> keys = allTypesMap.keySet();
		boolean first = true;
		for (TypeKey key : keys) {
			if (abstractTypesSet.contains(key))
			{
				// ignore abstract types
				continue;
			}
			// [else] if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_SHORT_FORM) {
			TypeReference ptype = key.getTypeReference(false);
			StringBuilder buf = new StringBuilder();
			buf.append(ptype.getArea().toLowerCase());
			buf.append("_");
			if (ptype.getService() != null)
			{
				buf.append(ptype.getService().toLowerCase());
				buf.append("_");
			}
			buf.append(ptype.getName().toLowerCase());
			String qfTypeNameL = buf.toString();
			String qfTypeNameU = qfTypeNameL.toUpperCase();

			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_SHORT_FORM)");
			areaContext.areaC.openBlock();
			if (isAttributeType(ptype))
			{
				String varType = ptype.getName().toLowerCase();
				addMalbinaryEncodingEncodeAttribute(areaContext.areaC, "element_holder->value." + varType + "_value", varType);
			}
			else if (isComposite(ptype))
			{
				addMalbinaryEncodingEncodeComposite(areaContext.areaC, "element_holder->value.composite_value", qfTypeNameL);
			}
			else if (isEnum(ptype))
			{
				MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(ptype);
				addMalbinaryEncodingEncodeEnumeration(areaContext.areaC, "element_holder->value.enumerated_value", enumMBSize);
			}
			else
			{
				throw new IllegalStateException("addMalbinaryEncodingEncodeElement: unexpected type " + key);
			}
			areaContext.areaC.closeBlock();
			first = false;
			
			// else if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_LIST_SHORT_FORM) {
			// 	<encode element>
			// }
			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_LIST_SHORT_FORM)");
			areaContext.areaC.openBlock();
			addMalbinaryEncodingEncodeList(areaContext.areaC, "element_holder->value.list_value", qfTypeNameL);
			areaContext.areaC.closeBlock();
			
			areaContext.reqAreas.add(ptype.getArea());
		}

		// else return -1;
		areaContext.areaC.addStatement("else", 1);
		areaContext.areaC.addStatement("return -1;", -1);
				
		areaContext.areaC.addStatement("return rc;");
		areaContext.areaC.closeFunctionBody();
  }

  private void addMalbinaryEncodingDecodeElement(CFileWriter code, AreaContext areaContext, String varName) throws IOException
  {
	  //        rc = <area>_malbinary_decode_mal_element(decoder, cursor, element);
	  //        if (rc < 0)
	  //          return rc;
	  code.addStatement("rc = " + areaContext.areaNameL + "_" + transportMalbinary + "_decode_mal_element(decoder, cursor, "+ varName + ");");
	  code.addStatement("if (rc < 0)", 1);
	  code.addStatement("return rc;", -1);
  }

  private void addMalbinaryEncodingDecodeElementFunction(AreaContext areaContext) throws IOException
  {
		// int <area>_malbinary_decode_mal_element(
  	//	mal_decoder_t *decoder, void *cursor,
		//	mal_element_holder_t *element_holder);
		areaContext.areaH.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_decode_mal_element", 3);
		areaContext.areaH.addFunctionParameter("mal_decoder_t *", "decoder", false);
		areaContext.areaH.addFunctionParameter("void *", "cursor", false);
		areaContext.areaH.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
		areaContext.areaH.closeFunctionPrototype();

		// int <area>_malbinary_decode_mal_element(
  	//	mal_decoder_t *decoder, void *cursor,
		//	mal_element_holder_t *element_holder) {
		areaContext.areaC.addNewLine();
		areaContext.areaC.openFunctionPrototype("int", areaContext.areaNameL + "_" + transportMalbinary + "_decode_mal_element", 3);
		areaContext.areaC.addFunctionParameter("mal_decoder_t *", "decoder", false);
		areaContext.areaC.addFunctionParameter("void *", "cursor", false);
		areaContext.areaC.addFunctionParameter("mal_element_holder_t *", "element_holder", true);
		areaContext.areaC.openFunctionBody();
		
       // int enumerated_value = 0;
		areaContext.areaC.addStatement("int enumerated_value = 0;");

		//	int rc = 0;
		//	rc = mal_decoder_decode_short_form(decoder, cursor, &element_holder->short_form);
		//	if (rc < 0) return rc;
		areaContext.areaC.addStatement("int rc = 0;");
		areaContext.areaC.addStatement("rc = mal_decoder_decode_short_form(decoder, cursor, &element_holder->short_form);");
		areaContext.areaC.addStatement("if (rc < 0)", 1);
		areaContext.areaC.addStatement("return rc;", -1);
		
		// type specific decoding depending on the short form
		Set <TypeKey> keys = allTypesMap.keySet();
		boolean first = true;
		for (TypeKey key : keys) {
			if (abstractTypesSet.contains(key))
			{
				// ignore abstract types
				continue;
			}
			// [else] if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_SHORT_FORM) {
			TypeReference ptype = key.getTypeReference(false);
			StringBuilder buf = new StringBuilder();
			buf.append(ptype.getArea().toLowerCase());
			buf.append("_");
			if (ptype.getService() != null)
			{
				buf.append(ptype.getService().toLowerCase());
				buf.append("_");
			}
			buf.append(ptype.getName().toLowerCase());
			String qfTypeNameL = buf.toString();
			String qfTypeNameU = qfTypeNameL.toUpperCase();

			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_SHORT_FORM)");
			areaContext.areaC.openBlock();
			if (isAttributeType(ptype))
			{
				String varType = ptype.getName().toLowerCase();
				addMalbinaryEncodingDecodeAttribute(areaContext.areaC, "element_holder->value." + varType + "_value", varType);
			}
			else if (isComposite(ptype))
			{
				addMalbinaryEncodingDecodeComposite(areaContext.areaC, "element_holder->value.composite_value", qfTypeNameL, true);
			}
			else if (isEnum(ptype))
			{
				MalbinaryEnumSize enumMBSize = getEnumTypeMBSize(ptype);
				addMalbinaryEncodingDecodeEnumeration(areaContext.areaC, "element_holder->value.enumerated_value", qfTypeNameL, enumMBSize);
			}
			else
			{
				throw new IllegalStateException("addMalbinaryEncodingDecodeElement: unexpected type " + key);
			}
			areaContext.areaC.closeBlock();
			first = false;
			
			// else if (element_holder->short_form == <AREA>_[<SERVICE>_]<TYPE>_LIST_SHORT_FORM) {
			// 	<decode element>
			// }
			areaContext.areaC.addStatement((first ? "" : "else ") + "if (element_holder->short_form == " + qfTypeNameU + "_LIST_SHORT_FORM)");
			areaContext.areaC.openBlock();
			addMalbinaryEncodingDecodeList(areaContext.areaC, "element_holder->value.list_value", qfTypeNameL, true);
			areaContext.areaC.closeBlock();
			
			areaContext.reqAreas.add(ptype.getArea());
		}

		// else return -1;
		areaContext.areaC.addStatement("else", 1);
		areaContext.areaC.addStatement("return -1;", -1);
				
		areaContext.areaC.addStatement("return rc;");
		areaContext.areaC.closeFunctionBody();
  }

  private void addRegisterFunction(OperationContext opContext, String opStage) throws IOException
  {
  	OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, null);
  	addRegisterFunction(opStageCtxt);
  }
  
  private void addRegisterFunction(OpStageContext opStageCtxt) throws IOException
  {
  	// declare the function in the <area>.h file and define it in the <area>.c file
  	CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
  	CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
  	areaC.addNewLine();
  	StringBuilder buf;
  	
//	  int <area>_<service>_<operation>_register(
//	  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri);
  	areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, 3);
  	areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
  	areaH.addFunctionParameter("mal_message_t *", "message", false);
  	areaH.addFunctionParameter("mal_uri_t *", "broker_uri", true);
   	areaH.closeFunctionPrototype();

//   	int <area>_<service>_<operation>_register(
//   		  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri) {
//   		  int rc = 0;
//   	Affectation des champs liés à l'opération :
//   		  mal_message_init(message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
//   		    <AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_PUBSUB, MAL_IP_STAGE_PUBSUB_REGISTER);
//   	Envoi du message :
//   		  rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);
//   		  return rc;
//   		}
  	areaC.openFunction("int", opStageCtxt.qfOpStageNameL, 3);
  	areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
  	areaC.addFunctionParameter("mal_message_t *", "message", false);
  	areaC.addFunctionParameter("mal_uri_t *", "broker_uri", true);
  	areaC.openFunctionBody();
  	areaC.addStatement("int rc = 0;");
  	String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
  	String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
    buf = new StringBuilder();
    buf.append("mal_message_init(message, ");
    buf.append(areaNameU).append("_AREA_NUMBER, ");
    buf.append(areaNameU).append("_AREA_VERSION, ");
    buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
    buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
    buf.append("MAL_INTERACTIONTYPE_PUBSUB, ");
    buf.append("MAL_IP_STAGE_PUBSUB_REGISTER");
    buf.append(");");
    areaC.addStatement(buf.toString());
    areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);");
    areaC.addStatement("return rc;");
  	areaC.closeFunctionBody();
  }
  
  private void generatePubSubEncodingRelatedParameters(OperationContext opContext, String opStage, List<TypeInfo> parameters) throws IOException
  {
	  OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, parameters);
	  generatePubSubEncodingRelatedParameters(opStageCtxt);
  }
  
  //generate all PubSub encoding code related to the parameters
  private void generatePubSubEncodingRelatedParameters(OpStageContext opStageCtxt) throws IOException {
	  // generate all encoding code related to the parameters
	  List<TypeInfo> parameters = opStageCtxt.parameters;
	  if (parameters != null)
	  {
		  int paramsNumber = parameters.size();
		  int paramIndex = 0;
		  for (TypeInfo param : parameters)
		  {
			  TypeReference paramType = param.getSourceType();

			  // keep the parameter area name for future include
			  opStageCtxt.opContext.serviceContext.areaContext.reqAreas.add(paramType.getArea());

			  ParameterDetails paramDetails = new ParameterDetails();
			  paramDetails.isPubSub = true;
			  paramDetails.type = paramType;
			  paramDetails.paramName = param.getFieldName();
			  paramDetails.paramIndex = paramIndex;
			  if (paramIndex == paramsNumber - 1)
			  {
				  paramDetails.isLast = true;
			  }

			  // parameters in a publish operation require a specific transformation
			  // if a parameter is declared as type T in the specification, it must be transformed into List<T> at execution.
			  // As a result an XML list type is illegal.
			  if (paramType.isList())
			  {
			  	// this should never occur
			  	throw new IllegalArgumentException("illegal list type " + paramType.toString() + " for parameter " + param.getFieldName() + " in operation " + opStageCtxt.qfOpStageNameL);
			  }
			  paramType.setList(true);
			  
			  if (isAbstract(paramType))
			  {
				  paramDetails.isAbstract = true;
				  // check for abstract Attribute type
				  if (StdStrings.MAL.equals(paramType.getArea()) &&
						  StdStrings.ATTRIBUTE.equals(paramType.getName()))
				  {
					  paramDetails.isAbstractAttribute = true;
				  }
				  if (! paramDetails.isLast)
				  {
				  	throw new IllegalArgumentException("non Attribute abstract type for a non terminal parameter " + paramDetails.paramName + " in function " + opStageCtxt.qfOpStageNameL);
				  }
    			try {
    				addInteractionAbstractParamXcodingFunctions(opStageCtxt, paramDetails, paramType);
    			} catch (IllegalArgumentException exc) {
    				throw new IllegalArgumentException("for parameter " + param.getFieldName() + " in operation " + opStageCtxt.qfOpStageNameL, exc);
    			}
			  }
			  else
			  {
    			try {
    				fillInteractionParamDetails(paramDetails, paramType);
        		addInteractionParamXcodingFunctions(opStageCtxt, paramDetails);
    			} catch (IllegalArgumentException exc) {
    				throw new IllegalArgumentException("for parameter " + param.getFieldName() + " in operation " + opStageCtxt.qfOpStageNameL, exc);
    			}
			  }

			  paramIndex ++;
		  }
	  }
  }
  
  private void addPublishRegisterFunction(OperationContext opContext, String opStage) throws IOException
  {
	  OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, null);
	  addPublishRegisterFunction(opStageCtxt);
  }

  private void addPublishRegisterFunction(OpStageContext opStageCtxt) throws IOException
  {
	  // declare the function in the <area>.h file and define it in the <area>.c file
	  CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
	  CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
	  areaC.addNewLine();
	  StringBuilder buf;

	  // int <area>_<service>_<operation>_publish_register(
	  // mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri); 
	  areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, 3);
	  areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaH.addFunctionParameter("mal_message_t *", "message", false);
	  areaH.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaH.closeFunctionPrototype();

	  //	   	int <area>_<service>_<operation>_publish_register(
	  //	   		  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri) {
	  //	   		  int rc = 0;
	  //	    Affectation des champs liés à l'opération :
	  //	   		  mal_message_init(message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
	  //	   		    <AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_PUBSUB, MAL_IP_STAGE_PUBSUB_PUBLISH_REGISTER);
	  //	   	Envoi du message :
	  //	   		  rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);
	  //	   		  return rc;
	  //	   		}
	  areaC.openFunction("int", opStageCtxt.qfOpStageNameL, 3);
	  areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaC.addFunctionParameter("mal_message_t *", "message", false);
	  areaC.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaC.openFunctionBody();
	  areaC.addStatement("int rc = 0;");
	  String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
	  String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
	  buf = new StringBuilder();
	  buf.append("mal_message_init(message, ");
	  buf.append(areaNameU).append("_AREA_NUMBER, ");
	  buf.append(areaNameU).append("_AREA_VERSION, ");
	  buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
	  buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
	  buf.append("MAL_INTERACTIONTYPE_PUBSUB, ");
	  buf.append("MAL_IP_STAGE_PUBSUB_PUBLISH_REGISTER");
	  buf.append(");");
	  areaC.addStatement(buf.toString());
	  areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);");
	  areaC.addStatement("return rc;");
	  areaC.closeFunctionBody();
  }

  private void addPublishFunction(OperationContext opContext, String opStage, List<TypeInfo> parameters) throws IOException
  {
	  OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, parameters);
	  addPublishFunction(opStageCtxt);
  }

  private void addPublishFunction(OpStageContext opStageCtxt) throws IOException
  {
	  // declare the function in the <area>.h file and define it in the <area>.c file
	  CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
	  CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
	  areaC.addNewLine();
	  StringBuilder buf;

//	  int <area>_<service>_<operation>_publish(
//	  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri, long initial_publish_register_tid);
	  areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, 4);
	  areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaH.addFunctionParameter("mal_message_t *", "message", false);
	  areaH.addFunctionParameter("mal_uri_t *", "broker_uri", false);
	  areaH.addFunctionParameter("long", "initial_publish_register_tid", true);
	  areaH.closeFunctionPrototype();

//	  int <area>_<service>_<operation>_publish(
//			  mal_endpoint_t *endpoint, mal_message_t *message,
//			  mal_uri_t *broker_uri, long initial_publish_register_tid) {
//			  int rc = 0;
//		Affectation des champs liés à l'opération :
//			  mal_message_init(message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
//			    <AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_PUBSUB, MAL_IP_STAGE_PUBSUB_PUBLISH);
//		Affectation du 'Transaction Id' :
//			  mal_message_set_transaction_id(message, initial_publish_register_tid);
//		Envoi du message :
//			  rc = mal_endpoint_return_operation(endpoint, message, broker_uri, false);
//			  return rc;
//			}
	  areaC.openFunction("int", opStageCtxt.qfOpStageNameL, 4);
	  areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaC.addFunctionParameter("mal_message_t *", "message", false);
	  areaC.addFunctionParameter("mal_uri_t *", "broker_uri", false);
	  areaC.addFunctionParameter("long", "initial_publish_register_tid", true);
	  areaC.openFunctionBody();
	  areaC.addStatement("int rc = 0;");
	  String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
	  String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
	  buf = new StringBuilder();
	  //mal_message_init
	  buf.append("mal_message_init(message, ");
	  buf.append(areaNameU).append("_AREA_NUMBER, ");
	  buf.append(areaNameU).append("_AREA_VERSION, ");
	  buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
	  buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
	  buf.append("MAL_INTERACTIONTYPE_PUBSUB, ");
	  buf.append("MAL_IP_STAGE_PUBSUB_PUBLISH");
	  buf.append(");");
	  areaC.addStatement(buf.toString());
	  //mal_message_set_transaction_id
	  buf = new StringBuilder();
	  buf.append("mal_message_set_transaction_id(message, initial_publish_register_tid);");
	  areaC.addStatement(buf.toString());
	  areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, message, broker_uri, false);");
	  areaC.addStatement("return rc;");
	  areaC.closeFunctionBody();
  }

  
  private void addDeregisterFunction(OperationContext opContext, String opStage) throws IOException
  {
	  OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, null);
	  addDeregisterFunction(opStageCtxt);
  }

  private void addDeregisterFunction(OpStageContext opStageCtxt) throws IOException
  {
	  // declare the function in the <area>.h file and define it in the <area>.c file
	  CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
	  CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
	  areaC.addNewLine();
	  StringBuilder buf;

//	  int <area>_<service>_<operation>_deregister(
//	  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri); 
	  areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, 3);
	  areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaH.addFunctionParameter("mal_message_t *", "message", false);
	  areaH.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaH.closeFunctionPrototype();

//	  int <area>_<service>_<operation>_deregister(
//			  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri) {
//			  int rc = 0;
//		Affectation des champs liés à l'opération :
//			  mal_message_init(message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
//			    <AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_PUBSUB, MAL_IP_STAGE_PUBSUB_DEREGISTER);
//		Envoi du message :
//			  rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);
//			  return rc;
//			}
	  areaC.openFunction("int", opStageCtxt.qfOpStageNameL, 3);
	  areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaC.addFunctionParameter("mal_message_t *", "message", false);
	  areaC.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaC.openFunctionBody();
	  areaC.addStatement("int rc = 0;");
	  String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
	  String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
	  buf = new StringBuilder();
	  buf.append("mal_message_init(message, ");
	  buf.append(areaNameU).append("_AREA_NUMBER, ");
	  buf.append(areaNameU).append("_AREA_VERSION, ");
	  buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
	  buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
	  buf.append("MAL_INTERACTIONTYPE_PUBSUB, ");
	  buf.append("MAL_IP_STAGE_PUBSUB_DEREGISTER");
	  buf.append(");");
	  areaC.addStatement(buf.toString());
	  areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);");
	  areaC.addStatement("return rc;");
	  areaC.closeFunctionBody();
  }

  
  private void addPublishDeregisterFunction(OperationContext opContext, String opStage) throws IOException
  {
	  OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, null);
	  addPublishDeregisterFunction(opStageCtxt);
  }

  private void addPublishDeregisterFunction(OpStageContext opStageCtxt) throws IOException
  {
	  // declare the function in the <area>.h file and define it in the <area>.c file
	  CFileWriter areaH = opStageCtxt.opContext.serviceContext.areaContext.areaHContent;
	  CFileWriter areaC = opStageCtxt.opContext.serviceContext.areaContext.areaC;
	  areaC.addNewLine();
	  StringBuilder buf;

//	  int <area>_<service>_<operation>_publish_deregister(
//	  mal_endpoint_t *endpoint, mal_message_t *message, mal_uri_t *broker_uri); 
	  areaH.openFunctionPrototype("int", opStageCtxt.qfOpStageNameL, 3);
	  areaH.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaH.addFunctionParameter("mal_message_t *", "message", false);
	  areaH.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaH.closeFunctionPrototype();

//	  int <area>_<service>_<operation>_publish_deregister(mal_endpoint_t *endpoint, mal_message_t *message,
//			  mal_uri_t *broker_uri) {
//			  int rc = 0;
//	  Affectation des champs liés à l'opération :
//			  mal_message_init(message, <AREA>_AREA_NUMBER, <AREA>_AREA_VERSION, <AREA>_<SERVICE>_SERVICE_NUMBER,
//			    <AREA>_<SERVICE>_<OPERATION>_OPERATION_NUMBER, MAL_INTERACTIONTYPE_PUBSUB, MAL_IP_STAGE_PUBSUB_PUBLISH_DEREGISTER);
//	  Envoi du message :
//			  rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);
//			  return rc;
//			}
	  areaC.openFunction("int", opStageCtxt.qfOpStageNameL, 3);
	  areaC.addFunctionParameter("mal_endpoint_t *", "endpoint", false);
	  areaC.addFunctionParameter("mal_message_t *", "message", false);
	  areaC.addFunctionParameter("mal_uri_t *", "broker_uri", true);
	  areaC.openFunctionBody();
	  areaC.addStatement("int rc = 0;");
	  String areaNameU = opStageCtxt.opContext.serviceContext.areaContext.areaNameL.toUpperCase();
	  String serviceNameU = opStageCtxt.opContext.serviceContext.serviceNameL.toUpperCase();
	  buf = new StringBuilder();
	  buf.append("mal_message_init(message, ");
	  buf.append(areaNameU).append("_AREA_NUMBER, ");
	  buf.append(areaNameU).append("_AREA_VERSION, ");
	  buf.append(areaNameU).append("_").append(serviceNameU).append("_SERVICE_NUMBER, ");
	  buf.append(opStageCtxt.opContext.qfOpNameL.toUpperCase()).append("_OPERATION_NUMBER, ");
	  buf.append("MAL_INTERACTIONTYPE_PUBSUB, ");
	  buf.append("MAL_IP_STAGE_PUBSUB_PUBLISH_DEREGISTER");
	  buf.append(");");
	  areaC.addStatement(buf.toString());
	  areaC.addStatement("rc = mal_endpoint_init_operation(endpoint, message, broker_uri, true);");
	  areaC.addStatement("return rc;");
	  areaC.closeFunctionBody();
  }

  private void generateZproject(File destFolder) throws IOException {
  	// generate the project.xml file
		PrintStream out;
		out = new PrintStream(new File(destFolder, "project.xml"));
		
		out.println("<project");
		out.println("    name = \"" + zprojectName + "\"");
		out.println("    description = \"Auto generated zproject project file\"");
		out.println("    script = \"zproject.gsl\"");
		out.println("    email = \"\"");
		out.println("    >");
		out.println();
		out.println("    <include filename = \"license.xml\" />");
		out.println("    <version major = \"1\" minor = \"0\" patch = \"0\" />");
		out.println();
		out.println("    <use project = \"malattributes\" />");
		out.println("    <use project = \"malbinary\" />");
		out.println("    <use project = \"mal\" />");
		out.println();
		// list the zproject classes
		for (String className : zclasses) {
			out.println("    <class name = \"" + className + "\" />");
		}
		out.println();
    out.println("</project>");
    
    // generate the <project>.h file
    File hFolder = new File(destFolder,"include");
    AreaHWriter hout = new AreaHWriter(hFolder, zprojectName);
    hout.openDefine();
		// list the zproject areas
		for (String areaName : zareas) {
			hout.addInclude(areaName + ".h");
		}
    hout.closeDefine();
    hout.flush();
    hout.close();
	}

  public static long getAbsoluteShortForm(int area, int service, int version, int type) throws IOException
  {
  	final int TYPE_SHORT_FORM_MAX = 0x007FFFFF;
  	final int TYPE_SHORT_FORM_MIN = -TYPE_SHORT_FORM_MAX;
  	
  	if (type < TYPE_SHORT_FORM_MIN ||
  			type > TYPE_SHORT_FORM_MAX) {
  		throw new IllegalArgumentException("Invalid type short form: " + type);
  	}
  	long res = type & 0xFFFFFF;
  	res |= (version & 0xFF) << 24;
  	res |= (service & 0xFFFFL) << 32;
  	res |= (area & 0xFFFFL) << 48;
  	return res;
  }

  public enum MalbinaryEnumSize {
  	MB_SMALL ("small", "MALBINARY_SMALL_ENUM_SIZE"),
  	MB_MEDIUM ("medium", "MALBINARY_MEDIUM_ENUM_SIZE"),
  	MB_LARGE ("large", "MALBINARY_LARGE_ENUM_SIZE");
  	
  	private final String cgenPrefix;
  	private final String cgenSvalue;
  	MalbinaryEnumSize(String cgenPrefix, String cgenSvalue)
  	{
  		this.cgenPrefix = cgenPrefix;
  		this.cgenSvalue = cgenSvalue;
  	}
  	public String getCgenPrefix()
  	{
  		return cgenPrefix;
  	}
  	public String getCgenSvalue()
  	{
  		return cgenSvalue;
  	}
  }
  
  /**
   * Utilitary class used to generate encoding code.
   * 
   * @author lacourte
   */
  class EncodingCode {
  	final StatementWriter lengthW = new StatementWriter();
  	final CFileWriter codeLength = new CFileWriter(lengthW);
  	final StatementWriter encodeW = new StatementWriter();
  	final CFileWriter codeEncode = new CFileWriter(encodeW);
  	final StatementWriter decodeW = new StatementWriter();
  	final CFileWriter codeDecode = new CFileWriter(decodeW);
  	public EncodingCode() throws IOException {}
  }

  /**
   * Context of code generation for an area.
   */
  private class AreaContext {
  	/** original area description */
  	final AreaType area;
  	/** folder for the area generated code files */
  	final File areaFolder;
    /** area name in lower case */
  	final String areaNameL;
  	/** writer to the <area>.h file. */
  	final AreaHWriter areaH;
  	/** buffer for the types part of the <area>.h file */
  	final StatementWriter areaHTypesW;
  	/** writer for the types part of the <area>.h file */
  	final CFileWriter areaHTypes;
  	/** buffer for the main content of the <area>.h file */
  	final StatementWriter areaHContentW;
  	/** writer for the main content of the <area>.h file */
  	final CFileWriter areaHContent;
  	/** writer to the <area>.c file. */
  	final AreaCWriter areaC;
    /** buffer for the include of the structure specific files */
  	final StatementWriter structureIncludesW;
    /** writer for the include of the structure specific files */
  	final CFileWriter structureIncludes;
  	/** set of required areas */
  	final Set<String> reqAreas;
  	
  	public AreaContext(File destinationFolder, AreaType area) throws IOException
  	{
  		this.area = area;
  		if (singleZproject) {
  			areaFolder = destinationFolder;
  		} else {
  			// create folder
  			areaFolder = StubUtils.createFolder(destinationFolder, area.getName());
  		}
      // area name in lower case
      areaNameL = area.getName().toLowerCase();
      // create the Writer structures
      File hFolder = new File(areaFolder,"include");
      hFolder.mkdirs();
      File cFolder = new File(areaFolder,"src");
      cFolder.mkdirs();
      areaH = new AreaHWriter(hFolder, areaNameL);
      areaHTypesW = new StatementWriter();
      areaHTypes = new CFileWriter(areaHTypesW);
      areaHContentW = new StatementWriter();
      areaHContent = new CFileWriter(areaHContentW);
      areaC = new AreaCWriter(cFolder, areaNameL);
    	structureIncludesW = new StatementWriter();
    	structureIncludes = new CFileWriter(structureIncludesW);
    	reqAreas = new LinkedHashSet<String>();
    	reqAreas.add(StdStrings.MAL);
  	}
  }

  /**
   * Context of code generation for a service.
   */
  private class ServiceContext {
  	/** area defining the service */
  	final AreaContext areaContext;
  	/** service summary */
  	final ServiceSummary summary;
  	/** folder for the service generated code files */
  	final File serviceFolder;
    /** service name in lower case */
  	final String serviceNameL;
  	
  	public ServiceContext(AreaContext areaContext, ServiceType service) throws IOException
  	{
  		this.areaContext = areaContext;
    	// all files are created in the same directory
      serviceFolder = areaContext.areaFolder;
      serviceNameL = service.getName().toLowerCase();
      // load service operation details
      summary = createOperationElementList(service);
  	}
  }

  /**
   * Context of code generation for an operation.
   */
  private class OperationContext {
  	/** service defining the operation */
  	final ServiceContext serviceContext;
  	/** operation summary */
  	final OperationSummary operation;
    /** fully qualified name of the operation in lower case */
  	final String qfOpNameL;
  	
  	public OperationContext(ServiceContext serviceContext, OperationSummary operation) throws IOException
  	{
  		this.serviceContext = serviceContext;
  		this.operation = operation;
      // build the fully qualified operation name for the C mapping (lower case)
      // <area>_[<service>_]<operation>
      StringBuilder buf = new StringBuilder();
      buf.append(serviceContext.areaContext.areaNameL);
      buf.append("_");
      buf.append(serviceContext.serviceNameL);
      buf.append("_");
      buf.append(operation.getName().toLowerCase());
      qfOpNameL = buf.toString();
  	}
  }

  /**
   * Context of code generation for the function associated to an operation stage.
   */
  private class OpStageContext {
  	/** operation context */
  	final OperationContext opContext;
  	/** stage */
  	final String opStage;
  	/** true if stage is an initial one */
  	final boolean isInit;
    /** fully qualified name of the operation stage function in lower case */
  	final String qfOpStageNameL;
  	/** list of the function parameters */
  	final List<TypeInfo> parameters;
  	
  	public OpStageContext(OperationContext opContext, String opStage, boolean isInit, List<TypeInfo> parameters) throws IOException
  	{
  		this.opContext = opContext;
  		this.opStage = opStage;
  		this.isInit = isInit;
      // build the fully qualified name of the operation stage function for the C mapping (lower case)
      // <area>_[<service>_]<operation>_<stage>
      qfOpStageNameL = opContext.qfOpNameL + "_" + opStage;
      this.parameters = parameters;
  	}
  }
  
  /**
   * Holds details about the composite, to be used in code generation.
   */
  private class CompositeContext {
  	final AreaContext areaContext;
  	final ServiceContext serviceContext;
  	final CompositeType composite;
  	final File folder;
  	final String mapCompNameL; 
  	final CompositeHWriter compositeH;
  	final CompositeCWriter compositeC;
    final EncodingCode encodingCode;
    final StatementWriter destroyCodeW;
    final CFileWriter destroyCode;
  	boolean holdsOptionalField = false;
  	boolean holdsEnumField = false;
  	
  	public CompositeContext(AreaContext areaContext, ServiceContext serviceContext, CompositeType composite, File folder) throws IOException
  	{
  		this.areaContext = areaContext;
  		this.serviceContext = serviceContext;
  		this.composite = composite;
  		this.folder = folder;
  		
      // build the fully qualified composite name for the C mapping (lower case)
      // <area>_[<service>_]<composite>
      StringBuilder buf = new StringBuilder();
      buf.append(areaContext.areaNameL);
      buf.append("_");
      if (serviceContext != null)
      {
      	buf.append(serviceContext.serviceNameL);
      	buf.append("_");
      }
      buf.append(composite.getName().toLowerCase());
      mapCompNameL = buf.toString();
      
      // create the writer structures
      File hFolder = new File(folder,"include");
      hFolder.mkdirs();
      File cFolder = new File(folder,"src");
      cFolder.mkdirs();
      compositeH = new CompositeHWriter(hFolder, mapCompNameL);
      compositeC = new CompositeCWriter(cFolder, mapCompNameL);
      encodingCode = new EncodingCode();
      destroyCodeW = new StatementWriter();
      destroyCode = new CFileWriter(destroyCodeW);
  	}
  }
  
  /**
   * Holds details about a composite field, to be used in code generation.
   * It could be interesting to make this class derive from CompositeField, however that class is declared final.
   * 
   * This class is presented as a simple structure, with no code.
   */
  private class CompositeFieldDetails {
  	boolean isPresentField = false;
  	boolean isAbstractAttribute = false;
  	boolean isAttribute = false;
  	boolean isComposite = false;
  	boolean isEnumeration = false;
  	boolean isList = false;
  	boolean isDestroyable = false;
		String fieldType = null;
		String qfTypeNameL = null;
  	String fieldName = null;
  	TypeReference type = null;
  }

  /**
   * Holds details about a parameter, to be used in code generation.
   * The structure is also used for an error.
   * 
   * This class is presented as a simple structure, with nearly no code.
   */
  private class ParameterDetails {
  	int paramIndex = 0;
  	boolean isLast = false;
  	boolean isPolymorph = false;
  	boolean isError = false;
  	boolean isPresenceFlag = false;
  	boolean isAbstract = false;
  	boolean isAbstractAttribute = false;
  	boolean isAttribute = false;
  	boolean isComposite = false;
  	boolean isEnumeration = false;
  	boolean isList = false;
  	boolean isPubSub = false;	// created to handle the null parameter in a PubSub operation issue, still unused
		String paramType = null;
		String qfTypeNameL = null;
  	String paramName = null;
  	TypeReference type = null;
  }
  
  /**
   * Isolate generation of the <area>.h file in this class.
   * 
   * @author lacourte
   *
   */
  private class AreaHWriter extends CFileWriter {
  	// name of the area
  	private final String areaName;
  	// area name in upper case letters
  	private final String areaNameCaps;
  	
    /**
     * Constructor.
     *
     * @param folder The folder to create the file in.
     * @param areaName The Area name.
     * @throws IOException If any problems creating the file.
     */
    public AreaHWriter(File folder, String areaName) throws IOException
    {
    	super();
    	this.areaName = areaName;
    	areaNameCaps = areaName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(folder, areaName, "h");
      out = new StatementWriter(file);
    }

    /**
     * Constructor.
     *
     * @param destinationFolderName Folder to create the file in.
     * @param areaName The Area name.
     * @throws IOException If any problems creating the file.
     */
    public AreaHWriter(String destinationFolderName, String areaName) throws IOException
    {
    	super();
    	this.areaName = areaName;
    	areaNameCaps = areaName.toUpperCase();
    	Writer file = StubUtils.createLowLevelWriter(destinationFolderName, areaName, "h");
      out = new StatementWriter(file);
    }

    /**
     * Open a #define statement for the file.
     * Should be closed by function closeDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void openDefine() throws IOException
    {
    	// #ifndef __<AREA>_H_INCLUDED__
    	// #define __<AREA>_H_INCLUDED__
    	out.append("#ifndef __");
    	out.append(areaNameCaps);
    	out.append("_H_INCLUDED__");
    	addNewLine();
    	out.append("#define __");
    	out.append(areaNameCaps);
    	out.append("_H_INCLUDED__");
    	addNewLine();
    	addNewLine();
    }

    /**
     * Close the #define statement for the file previously opened by function openDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void closeDefine() throws IOException
    {
    	// #endif // __<AREA>_H_INCLUDED__
    	addNewLine();
    	out.append("#endif // __");
    	out.append(areaNameCaps);
    	out.append("_H_INCLUDED__");
    	addNewLine();
    }

    /**
     * 
     * @param variable	name of the constant to define
     * @param value	value of the constant
     * @throws IOException If any problems writing to the file.
     */
    public void addAreaDefine(String variable, String value) throws IOException
    {
    	// #define <AREA>_<variable> <value>
    	out.append("#define ");
    	out.append(areaNameCaps);
    	out.append("_");
    	out.append(variable.toUpperCase());
    	out.append(" ");
    	out.append(value);
    	addNewLine();
    }
    
    /**
     * Finalize the file contents and close the underlying writer.
     * 
     * @throws IOException
     */
    public void close() throws IOException
    {
    	super.close();
    }
  }

  /**
   * Isolate generation of the <area>.c file in this class.
   * 
   * @author lacourte
   *
   */
  private class AreaCWriter extends CFileWriter {
  	
    /**
     * Constructor.
     *
     * @param folder The folder to create the file in.
     * @param areaName The Area name.
     * @throws IOException If any problems creating the file.
     */
    public AreaCWriter(File folder, String areaName) throws IOException
    {
    	super();
      Writer file = StubUtils.createLowLevelWriter(folder, areaName, "c");
      out = new StatementWriter(file);
    }

    /**
     * Constructor.
     *
     * @param destinationFolderName Folder to create the file in.
     * @param areaName The Area name.
     * @throws IOException If any problems creating the file.
     */
    public AreaCWriter(String destinationFolderName, String areaName) throws IOException
    {
    	super();
    	Writer file = StubUtils.createLowLevelWriter(destinationFolderName, areaName, "c");
      out = new StatementWriter(file);
    }
    
    /**
     * Finalize the file contents and close the underlying writer.
     * 
     * @throws IOException
     */
    public void close() throws IOException
    {
    	super.close();
    }
  }
  
  /**
   * Isolate generation of the <composite>.h file in this class.
   * 
   * @author lacourte
   *
   */
  private class CompositeHWriter extends CFileWriter {
  	// fully qualified name of the composite type
  	// <area>_[<service>_]<composite>
  	private final String compositeName;
  	// composite name in upper case letters
  	// <AREA>_[<SERVICE>_]<COMPOSITE>
  	private final String compositeNameCaps;

    /**
     * Constructor.
     *
     * @param folder The folder to create the file in.
     * @param compositeName The fully qualified name of the composite type.
     * @throws IOException If any problems creating the file.
     */
    public CompositeHWriter(File folder, String compositeName) throws IOException
    {
    	super();
    	this.compositeName = compositeName;
    	compositeNameCaps = compositeName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(folder, compositeName, "h");
      out = new StatementWriter(file);
    }

    /**
     * Constructor.
     *
     * @param destinationFolderName Folder to create the file in.
     * @param compositeName The fully qualified name of the composite type.
     * @throws IOException If any problems creating the file.
     */
    public CompositeHWriter(String destinationFolderName, String compositeName) throws IOException
    {
    	super();
    	this.compositeName = compositeName;
    	compositeNameCaps = compositeName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(destinationFolderName, compositeName, "h");
      out = new StatementWriter(file);
    }

    /**
     * Open a #define statement for the file.
     * Should be closed by function closeDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void openDefine() throws IOException
    {
    	// #ifndef __<AREA>_[<SERVICE>_]<COMPOSITE>_H_INCLUDED__
    	// #define __<AREA>_[<SERVICE>_]<COMPOSITE>_H_INCLUDED__
    	super.openDefine("__" + compositeNameCaps + "_H_INCLUDED__");
    }

    /**
     * Close the #define statement for the file previously opened by function openDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void closeDefine() throws IOException
    {
    	// #endif // __<AREA>_[<SERVICE>_]<COMPOSITE>_H_INCLUDED__
    	super.closeDefine("// __" + compositeNameCaps + "_H_INCLUDED__");
    }

  }
  
  /**
   * Isolate generation of the <composite>.c file in this class.
   * 
   * @author lacourte
   *
   */
  private class CompositeCWriter extends CFileWriter {
  	// fully qualified name of the composite type
  	private final String compositeName;
  	// composite name in upper case letters
  	private final String compositeNameCaps;

    /**
     * Constructor.
     *
     * @param folder The folder to create the file in.
     * @param compositeName The fully qualified name of the composite type.
     * @throws IOException If any problems creating the file.
     */
    public CompositeCWriter(File folder, String compositeName) throws IOException
    {
    	super();
    	this.compositeName = compositeName;
    	compositeNameCaps = compositeName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(folder, compositeName, "c");
      out = new StatementWriter(file);
    }

    /**
     * Constructor.
     *
     * @param destinationFolderName Folder to create the file in.
     * @param compositeName The fully qualified name of the composite type.
     * @throws IOException If any problems creating the file.
     */
    public CompositeCWriter(String destinationFolderName, String compositeName) throws IOException
    {
    	super();
    	this.compositeName = compositeName;
    	compositeNameCaps = compositeName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(destinationFolderName, compositeName, "c");
      out = new StatementWriter(file);
    }
  }

  /**
   * Isolate generation of the <type>_list.[h|c] file in this class.
   * 
   * @author lacourte
   *
   */
  private class TypeListWriter extends CFileWriter {
  	// fully qualified name of the type
  	// <area>_[<service>_]<type>_list
  	private final String typeName;
  	// type name in upper case letters
  	// <AREA>_[<SERVICE>_]<TYPE>_LIST
  	private final String typeNameCaps;

    /**
     * Constructor.
     *
     * @param folder The folder to create the file in.
     * @param typeName The fully qualified name of the type.
     * @param suffix	suffix of the file to create, "h" or "c"
     * @throws IOException If any problems creating the file.
     */
    public TypeListWriter(File folder, String typeName, String suffix) throws IOException
    {
    	super();
    	this.typeName = typeName;
    	typeNameCaps = typeName.toUpperCase();
    	File listFolder;
    	if ("h".equalsIgnoreCase(suffix))
    		listFolder = new File(folder,"include");
    	else
    		listFolder = new File(folder,"src");
    	if (!listFolder.exists())
    		listFolder.mkdirs();
    	Writer file = StubUtils.createLowLevelWriter(listFolder, typeName, suffix);
    	out = new StatementWriter(file);
    }

    /**
     * Constructor.
     *
     * @param destinationFolderName Folder to create the file in.
     * @param typeName The fully qualified name of the type.
     * @param suffix	suffix of the file to create, "h" or "c"
     * @throws IOException If any problems creating the file.
     */
    public TypeListWriter(String destinationFolderName, String typeName, String suffix) throws IOException
    {
    	super();
    	this.typeName = typeName;
    	typeNameCaps = typeName.toUpperCase();
      Writer file = StubUtils.createLowLevelWriter(destinationFolderName, typeName, suffix);
      out = new StatementWriter(file);
    }

    /**
     * Open a #define statement for the file.
     * Should be closed by function closeDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void openDefine() throws IOException
    {
    	// #ifndef __<TYPE_NAME>_H_INCLUDED__
    	// #define __<TYPE_NAME>_H_INCLUDED__
    	super.openDefine("__" + typeNameCaps + "_H_INCLUDED__");
    }

    /**
     * Close the #define statement for the file previously opened by function openDefine.
     * 
     * @throws IOException If any problems writing to the file.
     */
    public void closeDefine() throws IOException
    {
    	// #endif // __<TYPE_NAME>_H_INCLUDED__
    	super.closeDefine("// __" + typeNameCaps + "_H_INCLUDED__");
    }
  }
}
