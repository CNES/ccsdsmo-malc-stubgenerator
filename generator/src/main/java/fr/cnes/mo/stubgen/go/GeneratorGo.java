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
	import java.io.FileNotFoundException;
	import java.io.IOException;
	import java.io.Writer;
	import java.util.ArrayList;
	import java.util.HashMap;
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
import esa.mo.tools.stubgen.GeneratorBase.TypeKey;
import esa.mo.tools.stubgen.specification.AttributeTypeDetails;
	import esa.mo.tools.stubgen.specification.CompositeField;
	import esa.mo.tools.stubgen.specification.InteractionPatternEnum;
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
import esa.mo.xsd.ErrorDefinitionList;
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
	 * Generates code from MAL services specifications to use with the Go API of the MAL from CNES.
	 * This code uses the MAL code generation framework from ESA.
	 */
	public class GeneratorGo extends GeneratorBase
	{
		// we assume all CCSDS/go code is in the same go package
		public static final String BASE_PACKAGE_DEFAULT = "github.com/CNES/ccsdsmo-malgo";

  	static final HashMap<InteractionPatternEnum, String> operationTypes;
	  // list of all enumeration types, with their malbinary encoding size
	  private final Map<TypeKey, MalbinaryEnumSize> enumTypesMBSize = new TreeMap<TypeKey, MalbinaryEnumSize>();
	  
		// generation for the transports malbinary and malsplitbinary
		// currently statically set to true
		private boolean generateTransportMalbinary;
		private boolean generateTransportMalsplitbinary;
		
		// generate all areas in a single zproject
		// and build the project.xml file
		private boolean singleZproject = true;
		private String zprojectName = "generated_areas";
		List<String> zareas;
		List<String> zclasses;
		HashMap<String,String> typePackages = new HashMap<>();

  	static {
  		operationTypes = new HashMap<>(5);
  		operationTypes.put(InteractionPatternEnum.SEND_OP, "Send");
  		operationTypes.put(InteractionPatternEnum.SUBMIT_OP, "Submit");
  		operationTypes.put(InteractionPatternEnum.REQUEST_OP, "Request");
  		operationTypes.put(InteractionPatternEnum.INVOKE_OP, "Invoke");
  		operationTypes.put(InteractionPatternEnum.PROGRESS_OP, "Progress");
  	}
  	
	  /**
	   * Constructor used by the StubGenerator main.
	   * The parameters of the GeneratorConfiguration object ar not used.
	   *
	   * @param logger The logger to use.
	   */
	  public GeneratorGo(org.apache.maven.plugin.logging.Log logger)
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
	    return "go";
	  }
	
	  @Override
	  public String getDescription()
	  {
	  	return "Generates a Go language mapping.";
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
	    //
	    // The following statements activate code from the base class GeneratorConfiguration.
	    // We only use native types (3rd parameter true) so that complex code from this class is not used.
	    addAttributeType(StdStrings.MAL, StdStrings.BLOB, true, "mal.Blob", "");
	    addAttributeType(StdStrings.MAL, StdStrings.BOOLEAN, true, "mal.Boolean", "false");
	    addAttributeType(StdStrings.MAL, StdStrings.DOUBLE, true, "mal.Double", "");
	    addAttributeType(StdStrings.MAL, StdStrings.DURATION, true, "mal.Duration", "");
	    addAttributeType(StdStrings.MAL, StdStrings.FLOAT, true, "mal.Float", "");
	    addAttributeType(StdStrings.MAL, StdStrings.INTEGER, true, "mal.Integer", "");
	    addAttributeType(StdStrings.MAL, StdStrings.IDENTIFIER, true, "mal.Identifier", "");
	    addAttributeType(StdStrings.MAL, StdStrings.LONG, true, "mal.Long", "");
	    addAttributeType(StdStrings.MAL, StdStrings.OCTET, true, "mal.Octet", "");
	    addAttributeType(StdStrings.MAL, StdStrings.SHORT, true, "mal.Short", "");
	    addAttributeType(StdStrings.MAL, StdStrings.UINTEGER, true, "mal.UInteger", "");
	    addAttributeType(StdStrings.MAL, StdStrings.ULONG, true, "mal.ULong", "");
	    addAttributeType(StdStrings.MAL, StdStrings.UOCTET, true, "mal.UOctet", "");
	    addAttributeType(StdStrings.MAL, StdStrings.USHORT, true, "mal.UShort", "");
	    addAttributeType(StdStrings.MAL, StdStrings.STRING, true, "mal.String", "");
	    addAttributeType(StdStrings.MAL, StdStrings.TIME, true, "mal.Time", "");
	    addAttributeType(StdStrings.MAL, StdStrings.FINETIME, true, "mal.FineTime", "");
	    addAttributeType(StdStrings.MAL, StdStrings.URI, true, "mal.URI", "");
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
	  	// This is the main entry point into the Go generator from the base class GeneratorBase
	    getLog().info("Processing specification: " + spec.getComment());
	  	
	    File destFolder = new File(destinationFolderName);
	    // make sure the folder exists
	    if (!destFolder.exists() && !destFolder.mkdirs())
	    {
	      throw new FileNotFoundException("Failed to create directory: " + destFolder.getPath());
	    }
	    
	    // preprocess the areas (and services) to get their go package name
	    String areaDir = findBasePackage(destinationFolderName);
	    for (AreaType area : spec.getArea())
	    {
		    getLog().info("Preprocessing area: " + area.getName());
	    	String areaName = area.getName().toLowerCase();
	    	if (typePackages.put(areaName, areaDir + areaName) != null) {
	    		throw new IllegalStateException("duplicate go package name for area " + area.getName());
	    	}
	    	String serviceDir = areaDir + areaName + "/";
	    	for (ServiceType service : area.getService()) {
			    getLog().info("Preprocessing service: " + service.getName());
		    	String serviceName = service.getName().toLowerCase();
		    	if (typePackages.put(serviceName, serviceDir + serviceName) != null) {
		    		throw new IllegalStateException("duplicate go package name for service " + area.getName() + ":" + service.getName());
		    	}
	    	}
	    }
	    
	    for (AreaType area : spec.getArea())
	    {
	      processArea(destFolder, area);
	    }
	  }
	
		@Override
	  public void close(String destinationFolderName) throws IOException
	  {
	  	super.close(destinationFolderName);
	  }

	  /**
	   * Find the base go package from a directory name.
	   * There should be a "src" path element in the directory name.
	   * The base go package is what follows the src path element.
	   * 
	   * @param directory
	   * @return	base go package
	   */
	  private String findBasePackage(String directory)
	  {
	    // find the "src" marker in the destinationFolderName
	    LinkedList<String> folderPath = new LinkedList<>();
	    boolean found = false;
	    for (File folder = new File(directory); !found && folder != null;) {
	    	String name = folder.getName();
	    	if ("src".equals(name)) {
	    		found = true;
	    	} else {
	    		folderPath.addFirst(name);
	    		folder = folder.getParentFile();
	    	}
	    }
	    if (!found) {
	    	throw new IllegalArgumentException("Missing \"src\" path element in directory name.");
	    }
	    StringBuffer folderBuf = new StringBuffer();
	    int pathNum = folderPath.size();
	    for (int i = 0; i < pathNum; i++) {
	    	folderBuf.append(folderPath.get(i));
	    	folderBuf.append("/");
	    }
	    return folderBuf.toString();
	  }
	  
	  /**
	   * Find the go import path of a type/
	   * 
	   * @param type
	   * @return
	   */
		public String getTypePath(TypeReference type) {
			boolean isServiceType = type.getService() != null;
			String packageName = isServiceType ? type.getService() : type.getArea();
			packageName = packageName.toLowerCase();
			String typePath = typePackages.get(packageName);
			if (typePath != null)
				return typePath;
			StringBuffer pathBuf = new StringBuffer(BASE_PACKAGE_DEFAULT);
			if (isServiceType) {
				pathBuf.append('/').append(type.getArea().toLowerCase());
			}
			pathBuf.append('/').append(packageName);
			typePath = pathBuf.toString();
			typePackages.put(packageName, typePath);
			if (typePath == null || typePath.length() == 0)
				throw new IllegalStateException("null import path");
			return typePath;
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
	    if ((!area.getName().equalsIgnoreCase(StdStrings.COM)) || (generateCOM()))
	    {
	      getLog().info("Processing area: " + area.getName());
	      AreaContext areaContext = new AreaContext(destinationFolder, area);
	      areaContext.helper.init();
	
	      // if area level types exist
	      if (true && (null != area.getDataTypes()) && !area.getDataTypes().getFundamentalOrAttributeOrComposite().isEmpty())
	      {
	        // create area level data types
	        for (Object oType : area.getDataTypes().getFundamentalOrAttributeOrComposite())
	        {
	        	// despite the name of the property, it looks like enumerations actually are in the list
	          if (oType instanceof FundamentalType || oType instanceof AttributeType)
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
	      
	      // write the main content of the <area>_helper.go file
	      areaContext.helper.addStatements(areaContext.helper.helperContentW);
	
	      // finalize the area files
	      areaContext.helper.flush();
	      areaContext.helper.close();
	    }
	  }
	
	  protected void processService(AreaContext areaContext, ServiceType service) throws IOException
	  {
	    getLog().info("Processing service: " + service.getName());
	  	ServiceContext serviceContext = new ServiceContext(areaContext, service);
	  	serviceContext.helper.init();
	
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
	
	    serviceContext.consumer.init();
	    serviceContext.provider.init();
	  	
	    // TODO commentaire incompris
	    // don't create operation classes for COM as this is autogenerated in the specific services
//	    if (! serviceContext.summary.isComService())
//	    {
	    	for (OperationSummary op : serviceContext.summary.getOperations())
	      {
	    		processOperation(serviceContext, op);
	      }
//	    }

		  	serviceContext.helper.close();
		  	serviceContext.consumer.close();
		  	serviceContext.provider.close();
	  }
	
	  protected void processOperation(ServiceContext serviceContext, OperationSummary operation) throws IOException
	  {
	    getLog().info("Processing operation: " + operation.getName());
	  	OperationContext opContext = new OperationContext(serviceContext, operation);
	  	OperationErrorList errors = null;
	
	    GoFileWriter serviceOperations = serviceContext.helper.serviceOperations;
	    serviceOperations.addVariableDeclare("mal.UShort", opContext.operationNumberConstName, String.valueOf(operation.getNumber()));
	
	    switch (operation.getPattern())
	    {
	    	case SEND_OP:
	    	{
	  	    addPHelperOpStructure(opContext);
	    		addConsumerOpStructure(opContext);
	    		processInitInteraction(opContext, "Send", operation.getArgTypes(), null);
	    		break;
	    	}
	    	case SUBMIT_OP:
	    	{
	    		OperationType opType = operation.getOriginalOp();
	    		if (opType == null || ! (opType instanceof SubmitOperationType))
	    		{
	    			throw new IllegalStateException("Submit operation " + opContext.qfOpNameL + " is not typed SubmitOperationType");
	    		}
	    		errors = ((SubmitOperationType) opType).getErrors();
	    		if (errors == null || errors.getErrorOrErrorRef() == null || errors.getErrorOrErrorRef().isEmpty()) {
	    			errors = null;
	    		} else {
	    			opContext.isNoError = false;
	    		}

	  	    addPHelperOpStructure(opContext);
	    		addConsumerOpStructure(opContext);
	    		processInitInteraction(opContext, "Submit", operation.getArgTypes(), operation.getAckTypes());
	    		processNextInteraction(opContext, "Ack", null, operation.getAckTypes(), true);
	    		break;
	    	}
	    	case REQUEST_OP:
	    	{
	    		OperationType opType = operation.getOriginalOp();
	    		if (opType == null || ! (opType instanceof RequestOperationType))
	    		{
	    			throw new IllegalStateException("Request operation " + opContext.qfOpNameL + " is not typed RequestOperationType");
	    		}
	    		errors = ((RequestOperationType) opType).getErrors();
	    		if (errors == null || errors.getErrorOrErrorRef() == null || errors.getErrorOrErrorRef().isEmpty()) {
	    			errors = null;
	    		} else {
	    			opContext.isNoError = false;
	    		}

	  	    addPHelperOpStructure(opContext);
	    		addConsumerOpStructure(opContext);
	    		processInitInteraction(opContext, "Request", operation.getArgTypes(), operation.getRetTypes());
	    		processNextInteraction(opContext, "Reply", null, operation.getRetTypes(), true);
	    		break;
	    	}
	    	case INVOKE_OP:
	    	{
	    		OperationType opType = operation.getOriginalOp();
	    		if (opType == null || ! (opType instanceof InvokeOperationType))
	    		{
	    			throw new IllegalStateException("Invoke operation " + opContext.qfOpNameL + " is not typed InvokeOperationType");
	    		}
	    		errors = ((InvokeOperationType) opType).getErrors();
	    		if (errors == null || errors.getErrorOrErrorRef() == null || errors.getErrorOrErrorRef().isEmpty()) {
	    			errors = null;
	    		} else {
	    			opContext.isNoError = false;
	    		}

	  	    addPHelperOpStructure(opContext);
	    		addConsumerOpStructure(opContext);
	    		processInitInteraction(opContext, "Invoke", operation.getArgTypes(), operation.getAckTypes());
	    		processNextInteraction(opContext, "Ack", null, operation.getAckTypes(), true);
	    		processNextInteraction(opContext, "Reply", null, operation.getRetTypes());
	    		break;
	    	}
	    	case PROGRESS_OP:
	    	{
	    		OperationType opType = operation.getOriginalOp();
	    		if (opType == null || ! (opType instanceof ProgressOperationType))
	    		{
	    			throw new IllegalStateException("Progress operation " + opContext.qfOpNameL + " is not typed ProgressOperationType");
	    		}
	    		errors = ((ProgressOperationType) opType).getErrors();
	    		if (errors == null || errors.getErrorOrErrorRef() == null || errors.getErrorOrErrorRef().isEmpty()) {
	    			errors = null;
	    		} else {
	    			opContext.isNoError = false;
	    		}

	  	    addPHelperOpStructure(opContext);
	    		addConsumerOpStructure(opContext);
	    		processInitInteraction(opContext, "Progress", operation.getArgTypes(), operation.getAckTypes());
	    		processNextInteraction(opContext, "Ack", null, operation.getAckTypes(), true);
	    		processNextInteraction(opContext, "Update", null, operation.getUpdateTypes());
	    		processNextInteraction(opContext, "Reply", null, operation.getRetTypes());
	    		break;
	    	}
	    	case PUBSUB_OP:
	    	{
	    		OperationType opType = operation.getOriginalOp();
	    		if (opType == null || ! (opType instanceof PubSubOperationType))
	    		{
	    			throw new IllegalStateException("PubSub operation " + opContext.qfOpNameL + " is not typed PubSubOperationType");
	    		}
	    		errors = ((PubSubOperationType) opType).getErrors();
	    		if (errors == null || errors.getErrorOrErrorRef() == null || errors.getErrorOrErrorRef().isEmpty()) {
	    			errors = null;
	    		} else {
	    			opContext.isNoError = false;
	    		}
	    		
	    		// generate the subscriber consumer
	  	  	OperationContext subOpContext = new OperationContext(serviceContext, operation, false);
	    		addConsumerOpStructure(subOpContext);
	    		// REGISTER stage
	    		TypeInfo subscriptionType = new TypeInfo(
	    				TypeUtils.createTypeReference("MAL",  null,  "Subscription", false), 
	    				"subscription", "", "MAL::Subscription", "mal.Subscription", false, "0x1000001000017", "");
	    		List<TypeInfo> registerTypes = new ArrayList<TypeInfo>(1);
	    		registerTypes.add(subscriptionType);
	    		processInitInteraction(subOpContext, "Register", registerTypes, null);
	    		// NOTIFY stage
	    		TypeInfo identifierType = new TypeInfo(
	    				TypeUtils.createTypeReference("MAL",  null,  "Identifier", false), 
	    				"subscriptionid", "", "MAL::Identifier", "mal.Identifier", true, "0x1000001000006", "");
	    		TypeInfo updateHeaderList = new TypeInfo(
	    				TypeUtils.createTypeReference("MAL",  null,  "UpdateHeader", true), 
	    				"updateHeaders", "", "MAL::UpdateHeader", "mal.UpdateHeaderList", false, "0x1000001FFFFE6", "");
	    		List<TypeInfo> notifyTypes = new ArrayList<TypeInfo>(operation.getUpdateTypes().size()+2);
	    		notifyTypes.add(identifierType);
	    		notifyTypes.add(updateHeaderList);
	    		// try de change the types in their list couterparts
	    		for (TypeInfo updateType : operation.getUpdateTypes()) {
	    			TypeInfo updateListType = new TypeInfo(
		    				TypeUtils.createTypeReference(updateType.getSourceType().getArea(), updateType.getSourceType().getService(), updateType.getSourceType().getName(), true), 
		    				updateType.getFieldName(), updateType.getFieldComment(), updateType.getActualMalType(),
		    				updateType.getTargetType(), false, updateType.getMalShortFormField(), updateType.getMalVersionField());
	    			notifyTypes.add(updateListType);
	    		}
	    		processNextInteraction(subOpContext, "Notify", null, notifyTypes);
	    		// DEREGISTER stage
	    		TypeInfo identifierListType = new TypeInfo(
	    				TypeUtils.createTypeReference("MAL",  null,  "Identifier", true), 
	    				"subscriptionids", "", "MAL::Identifier", "mal.IdentifierList", false, "0x1000001FFFFFA", "");
	    		List<TypeInfo> deregisterTypes = new ArrayList<TypeInfo>(1);
	    		deregisterTypes.add(identifierListType);
	    		processNextInteraction(subOpContext, "Deregister", deregisterTypes, null);
	    		
	    		// generate the publisher consumer
	    		OperationContext pubOpContext = new OperationContext(serviceContext, operation, true);
	    		addConsumerOpStructure(pubOpContext);
	    		// PUBLISH_REGISTER stage
	    		TypeInfo entityKeyListType = new TypeInfo(
	    				TypeUtils.createTypeReference("MAL",  null,  "EntityKey", true), 
	    				"entitykeys", "", "MAL::EntityKey", "mal.EntityKeyList", false, "0x1000001FFFFE7", "");
	    		List<TypeInfo> publishRegisterTypes = new ArrayList<TypeInfo>(1);
	    		publishRegisterTypes.add(entityKeyListType);
	    		processInitInteraction(pubOpContext, "Register", publishRegisterTypes, null);
	    		// PUBLISH stage
	    		List<TypeInfo> publishTypes = new ArrayList<TypeInfo>(operation.getUpdateTypes().size()+1);
	    		publishTypes.add(updateHeaderList);
	    		for (int i = 2; i < notifyTypes.size(); i++) {
	    			publishTypes.add(notifyTypes.get(i));
	    		}
	    		processNextInteraction(pubOpContext, "Publish", publishTypes, null);
	    		// PUBLISH_DEREGISTER stage
	    		processNextInteraction(pubOpContext, "Deregister", null, null);
	    		
	    		// generate dummy code to avoid errors related to defects in PubSub code generation
	    		generatePubsubDummyCode(opContext);
	    		break;
	    	}
	    }
	    // declare the errors
	    // generate related code even when errors is null
	    processOpErrors(opContext, errors);
	  }

	  protected void addPHelperOpStructure(OperationContext opContext) throws IOException
	  {
	  	GoFileWriter servicePHelper = opContext.serviceContext.provider.servicePHelper;
	  	StatementWriter servicePHelperW = opContext.serviceContext.provider.servicePHelperW;
	  	String opName = opContext.operation.getName();
	    String operationIp = operationTypes.get(opContext.operation.getPattern());
	    String ipTransactionType = "malapi." + operationIp + "Transaction";
	  	
	    String comment = "generated code for operation " + opName;
	    servicePHelper.addNewLine();
	    servicePHelper.addSingleLineComment(comment);
	    
	    // create the provider helper structure specific to the operation
	    // type <operation>Helper struct {
	    //   [acked				bool]
	    //   transaction	malapi.<IP>Transaction
	    // }
	    String initValues = null;
	    servicePHelper.openStruct(opContext.helperStructName);
	  	switch (opContext.operation.getPattern()) {
	  	case SEND_OP:
	  	case SUBMIT_OP:
	  	case REQUEST_OP:
	  	case PUBSUB_OP:
		  	break;
	  	case INVOKE_OP:
	  	case PROGRESS_OP:
		    servicePHelper.addStructField("bool", "acked");
		    initValues = "false";
	  		break;
	  	default:
	  		throw new IllegalStateException("unexpected pattern in addPHelperOpStructure: " + opContext.operation.getPattern());
	  	}
	    servicePHelper.addStructField(ipTransactionType, "transaction");
	    servicePHelper.closeStruct();

	    // constructor for the provider helper structure
	    // func New<operation>Helper(transaction malapi.Transaction) (*<operation>Helper, error) {
	    //   iptransaction, ok := transaction.(malapi.<IP>Transaction)
	    //   if !ok {
	    //     return nil, errors.New("Unexpected transaction type")
	    //   }
	    //   helper := &<operation>Helper{iptransaction}
	    //   return helper, nil
	    // }
	    servicePHelper.openFunction("New" + opContext.helperStructName, null);
	    servicePHelper.addFunctionParameter("malapi.Transaction", "transaction", true);
	    servicePHelper.openFunctionBody(new String[] { "*" + opContext.helperStructName, "error" });
	    servicePHelper.addIndent();
	    servicePHelperW
      	.append("iptransaction, ok := transaction.(")
      	.append(ipTransactionType)
      	.append(")");
	    servicePHelper.addNewLine();
	    servicePHelper.addStatement("if !ok {", 1);
	    servicePHelper.addStatement("return nil, errors.New(\"Unexpected transaction type\")");
	    servicePHelper.closeBlock();
	    servicePHelperW
	      .append("helper := &")
	      .append(opContext.helperStructName)
	      .append("{");
	    if (initValues != null) {
	    	servicePHelperW.append(initValues).append(", ");
	    }
	    servicePHelperW.append("iptransaction}");
	    servicePHelper.addNewLine();
	    servicePHelper.addStatement("return helper, nil");
	    servicePHelper.closeFunctionBody(); 
	  }
	  
	  protected void addConsumerOpStructure(OperationContext opContext) throws IOException
	  {
	  	ServiceContext serviceContext = opContext.serviceContext;
	  	AreaContext areaContext = serviceContext.areaContext;
	  	GoFileWriter serviceCContent = opContext.serviceContext.consumer.serviceContent;
	  	StatementWriter serviceCContentW = opContext.serviceContext.consumer.serviceContentW;
	  	String opName = opContext.operation.getName();

	    String comment = "generated code for operation " + opName;
	    serviceCContent.addNewLine();
	    serviceCContent.addSingleLineComment(comment);
	    
	    // create the consumer structure specific to the operation
	    // type <operation>Operation struct {
	    //   op      mal.<type IP>Operation
	    // }
	    serviceCContent.openStruct(opContext.consumerStructName);
	    serviceCContent.addStructField(opContext.operationType, "op");
	    serviceCContent.closeStruct();
	    
	    // constructor for the consumer structure
	    // func New<operation>Operation(providerURI *mal.URI) (*<operation>Operation, error) {
	    //   op := Cctx.New<type IP>Operation(providerURI, <area number>, <area version>, <service number>, <OPERATION>_OPERATION_NUMBER)
	    //   consumer := &<operation>Operation{op}
	    //   return consumer, nil
	    // }
	    serviceCContent.openFunction("New" + opContext.consumerStructName, null);
	    serviceCContent.addFunctionParameter("*mal.URI", "providerURI", true);
	    serviceCContent.openFunctionBody(new String[] { "*" + opContext.consumerStructName, "error" });
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("op := Cctx.New")
	      .append(opContext.operationIp)
	      .append("Operation(providerURI, ")
	      .append(areaContext.areaNameL)
	      .append('.')
	      .append(areaContext.areaNumberConstName)
	      .append(", ")
	      .append(areaContext.areaNameL)
	      .append('.')
	      .append(areaContext.areaVersionConstName)
	      .append(", ")
	      .append(serviceContext.serviceNumberConstName)
	      .append(", ")
	      .append(opContext.operationNumberConstName)
	      .append(")");
	    serviceCContent.addNewLine();
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("consumer := &")
	      .append(opContext.consumerStructName)
	      .append("{op}");
	    serviceCContent.addNewLine();
	    serviceCContent.addStatement("return consumer, nil");
	    serviceCContent.closeFunctionBody(); 
	  }

	  protected void addConsumerPubsubStructures(OperationContext opContext) throws IOException
	  {
	  	GoFileWriter serviceCContent = opContext.serviceContext.consumer.serviceContent;
	  	StatementWriter serviceCContentW = opContext.serviceContext.consumer.serviceContentW;
	  	String opName = opContext.operation.getName();

	    String comment = "generated code for operation " + opName;
	    serviceCContent.addNewLine();
	    serviceCContent.addSingleLineComment(comment);
	    
	    // create the subscriber structure specific to the operation
	    // type <operation>Subscriber struct {
	    //   op      mal.SubscriberOperation
	    // }
	  	String consumerStructName = opName + "Subscriber";
	    serviceCContent.openStruct(consumerStructName);
	    serviceCContent.addStructField("mal.SubscriberOperation", "op");
	    serviceCContent.closeStruct();
	    
	    // constructor for the consumer structure
	    // func New<operation>Subscriber(providerURI *mal.URI) (*<operation>Subscriber, error) {
	    //   op := clientContext.NewSubscriberOperation(providerURI, <area number>, <area version>, <service number>, <OPERATION>_OPERATION_NUMBER)
	    //   subscriber := &<operation>Subscriber{op}
	    //   return subscriber, nil
	    // }
	    serviceCContent.openFunction("New" + consumerStructName, null);
	    serviceCContent.addFunctionParameter("*mal.URI", "providerURI", true);
	    serviceCContent.openFunctionBody(new String[] { "*" + consumerStructName, "error" });
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("op := clientContext.NewSubscriberOperation(providerURI, ")
	      .append(opContext.serviceContext.areaContext.areaNumberConstName)
	      .append(", ")
	      .append(opContext.serviceContext.areaContext.areaVersionConstName)
	      .append(", ")
	      .append(opContext.serviceContext.serviceNumberConstName)
	      .append(", ")
	      .append(opContext.operationNumberConstName)
	      .append(")");
	    serviceCContent.addNewLine();
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("subscriber := &")
	      .append(consumerStructName)
	      .append("{op}");
	    serviceCContent.addNewLine();
	    serviceCContent.addStatement("return subscriber, nil");
	    serviceCContent.closeFunctionBody();

	    // create the publisher structure specific to the operation
	    // type <operation>Publisher struct {
	    //   op      mal.PublisherOperation
	    // }
	    consumerStructName = opName + "Publisher";
	    serviceCContent.openStruct(consumerStructName);
	    serviceCContent.addStructField("mal.PublisherOperation", "op");
	    serviceCContent.closeStruct();
	    
	    // constructor for the consumer structure
	    // func New<operation>Publisher(providerURI *mal.URI) (*<operation>Publisher, error) {
	    //   op := clientContext.NewPublisherOperation(providerURI, <area number>, <area version>, <service number>, <OPERATION>_OPERATION_NUMBER)
	    //   publisher := &<operation>Publisher{op}
	    //   return publisher, nil
	    // }
	    serviceCContent.openFunction("New" + consumerStructName, null);
	    serviceCContent.addFunctionParameter("*mal.URI", "providerURI", true);
	    serviceCContent.openFunctionBody(new String[] { "*" + consumerStructName, "error" });
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("op := clientContext.NewPublisherOperation(providerURI, ")
	      .append(opContext.serviceContext.areaContext.areaNumberConstName)
	      .append(", ")
	      .append(opContext.serviceContext.areaContext.areaVersionConstName)
	      .append(", ")
	      .append(opContext.serviceContext.serviceNumberConstName)
	      .append(", ")
	      .append(opContext.operationNumberConstName)
	      .append(")");
	    serviceCContent.addNewLine();
	    serviceCContent.addIndent();
	    serviceCContentW
	      .append("publisher := &")
	      .append(consumerStructName)
	      .append("{op}");
	    serviceCContent.addNewLine();
	    serviceCContent.addStatement("return publisher, nil");
	    serviceCContent.closeFunctionBody();
	  }

	  protected void generatePubsubDummyCode(OperationContext opContext) throws IOException
	  {
	  	// func <operation>Dummy() error {
	  	//   return errors.New(string(<area>.AREA_NAME))
	  	// }
	  	GoFileWriter servicePHelper = opContext.serviceContext.provider.servicePHelper;
	  	StatementWriter servicePHelperW = opContext.serviceContext.provider.servicePHelperW;
	  	String funcName = opContext.operationName + "Dummy";
	  	servicePHelper.openFunction(funcName, null);
	  	servicePHelper.openFunctionBody(new String[] { "error" });
	  	servicePHelper.addIndent();
	  	servicePHelperW
	  		.append("return errors.New(string(")
	  		.append(opContext.serviceContext.areaContext.areaNameL)
	  		.append(".AREA_NAME))");
	  	servicePHelper.addNewLine();
	  	servicePHelper.closeFunctionBody();
	  }
	  
	  protected void processOpErrors(OperationContext opContext, OperationErrorList errors) throws IOException
	  {
	  	List<Object> errorList = null;
	  	if (errors != null) {
	  		errorList = errors.getErrorOrErrorRef();
	  		if (errorList.isEmpty())
	  			errorList = null;
	  	}

	  	if (opContext.operation.getPattern() == InteractionPatternEnum.SEND_OP) {
	  		return;
	  	}
	  	if (opContext.operation.getPattern() == InteractionPatternEnum.PUBSUB_OP) {
	  		if (errorList == null)
	  			return;
	  		throw new IllegalStateException("TODO: unimplemented errors in PUBSUB pattern");
	  	}
	  	
	  	// define the provider helper error encoding function and consumer decoding function
	  	GoFileWriter servicePHelper = opContext.serviceContext.provider.servicePHelper;
	  	StatementWriter servicePHelperW = opContext.serviceContext.provider.servicePHelperW;
	  	// func (receiver *DosubmitHelper) ReturnError(e error) error {
	    //   transaction := receiver.transaction
	  	//   body := transaction.NewBody()
	  	//   var errCode *mal.UInteger"
	  	//   var errExtraInfo mal.Element
	  	//   var errIsAbstract bool
	  	//   malErr, ok := e.(*malapi.MalError)
	  	//   if ok {
  		//     isAbstract := false
	  	servicePHelper.addNewLine();
	  	servicePHelper.openFunction("ReturnError", "*" + opContext.helperStructName);
	  	servicePHelper.addFunctionParameter("error", "e", true);
	  	servicePHelper.openFunctionBody(new String[] { "error" });
	  	servicePHelper.addStatement("transaction := receiver.transaction");
	  	servicePHelper.addStatement("body := transaction.NewBody()");
	  	servicePHelper.addStatement("var errCode *mal.UInteger");
	  	servicePHelper.addStatement("var errExtraInfo mal.Element");
	  	servicePHelper.addStatement("var errIsAbstract bool");
	  	servicePHelper.addStatement("malErr, ok := e.(*malapi.MalError)");
	  	servicePHelper.addStatement("if ok {", 1);
	  	servicePHelper.addStatement("errCode = &malErr.Code");
	  	servicePHelper.addStatement("errExtraInfo = malErr.ExtraInfo");
	  	servicePHelper.addStatement("errIsAbstract = false");

	  	// func (receiver *<operation>Operation) decodeError(resp *mal.Message, e error) error {
	  	GoFileWriter serviceCContent = opContext.serviceContext.consumer.serviceContent;
	  	StatementWriter serviceCContentW = opContext.serviceContext.consumer.serviceContentW;
	  	serviceCContent.addNewLine();
	  	serviceCContent.openFunction("decodeError", "*" + opContext.consumerStructName);
	  	serviceCContent.addFunctionParameter("*mal.Message", "resp", false);
	  	serviceCContent.addFunctionParameter("error", "e", true);
	  	serviceCContent.openFunctionBody(new String[] { "error" });
  		//   // decode err parameters
  		//   outElem_code, err := resp.DecodeParameter(mal.NullUInteger)
  		//   if err != nil {
  		//     return err
	  	//   }
  		//   outParam_code, ok := outElem_code.(*mal.UInteger)
  		//   if ! ok {
  		//     err = errors.New("unexpected type for parameter code")
  		//     return err
	  	//   }
  		//   nullValue := mal.NullElement
  		//   errIsAbstract := false
  		//   switch *outParam_code {
  		String comment = "decode err parameters";
  		serviceCContent.addSingleLineComment(comment);
  		serviceCContent.addStatement("outElem_code, err := resp.DecodeParameter(mal.NullUInteger)");
  		serviceCContent.addStatement("if err != nil {", 1);
  		serviceCContent.addStatement("return err");
  		serviceCContent.closeBlock();
  		serviceCContent.addStatement("outParam_code, ok := outElem_code.(*mal.UInteger)");
  		serviceCContent.addStatement("if ! ok {", 1);
  		serviceCContent.addStatement("err = errors.New(\"unexpected type for parameter code\")");
  		serviceCContent.addStatement("return err");
  		serviceCContent.closeBlock();
  		serviceCContent.addStatement("nullValue := mal.NullElement");
  		serviceCContent.addStatement("errIsAbstract := false");
  		serviceCContent.addStatement("switch *outParam_code {");
	  	
  		if (errorList != null) {
  			for (Object error : errorList)
  			{
  				long code;
  				String codeName = null;
  				ElementReferenceWithCommentType extraInfo = null;
  				if (error instanceof ErrorDefinitionType)
  				{
  					ErrorDefinitionType errorDef = (ErrorDefinitionType) error;
  					// define the constant for the error code
  					// <OPERATION>_ERROR_<name> mal.UInteger = <number>
  					StringBuffer codeNameBuf = new StringBuffer();
  					codeNameBuf
  					.append(opContext.operationName.toUpperCase())
  					.append("_ERROR_")
  					.append(errorDef.getName());
  					codeName = codeNameBuf.toString();
  					opContext.serviceContext.helper.addVariableDeclare("mal.UInteger", codeName, Long.toString(errorDef.getNumber()));

  					extraInfo = errorDef.getExtraInformation();
  				}
  				else if (error instanceof ErrorReferenceType)
  				{
  					ErrorReferenceType errorRef = (ErrorReferenceType) error;
  					// TODO import the reference area or service

  					// build the code constant name
  					StringBuffer codeNameBuf = new StringBuffer();
  					String codePackage = errorRef.getType().getService() != null ? errorRef.getType().getService() : errorRef.getType().getArea();
  					codePackage = codePackage.toLowerCase();
  					if (! servicePHelper.packageName.equals(codePackage)) {
  						codeNameBuf.append(codePackage).append(".");
  					}
  					codeNameBuf
  					.append("ERROR_")
  					.append(errorRef.getType().getName().toUpperCase());
  					codeName = codeNameBuf.toString();
  					extraInfo = errorRef.getExtraInformation();
  				}
  				else
  				{
  					throw new IllegalStateException("unexpected error structure: " + error.getClass().getName());
  				}
  				//   case <code>:
  				serviceCContent.addStatement("case " + codeName + ":", 1);
  				if (extraInfo != null) {
  					if (isAbstract(extraInfo.getType())) {
  						// if malErr.Code == <codeName> { errIsAbstract = true }
  						servicePHelper.addIndent();
  						servicePHelperW
  						.append("if malErr.Code == ")
  						.append(codeName)
  						.append(" { errIsAbstract = true }");
  						servicePHelper.addNewLine();

  						//     errIsAbstract = true
  						serviceCContent.addStatement("errIsAbstract = true", -1);
  					} else {
  						//     nullValue = <nullValue>
  						StringBuffer nullValueBuf = new StringBuffer("nullValue = ");
  						TypeReference extraInfoType = extraInfo.getType();
  						String typePackage = extraInfoType.getService();
  						if (typePackage == null)
  							typePackage = extraInfoType.getArea();
  						typePackage = typePackage.toLowerCase();
  						if (!typePackage.equals(serviceCContent.packageName)) {
  							nullValueBuf.append(typePackage).append(".");
  						}
  						nullValueBuf.append("Null").append(extraInfoType.getName());
  						if (extraInfoType.isList()) {
  							nullValueBuf.append("List");
  						}
  						serviceCContent.addStatement(nullValueBuf.toString(), -1);
  					}
  				} else {
  					// assume the extra info is a String
  					serviceCContent.addStatement("nullValue = mal.NullString", -1);
  				}
  			}
  		}
	  	// default error case, try to decode a String
  		//   default:
			//     nullValue = mal.NullString
			serviceCContent.addStatement("default:", 1);
			serviceCContent.addStatement("nullValue = mal.NullString", -1);
	  	
	  	
	  	//   } else {
	    //     // return an UNKNOWN error with a String information
	  	//     errCode = mal.NewUInteger(uint32(mal.ERROR_UNKNOWN))
	  	//     errExtraInfo = mal.NewString(e.Error())
	  	//     errIsAbstract = false
	  	//   }
	  	servicePHelper.addIndent(-1);
	  	servicePHelperW.append("} else {");
	  	servicePHelper.addNewLine(1);
	  	servicePHelper.addSingleLineComment("return an UNKNOWN error with a String information");
	  	servicePHelper.addStatement("errCode = mal.NewUInteger(uint32(mal.ERROR_UNKNOWN))");
	  	servicePHelper.addStatement("errExtraInfo = mal.NewString(e.Error())");
	  	servicePHelper.addStatement("errIsAbstract = false");
	  	servicePHelper.closeBlock();
	  	
	  	//   // encode parameters
	  	//   if body.EncodeParameter(errCode) != nil {
	  	//     // TODO dont know what
	  	//   } else if body.EncodeLastParameter(errExtraInfo, errIsAbstract) != nil {
	  	//     // TODO dont know what
	  	//   }
	  	servicePHelper.addSingleLineComment("encode parameters");
	  	servicePHelper.addStatement("if body.EncodeParameter(errCode) != nil {", 1);
	  	servicePHelper.addStatement("return errors.New(\"Fatal error in error handling code\")", -1);
	  	servicePHelper.addStatement("} else if body.EncodeLastParameter(errExtraInfo, errIsAbstract) != nil {", 1);
	  	servicePHelper.addStatement("return errors.New(\"Fatal error in error handling code\")");
	  	servicePHelper.closeBlock();

	  	//   }
	  	//   outElem_extraInfo, err := resp.DecodeLastParameter(nullValue, errIsAbstract)
	  	//   if err != nil {
	  	//     return err
	  	//   }
	  	//   return malapi.NewMalError(*outParam_code, outElem_extraInfo)
  		serviceCContent.addStatement("}");
  		serviceCContent.addStatement("outElem_extraInfo, err := resp.DecodeLastParameter(nullValue, errIsAbstract)");
  		serviceCContent.addStatement("if err != nil {", 1);
  		serviceCContent.addStatement("return err");
  		serviceCContent.closeBlock();
  		serviceCContent.addStatement("return malapi.NewMalError(*outParam_code, outElem_extraInfo)");
  		serviceCContent.closeFunctionBody();
	  	
	  	// send the message
	  	//   // interaction call
  		//   var err error
	    //   err = transaction.<errOp>(body, true)
	    //   if err != nil {
	    //     return err
	    //   }
	    // return nil
	  	servicePHelper.addNewLine();
	  	servicePHelper.addSingleLineComment("interaction call");
	  	servicePHelper.addStatement("var err error");
	  	switch (opContext.operation.getPattern()) {
	  	case SUBMIT_OP:
		  	servicePHelper.addStatement("err = transaction.Ack(body, true)");
		  	break;
	  	case REQUEST_OP:
		  	servicePHelper.addStatement("err = transaction.Reply(body, true)");
	  		break;
	  	case INVOKE_OP:
		  	servicePHelper.addStatement("if !receiver.acked {", 1);
		  	servicePHelper.addStatement("err = transaction.Ack(body, true)", -1);
		  	servicePHelper.addStatement("} else {", 1);
		  	servicePHelper.addStatement("err = transaction.Reply(body, true)");
		  	servicePHelper.closeBlock();
	  		break;
	  	case PROGRESS_OP:
		  	servicePHelper.addStatement("if !receiver.acked {", 1);
		  	servicePHelper.addStatement("err = transaction.Ack(body, true)", -1);
		  	servicePHelper.addStatement("} else {", 1);
		  	servicePHelper.addStatement("err = transaction.Update(body, true)");
		  	servicePHelper.closeBlock();
	  		break;
	  	default:
	  		throw new IllegalStateException("unexpected pattern in processOpErrors: " + opContext.operation.getPattern());
	  	}
	  	servicePHelper.addStatement("if err != nil {", 1);
	  	servicePHelper.addStatement("return err");
	  	servicePHelper.closeBlock();
	  	servicePHelper.addStatement("return nil");
	  	servicePHelper.closeFunctionBody();
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
	  	// TODO clean with what is actually used by the go generator
	  	// this function is currently called in the go generator with a null file parameter
	  	// type dependencies are handled in another way
	    CompositeField ele;
	
	    String typeName = elementType.getName().substring(0, 1).toUpperCase() + elementType.getName().substring(1);
			// the field name is given an uppercase first letter so that it is publicly accessible
			// by the way it makes the name "type" an acceptable field name as "Type"
	    String goFieldName = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
	
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
	
	    	ele = new CompositeField(fqTypeName, elementType, goFieldName, elementType.isList(), canBeNull, false, encCall, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, newCall, comment);
	    }
	    else
	    {
	      if (isAttributeType(elementType))
	      {
	        AttributeTypeDetails details = getAttributeDetails(elementType);
	        String fqTypeName = createElementType((LanguageWriter) file, elementType, isStructure);
	        ele = new CompositeField(details.getTargetType(), elementType, goFieldName, elementType.isList(), canBeNull, false, typeName, "", typeName, false, "new " + fqTypeName + "()", comment);
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
	          ele = new CompositeField(fqTypeName, elementType, goFieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, firstEle, comment);
	        }
	        else if (StdStrings.ATTRIBUTE.equals(typeName))
	        {
	          ele = new CompositeField(fqTypeName, elementType, goFieldName, elementType.isList(), canBeNull, false, StdStrings.ATTRIBUTE, "(" + fqTypeName + ") ", StdStrings.ATTRIBUTE, false, "", comment);
	        }
	        else if (StdStrings.ELEMENT.equals(typeName))
	        {
	          ele = new CompositeField(fqTypeName, elementType, goFieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, false, "", comment);
	        }
	        else
	        {
	          ele = new CompositeField(fqTypeName, elementType, goFieldName, elementType.isList(), canBeNull, false, StdStrings.ELEMENT, "(" + fqTypeName + ") ", StdStrings.ELEMENT, true, "new " + fqTypeName + "()", comment);
	        }
	      }
	    }
	
	    return ele;
	  }
	
	  protected void createEnumeration(File folder, AreaContext areaContext, ServiceContext serviceContext, EnumerationType enumeration) throws IOException
	  {
	  	String comment;
	    String enumName = enumeration.getName();

	    int enumSize = enumeration.getItem().size();
	    if (enumSize == 0) {
	    	throw new IllegalStateException("empty enumeration " + enumName);
	    }
	    
	    AreaType area = areaContext.area;
	    ServiceType service = null;
	    if (serviceContext != null)
	    {
	    	service = serviceContext.summary.getService();
	    }
	    String malEnumName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + enumName;
	
	    getLog().info("Creating enumeration " + malEnumName);

	    EnumerationContext enumCtxt = new EnumerationContext(areaContext, serviceContext, enumeration, folder);
	    enumCtxt.writer.init();
	    EnumerationWriter enumContent = enumCtxt.writer;
	    StatementWriter enumContentW = enumCtxt.writer.out;
	    StatementWriter nvalTableW = enumCtxt.writer.nvalTableW;
	    GoFileWriter nvalTable = enumCtxt.writer.nvalTable;

	    // define the enumeration
	    comment = "Defines " + enumCtxt.enumName + " type";
	    enumContent.addNewLine();
	    enumContent.addSingleLineComment(comment);
	    enumContent.addNewLine();
	    
	    // define the type
	    // type <enum> uint32
	    enumContent.addIndent();
	    enumContentW
	    	.append("type ")
	    	.append(enumCtxt.enumName)
	    	.append(" uint32");
	    enumContent.addNewLine();
	    
	    // prepare the conversion table OVAL->NVAL
	    comment = "Conversion table OVAL->NVAL";
	    nvalTable.addSingleLineComment(comment);
	    nvalTable.addStatement("var nvalTable = []uint32 {", 1);
	    
	    // open the constant definition block
	    enumContent.openConstBlock();
	    for (int i = 0; i < enumSize; i++)
	    {
	    	// we keep the default value set by the go language, optimal for encoding and conform to malbinary encoding
	    	//   <ENUM NAME>_<VALUE NAME>_OVAL [= iota]
	    	Item item = enumeration.getItem().get(i);
	    	String valueName = item.getValue().toUpperCase();
		    enumContent.addIndent();
		    enumContentW
		    	.append(enumCtxt.enumNameU)
		    	.append("_")
		    	.append(valueName)
		    	.append("_OVAL");
		    if (i == 0) {
		    	enumContentW.append(" = iota");
		    }
		    enumContent.addNewLine();
	    	//   <ENUM NAME>_<VALUE NAME>_NVAL = <numeric value>
		    enumContent.addIndent();
		    enumContentW
		    	.append(enumCtxt.enumNameU)
		    	.append("_")
		    	.append(valueName)
		    	.append("_NVAL = ")
		    	.append(Long.toString(item.getNvalue()));
		    enumContent.addNewLine();
		    // add the constant to the nvalTable
		    nvalTable.addIndent();
		    nvalTableW
	    		.append(enumCtxt.enumNameU)
	    		.append("_")
	    		.append(valueName)
		    	.append("_NVAL,");
		    nvalTable.addNewLine();
	    }
	    enumContent.closeConstBlock();
	    nvalTable.closeBlock();
	    
	    enumContent.addNewLine();
	    enumContent.addStatements(nvalTableW);
	    
	    // follow with the variable block to define simili constants
	    enumContent.addNewLine();
	    enumContent.openVarBlock();
	    for (int i = 0; i < enumSize; i++)
	    {
	    	//   <ENUM NAME>_<VALUE NAME> = <Enum>(<ENUM NAME>_<VALUE NAME>_OVAL)
	    	Item item = enumeration.getItem().get(i);
		    enumContent.addIndent();
		    enumContentW
		    	.append(enumCtxt.enumNameU)
		    	.append("_")
		    	.append(item.getValue().toUpperCase())
		    	.append(" = ")
		    	.append(enumName)
		    	.append('(')
		    	.append(enumCtxt.enumNameU)
		    	.append("_")
		    	.append(item.getValue().toUpperCase())
		    	.append("_OVAL")
		    	.append(')');
		    enumContent.addNewLine();
	    }
	    enumContent.closeVarBlock();
	    enumContent.addNewLine();
	
	    // define the enumeration null value
	    enumContent.addIndent();
	    enumContentW
	    	.append("var Null")
	    	.append(enumCtxt.enumName)
	    	.append(" *")
	    	.append(enumCtxt.enumName)
	    	.append(" = nil");
	    enumContent.addNewLine();

	    // define a default constructor
	    addEnumerationConstructor(enumCtxt);
	    
	    // implement the mal.Element API
	    addEnumerationMalElementAPI(enumCtxt);
	    
	    // implement the standard encoding API
	    addEnumerationEncodingFunctions(enumCtxt);

	    // create the list type associated to the enumeration
	    createEnumerationList(enumCtxt);
	    
	    enumCtxt.writer.close();
	  }

	  private void addEnumerationConstructor(EnumerationContext enumCtxt) throws IOException
	  {
	  	EnumerationWriter writer = enumCtxt.writer;
	  	String enumName = enumCtxt.enumName;
	  	
	  	// func New<Enum>(i uint32) *<Enum> {
	  	//   var val <Enum> = <Enum>(i)
	  	//   return &val
	  	// }
	  	writer.openFunction("New" + enumName, null);
	  	writer.addFunctionParameter("uint32", "i", true);
	  	writer.openFunctionBody(new String[] {"*" + enumName});
	  	writer.addIndent();
	  	writer.out
	  		.append("var val ")
	  		.append(enumName)
	  		.append(" = ")
	  		.append(enumName)
	  		.append("(i)");
	  	writer.addNewLine();
	  	writer.addStatement("return &val");
	  	writer.closeFunctionBody();
	  }
	  
	  private void addEnumerationEncodingFunctions(EnumerationContext enumCtxt) throws IOException
	  {
	  	EnumerationWriter writer = enumCtxt.writer;
	  	StatementWriter swriter = enumCtxt.writer.out;
	  	String enumName = enumCtxt.enumName;

  		if (generateTransportMalbinary || generateTransportMalsplitbinary)
  		{
  	    // fill in the enumTypesMBSize table
  	    TypeReference enumTypeRef = new TypeReference();
  	    enumTypeRef.setArea(enumCtxt.areaContext.area.getName());
  	    if (!enumCtxt.isAreaType())
  	    {
  	    	enumTypeRef.setService(enumCtxt.serviceContext.summary.getService().getName());
  	    }
  	    enumTypeRef.setName(enumCtxt.enumeration.getName());
  	    MalbinaryEnumSize mbSize = getEnumTypeMBSize(enumTypeRef, enumCtxt.enumeration);
  			String codedMalType = null;
  			String codedNativeType = null;
  			switch (mbSize) {
  			case MB_SMALL:
  				codedMalType = "UOctet";
  				codedNativeType = "uint8";
  				break;
  			case MB_MEDIUM:
  				codedMalType = "UShort";
  				codedNativeType = "uint16";
  				break;
  			case MB_LARGE:
  				codedMalType = "UInteger";
  				codedNativeType = "uint32";
  				break;
  			}
  	    
  			String comment = "Encodes this element using the supplied encoder.";
  			writer.addNewLine();
  			writer.addSingleLineComment(comment);
  			comment = "@param encoder The encoder to use, must not be null.";
  			writer.addSingleLineComment(comment);
  			// func (<receiver> *<Enum>) Encode(encoder mal.Encoder) error {
  			writer.openFunction("Encode", "*" + enumName);
  			writer.addFunctionParameter("mal.Encoder", "encoder", true);
  			writer.openFunctionBody(new String[] {"error"});
  			//   specific := encoder.LookupSpecific(<ENUM>_SHORT_FORM)
  			//   if specific != nil {
  			//     return specific(<receiver>, encoder)
  			//   }
  			writer.addIndent();
  			swriter.append("specific := encoder.LookupSpecific(")
  				.append(enumCtxt.enumNameU)
  				.append("_SHORT_FORM)");
  			writer.addNewLine();
  			writer.addStatement("if specific != nil {", 1);
  			writer.addIndent();
  			swriter.append("return specific(")
  				.append(GoFileWriter.FUNC_RECEIVER)
  				.append(", encoder)");
  			writer.addNewLine();
  			writer.closeBlock();
  			writer.addNewLine();
  			//   value := mal.New[UOCtet|UShort|UInteger](uint[8|16|32](uint32(*<receiver>)))
  			//   return encoder.Encode[UOCtet|UShort|UInteger](value)
  			// }
				writer.addIndent();
				swriter
					.append("value := mal.New")
					.append(codedMalType)
					.append("(")
					.append(codedNativeType);
				if (mbSize != MalbinaryEnumSize.MB_LARGE)
					swriter.append("(uint32");
				swriter.append("(*")
					.append(GoFileWriter.FUNC_RECEIVER)
					.append(')');
				if (mbSize != MalbinaryEnumSize.MB_LARGE)
					swriter.append(')');
				swriter.append(')');
				writer.addNewLine();
				writer.addIndent();
				swriter
					.append("return encoder.Encode")
					.append(codedMalType)
					.append("(value)");
				writer.addNewLine();
  			writer.closeFunctionBody();
  			writer.addNewLine();

  			comment = "Decodes an instance of this element type using the supplied decoder.";
  			writer.addNewLine();
  			writer.addSingleLineComment(comment);
  			comment = "@param decoder The decoder to use, must not be null.";
  			writer.addSingleLineComment(comment);
  			comment = "@return the decoded instance, may be not the same instance as this Element.";
  			writer.addSingleLineComment(comment);
  			// func (<receiver> *<composite>) Decode(decoder mal.Decoder) (mal.Element, error) {
  			writer.openFunction("Decode", "*" + enumName);
  			writer.addFunctionParameter("mal.Decoder", "decoder", true);
  			writer.openFunctionBody(new String[] {"mal.Element", "error"});
  			//   specific := decoder.LookupSpecific(<ENUM>_SHORT_FORM)
  			//   if specific != nil {
  			//     return specific(decoder)
  			//   }
  			writer.addIndent();
  			swriter
  				.append("specific := decoder.LookupSpecific(")
  				.append(enumCtxt.enumNameU)
  				.append("_SHORT_FORM)");
  			writer.addNewLine();
  			writer.addStatement("if specific != nil {", 1);
  			writer.addStatement("return specific(decoder)");
  			writer.closeBlock();
  			writer.addNewLine();
  			//   elem, err := decoder.Decode[UOCtet|UShort|UInteger]()
  			//   if err != nil {
  			//     return <receiver>.Null(), err
  			//   }
  			//   value := <Enum>(uint32(uint[8|16|32](*elem)))
  			//   return &value, nil
  			// }
  			writer.addIndent();
  			swriter
  				.append("elem, err := decoder.Decode")
  				.append(codedMalType)
  				.append("()");
  			writer.addNewLine();
  			writer.addStatement("if err != nil {", 1);
  			writer.addIndent();
  			swriter
  				.append("return ")
  				.append(GoFileWriter.FUNC_RECEIVER)
  				.append(".Null(), err");
  			writer.addNewLine();
  			writer.closeBlock();
  			writer.addIndent();
  			swriter
  				.append("value := ")
  				.append(enumName);
				if (mbSize != MalbinaryEnumSize.MB_LARGE)
					swriter.append("(uint32");
  			swriter
  				.append('(')
  				.append(codedNativeType)
  				.append("(*elem))");
				if (mbSize != MalbinaryEnumSize.MB_LARGE)
					swriter.append(')');
				writer.addNewLine();
				writer.addStatement("return &value, nil");
				writer.closeFunctionBody();
  			writer.addNewLine();
  		}
	  }
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	
	  	

	  private void addEnumerationMalElementAPI(EnumerationContext enumCtxt) throws IOException
	  {
	  	EnumerationWriter writer = enumCtxt.writer;
	  	StatementWriter swriter = enumCtxt.writer.out;
	  	String enumName = enumCtxt.enumName;

	  	String comment = "================================================================================";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	comment = "Defines " + enumName + " type as a MAL Element";
	  	writer.addSingleLineComment(comment);
	  	writer.addNewLine();

	  	// const <ENUM>_TYPE_SHORT_FORM mal.Integer = 0x<type short form>
	  	// const <ENUM>_SHORT_FORM mal.Long = 0x<short form>
	  	int typeShortForm = (int) enumCtxt.enumeration.getShortFormPart();
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
	  		.append(enumCtxt.enumNameU)
	  		.append("_TYPE_SHORT_FORM mal.Integer = ")
	  		.append(Integer.toString(typeShortForm));
	  	writer.addNewLine();
	  	long absoluteShortForm = getAbsoluteShortForm(
	  			enumCtxt.areaContext.area.getNumber(),
	  			(enumCtxt.isAreaType() ? 0 : enumCtxt.serviceContext.summary.getService().getNumber()),
	  			enumCtxt.areaContext.area.getVersion(),
	  			typeShortForm);
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
	  		.append(enumCtxt.enumNameU)
	  		.append("_SHORT_FORM mal.Long = 0x")
	  		.append(Long.toHexString(absoluteShortForm));
	  	writer.addNewLine();
	
	  	comment = "Registers " + enumName + " type for polymorphism handling";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func init() {
	  	//   mal.RegisterMALElement(<ENUM>_SHORT_FORM, Null<Enum>)
	  	// }
	  	writer.openFunction("init", null);
	  	writer.openFunctionBody(null);
	  	writer.addIndent();
	  	swriter.append("mal.RegisterMALElement(")
  			.append(enumCtxt.enumNameU)
  			.append("_SHORT_FORM, Null")
  			.append(enumName)
  			.append(")");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the absolute short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Enum>) GetShortForm() mal.Long {
	  	//   return <ENUM>_SHORT_FORM
	  	// }
	  	writer.openFunction("GetShortForm", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.Long"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(enumCtxt.enumNameU)
				.append("_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Enum>) GetAreaNumber() mal.UShort {
	  	//   return [<area>.]AREA_NUMBER
	  	// }
	  	writer.openFunction("GetAreaNumber", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (! enumCtxt.isAreaType()) {
	  		swriter
	  			.append(enumCtxt.areaContext.areaNameL)
	  			.append('.');
	  	}
	  	swriter.append("AREA_NUMBER");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the version of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Enum>) GetAreaVersion() mal.UOctet {
	  	//   return [<area>.]AREA_VERSION
	  	// }
	  	writer.openFunction("GetAreaVersion", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.UOctet"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (! enumCtxt.isAreaType()) {
	  		swriter
	  			.append(enumCtxt.areaContext.areaNameL)
	  			.append('.');
	  	}
	  	swriter.append("AREA_VERSION");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the service this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Enum>) GetServiceNumber() mal.UShort {
	  	//   return [mal.NULL_]SERVICE_NUMBER
	  	// }
	  	writer.openFunction("GetServiceNumber", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	if (enumCtxt.isAreaType()) {
	  		writer.addStatement("return mal.NULL_SERVICE_NUMBER");
	  	} else {
	  		writer.addStatement("return SERVICE_NUMBER");
	  	}
	  	writer.closeFunctionBody();

	  	comment = "Returns the relative short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Enum>) GetTypeShortForm() mal.Integer {
	  	//   return <COMPOSITE>_TYPE_SHORT_FORM
	  	// }
	  	writer.openFunction("GetTypeShortForm", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.Integer"});
	  	writer.addIndent();
	  	swriter
	  		.append("return ")
				.append(enumCtxt.enumNameU)
				.append("_TYPE_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	comment = "Allows the creation of an element in a generic way, i.e., using the MAL Element polymorphism.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (<receiver> *<Enum>) CreateElement() mal.Element {
	  	//   return New<Enum>(0)
	  	// }
	  	writer.openFunction("CreateElement", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return New")
				.append(enumName)
				.append("(0)");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// func (<receiver> *<Enum>) IsNull() bool {
	  	//   return <receiver> == nil
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("IsNull", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"bool"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(GoFileWriter.FUNC_RECEIVER)
				.append(" == nil");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	// func (*<Enum>) Null() mal.Element {
	  	//   return Null<Enum>
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("Null", "*" + enumName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return Null")
				.append(enumName);
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// TODO Stringer interface ?
	  }
	  
	  protected void createEnumerationList(EnumerationContext enumCtxt) throws IOException
	  {
	    AreaType area = enumCtxt.areaContext.area;
	    ServiceType service = null;
	    if (enumCtxt.serviceContext != null) {
	    	service = enumCtxt.serviceContext.summary.getService();
	    }
	    String malEnumName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + enumCtxt.enumName;
	
	    getLog().info("Creating enumeration list " + malEnumName);

      // create the writer structures
	    EnumerationListWriter writer = new EnumerationListWriter(enumCtxt);	
	    writer.init();
	    createGenericTypeList(writer);
	    writer.close();
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
	    AreaType area = areaContext.area;
	    ServiceType service = null;
	    if (serviceContext != null) {
	    	service = serviceContext.summary.getService();
	    }
	    String malCompName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + composite.getName();
	
	    getLog().info("Creating composite " + malCompName);
	
	    boolean abstractComposite = (null == composite.getShortFormPart());
	    if (abstractComposite) {
	    	AbstractCompositeWriter writer = new AbstractCompositeWriter(folder, areaContext, serviceContext, composite);
	    	writer.init();
	    	writer.close();
	    	return;
	    }
	    
	    CompositeContext compCtxt = new CompositeContext(areaContext, serviceContext, composite, folder);
	    // create the list of parent abstract composites
    	List<TypeReference> compositeHierarchy = new ArrayList<>();
      CompositeType theType = compositeTypesMap.get(new TypeKey(area.getName(), (service == null ? null : service.getName()), composite.getName()));
      while (theType != null) {
      	if (theType.getExtends() == null) {
      		theType = null;
      	} else {
      		TypeReference superType = theType.getExtends().getType();
      		if (StdStrings.COMPOSITE.equals(superType.getName())) {
      			theType = null;
      		} else {
      			compositeHierarchy.add(superType);
      			compCtxt.reqTypes.add(superType);
      			theType = compositeTypesMap.get(new TypeKey(superType));
      		}
      	}
      }
	
	    compCtxt.writer.init();
	    
	    // generate all code related to the composite fields
	    // and define the structure in the <composite>.c file
	    processCompFields(compCtxt);
	
	    // define the composite null value
	    addCompositeNullValue(compCtxt);
	    
	    // declare and define the composite constructor
	    addCompositeConstructor(compCtxt);
	    
	    // implement the mal.Composite API
	    addCompositeMalCompositeAPI(compCtxt);
	    
	    // implement the mal.Element API
	    addCompositeMalElementAPI(compCtxt);
	    
	    // implement the parent abstract composite marker interfaces
	    addCompositeAbstractAPIs(compCtxt, compositeHierarchy);
	    
	    // create the list type associated to the composite
	    createCompositeList(compCtxt, compositeHierarchy);
	    
	    compCtxt.writer.close();
	  }
	  
	  protected void createGenericTypeList(TypeListWriter writer) throws IOException
	  {
	  	String baseTypeName = writer.getBaseTypeName();
	  	String typeName = baseTypeName + "List";
	  	
	  	// define the type
	  	String comment = "Defines " + typeName + " type";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	writer.addNewLine();

	    // declare the structure
	  	// type <TypeName> []*<BaseTypeName>
	  	writer.addIndent();
	  	writer.out
	  		.append("type ")
	  		.append(typeName)
	  		.append(" []*")
	  		.append(baseTypeName);
	  	writer.addNewLine();
	  	writer.addNewLine();

	    // define the composite null value
	  	// var Null<TypeName> *<TypeName> = nil
	  	writer.addIndent();
	  	writer.out
	  		.append("var Null")
	  		.append(typeName)
	  		.append(" *")
	  		.append(typeName)
	  		.append(" = nil");
	  	writer.addNewLine();
	  	writer.addNewLine();
	    
	    // declare and define the composite constructor
	    addTypeListConstructor(writer, typeName, baseTypeName);

	    // implement the ElementList API
	    addTypeListElementListAPI(writer, baseTypeName);
	    
	    // implement the mal.Composite API
	    addTypeListMalCompositeAPI(writer, typeName);

	    // implement the mal.Element API
	    addTypeListMalElementAPI(writer, baseTypeName);

	    // implement the standard encoding API
	    addTypeListEncodingFunctions(writer, baseTypeName);
	    
	  }
	  
	  protected void addTypeListConstructor(TypeListWriter writer, String typeName, String baseTypeName) throws IOException
	  {
	  	// func New<TypeName>(size int) *<TypeName> {
	  	//   var list <TypeName> = <TypeName>(make([]*<BaseTypeName>, size))
	  	//   return &list
	  	// }
	  	writer.openFunction("New" + typeName, null);
	  	writer.addFunctionParameter("int", "size", true);
	  	writer.openFunctionBody(new String[] {"*" + typeName});
	  	writer.addIndent();
	  	writer.out
	  		.append("var list ")
	  		.append(typeName)
	  		.append(" = ")
	  		.append(typeName)
	  		.append("(make([]*")
	  		.append(baseTypeName)
	  		.append(", size))");
	  	writer.addNewLine();
	  	writer.addStatement("return &list");
	  	writer.closeFunctionBody();
	  }

	  private void addTypeListElementListAPI(TypeListWriter writer, String baseTypeName) throws IOException
	  {
	  	StatementWriter swriter = writer.out;
	  	String typeName = baseTypeName + "List";
	  	
	  	String comment1 = "================================================================================";
	  	String comment2 = "Defines " + typeName + " type as an ElementList";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment1);
	  	writer.addSingleLineComment(comment2);
	  	writer.addNewLine();
	  	
	  	// func (<receiver> *<TypeName>) Size() int {
	  	//   if <receiver> != nil {
	  	//     return len(*<receiver>)
	  	//   }
	  	//   return -1
	  	// }
	  	writer.openFunction("Size", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"int"});
	  	writer.addIndent();
	  	swriter
	  		.append("if ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(" != nil {");
	  	writer.addNewLine(1);
	  	writer.addIndent();
	  	swriter
	  		.append("return len(*")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(")");
	  	writer.addNewLine();
	  	writer.closeBlock();
	  	writer.addStatement("return -1");
	  	writer.closeFunctionBody();
	  	writer.addNewLine();
	  	
	  	// func (<receiver> *<TypeName>) GetElementAt(i int) mal.Element {
	  	//   if <receiver> == nil || i >= <receiver>.Size() {
	  	//     return nil
	  	//   }
	  	//   return (*<receiver>)[i]
	  	// }
	  	writer.openFunction("GetElementAt", "*" + typeName);
	  	writer.addFunctionParameter("int", "i", true);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter
	  		.append("if ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(" == nil || i >= ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(".Size() {");
	  	writer.addNewLine(1);
	  	writer.addStatement("return nil");
	  	writer.closeBlock();
	  	writer.addIndent();
	  	swriter
	  		.append("return (*")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(")[i]");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	writer.addNewLine();
	  	
	  	// func (<receiver> *<TypeName>) AppendElement(element mal.Element) {
	  	//   if <receiver> != nil {
	  	//     *<receiver> = append(*<receiver>, element.(*<BaseTypeName>))
	  	//   }
	  	// }
	  	writer.openFunction("AppendElement", "*" + typeName);
	  	writer.addFunctionParameter("mal.Element", "element", true);
	  	writer.openFunctionBody(null);
	  	writer.addIndent();
	  	swriter
	  		.append("if ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(" != nil {");
	  	writer.addNewLine(1);
	  	writer.addIndent();
	  	swriter
	  		.append("*")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(" = append(*")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(", element.(*")
	  		.append(baseTypeName)
	  		.append("))");
	  	writer.addNewLine();
	  	writer.closeBlock();
	  	writer.closeFunctionBody();
	  }
	  
	  protected void addTypeListMalCompositeAPI(TypeListWriter writer, String typeName) throws IOException
	  {
	  	String comment1 = "================================================================================";
	  	String comment2 = "Defines " + typeName + " type as a MAL Composite";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment1);
	  	writer.addSingleLineComment(comment2);
	  	writer.addNewLine();
	  	
	  	// func (receiver *<TypeName>) Composite() mal.Composite {
	  	//     return receiver
	  	// }
	  	writer.openFunction("Composite", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.Composite"});
	  	writer.addStatement("return " + GoFileWriter.FUNC_RECEIVER);
	  	writer.closeFunctionBody();
	  }

	  private void addTypeListMalElementAPI(TypeListWriter writer, String baseTypeName) throws IOException
	  {
	  	StatementWriter swriter = writer.out;
	  	String typeName = baseTypeName + "List";
	  	// TODO pourquoi un _ additionnel ?
	  	String typeNameU = (baseTypeName + "_List").toUpperCase();

	  	String comment = "================================================================================";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	comment = "Defines " + typeName + " type as a MAL Element";
	  	writer.addSingleLineComment(comment);
	  	writer.addNewLine();
	  	
	  	// const <TYPE NAME>_TYPE_SHORT_FORM mal.Integer = 0x<type short form>
	  	// const <TYPE NAME>_SHORT_FORM mal.Long = 0x<short form>
	  	int typeShortForm = writer.getTypeShortForm();
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
	  		.append(typeNameU)
	  		.append("_TYPE_SHORT_FORM mal.Integer = ")
	  		.append(Integer.toString(typeShortForm));
	  	writer.addNewLine();
	  	long absoluteShortForm = writer.getShortForm();
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
  		.append(typeNameU)
  		.append("_SHORT_FORM mal.Long = 0x")
  		.append(Long.toHexString(absoluteShortForm));
	  	writer.addNewLine();
	
	  	comment = "Registers " + typeName + " type for polymorphism handling";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func init() {
	  	//   mal.RegisterMALElement(<TYPE NAME>_SHORT_FORM, Null<TypeName>)
	  	// }
	  	writer.openFunction("init", null);
	  	writer.openFunctionBody(null);
	  	writer.addIndent();
	  	swriter.append("mal.RegisterMALElement(")
  			.append(typeNameU)
  			.append("_SHORT_FORM, Null")
  			.append(typeName)
  			.append(")");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the absolute short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<TypeName>) GetShortForm() mal.Long {
	  	//   return <TYPE NAME>_SHORT_FORM
	  	// }
	  	writer.openFunction("GetShortForm", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.Long"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(typeNameU)
				.append("_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<TypeName>) GetAreaNumber() mal.UShort {
	  	//   return [<area>.]AREA_NUMBER
	  	// }
	  	writer.openFunction("GetAreaNumber", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (!writer.isAreaType()) {
	  		swriter
	  			.append(writer.getAreaNameL())
	  			.append('.');
	  	}
	  	swriter.append("AREA_NUMBER");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the version of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<TypeName>) GetAreaVersion() mal.UOctet {
	  	//   return [<area>.]AREA_VERSION
	  	// }
	  	writer.openFunction("GetAreaVersion", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.UOctet"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (!writer.isAreaType()) {
	  		swriter
  				.append(writer.getAreaNameL())
	  			.append('.');
	  	}
	  	swriter.append("AREA_VERSION");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the service this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<TypeName>) GetServiceNumber() mal.UShort {
	  	//   return [mal.NULL_]SERVICE_NUMBER
	  	// }
	  	writer.openFunction("GetServiceNumber", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	if (writer.isAreaType()) {
	  		writer.addStatement("return mal.NULL_SERVICE_NUMBER");
	  	} else {
	  		writer.addStatement("return SERVICE_NUMBER");
	  	}
	  	writer.closeFunctionBody();

	  	comment = "Returns the relative short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<TypeName>) GetTypeShortForm() mal.Integer {
	  	//   return <TYPE NAME>_TYPE_SHORT_FORM
	  	// }
	  	writer.openFunction("GetTypeShortForm", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.Integer"});
	  	writer.addIndent();
	  	swriter
	  		.append("return ")
				.append(typeNameU)
				.append("_TYPE_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	comment = "Allows the creation of an element in a generic way, i.e., using the MAL Element polymorphism.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (<receiver> *<TypeName>) CreateElement() mal.Element {
	  	//   return New<TypeName>(0)
	  	// }
	  	writer.openFunction("CreateElement", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return New")
				.append(typeName)
				.append("(0)");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// func (<receiver> *<TypeName>) IsNull() bool {
	  	//   return <receiver> == nil
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("IsNull", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"bool"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(GoFileWriter.FUNC_RECEIVER)
				.append(" == nil");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	// func (*<TypeName>) Null() mal.Element {
	  	//   return Null<TypeName>
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("Null", "*" + typeName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return Null")
				.append(typeName);
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// TODO Stringer interface ?
	  }

	  private void addTypeListEncodingFunctions(TypeListWriter writer, String baseTypeName) throws IOException
	  {
	  	StatementWriter swriter = writer.out;
	  	String typeName = baseTypeName + "List";
	  	// TODO pourquoi un _ additionnel ?
	  	String typeNameU = (baseTypeName + "_List").toUpperCase();

  		if (generateTransportMalbinary || generateTransportMalsplitbinary)
  		{
  			String comment = "Encodes this element using the supplied encoder.";
  			writer.addNewLine();
  			writer.addSingleLineComment(comment);
  			comment = "@param encoder The encoder to use, must not be null.";
  			writer.addSingleLineComment(comment);
  			// func (<receiver> *<TypeName>) Encode(encoder mal.Encoder) error {
  			writer.openFunction("Encode", "*" + typeName);
  			writer.addFunctionParameter("mal.Encoder", "encoder", true);
  			writer.openFunctionBody(new String[] {"error"});
  			//   specific := encoder.LookupSpecific(<TYPE NAME>_SHORT_FORM)
  			//   if specific != nil {
  			//     return specific(<receiver>, encoder)
  			//   }
  			writer.addIndent();
  			swriter.append("specific := encoder.LookupSpecific(")
  				.append(typeNameU)
  				.append("_SHORT_FORM)");
  			writer.addNewLine();
  			writer.addStatement("if specific != nil {", 1);
  			writer.addIndent();
  			swriter.append("return specific(")
  				.append(GoFileWriter.FUNC_RECEIVER)
  				.append(", encoder)");
  			writer.addNewLine();
  			writer.closeBlock();
  			writer.addNewLine();
  			//   err := encoder.EncodeUInteger(mal.NewUInteger(uint32(len([]*<BaseTypeName>(*<receiver>)))))
  			//   if err != nil {
  			//     return err
  			//   }
  			writer.addIndent();
  			swriter
  				.append("err := encoder.EncodeUInteger(mal.NewUInteger(uint32(len([]*")
  				.append(baseTypeName)
  				.append("(*")
  				.append(GoFileWriter.FUNC_RECEIVER)
  				.append(")))))");
  			writer.addNewLine();
  			writer.addStatement("if err != nil {", 1);
  			writer.addStatement("return err");
  			writer.closeBlock();
  			//   for _, e := range []*<BaseTypeName>(*<receiver>) {
  			//     encoder.EncodeNullableElement(e)
  			//   }
  			//   return nil
	  		// }
  			writer.addIndent();
  			swriter
  				.append("for _, e := range []*")
  				.append(baseTypeName)
  				.append("(*")
  				.append(GoFileWriter.FUNC_RECEIVER)
  				.append(") {");
  			writer.addNewLine(1);
  			writer.addStatement("encoder.EncodeNullableElement(e)");
  			writer.closeBlock();
  			writer.addStatement("return nil");
  			writer.closeFunctionBody();

  			comment = "Decodes an instance of this element type using the supplied decoder.";
  			writer.addNewLine();
  			writer.addSingleLineComment(comment);
  			comment = "@param decoder The decoder to use, must not be null.";
  			writer.addSingleLineComment(comment);
  			comment = "@return the decoded instance, may be not the same instance as this Element.";
  			writer.addSingleLineComment(comment);
  			// func (<receiver> *<TypeName>) Decode(decoder mal.Decoder) (mal.Element, error) {
  			writer.openFunction("Decode", "*" + typeName);
  			writer.addFunctionParameter("mal.Decoder", "decoder", true);
  			writer.openFunctionBody(new String[] {"mal.Element", "error"});
  			//   specific := decoder.LookupSpecific(<TYPE NAME>_SHORT_FORM)
  			//   if specific != nil {
  			//     return specific(decoder)
  			//   }
  			writer.addIndent();
  			swriter
  				.append("specific := decoder.LookupSpecific(")
  				.append(typeNameU)
  				.append("_SHORT_FORM)");
  			writer.addNewLine();
  			writer.addStatement("if specific != nil {", 1);
  			writer.addStatement("return specific(decoder)");
  			writer.closeBlock();
  			writer.addNewLine();
  			//   size, err := decoder.DecodeUInteger()
  			//   if err != nil {
  			//     return nil, err
  			//   }
  			writer.addStatement("size, err := decoder.DecodeUInteger()");
  			writer.addStatement("if err != nil {", 1);
  			writer.addStatement("return nil, err");
  			writer.closeBlock();
  			//   list := <TypeName>(make([]*<BaseTypeName>, int(*size)))
  			//   for i := 0; i < len(list); i++ {
  			//     elem, err := decoder.DecodeNullableElement(Null<BaseTypeName>)
  			//     if err != nil {
  			//       return nil, err
  			//     }
  			//     list[i] = elem.(*ObjectKey)
  			//   }
  			//   return &list, nil
  			// }
  			writer.addIndent();
  			swriter
  				.append("list := ")
  				.append(typeName)
  				.append("(make([]*")
  				.append(baseTypeName)
  				.append(", int(*size)))");
  			writer.addNewLine();
  			writer.addStatement("for i := 0; i < len(list); i++ {", 1);
  			writer.addIndent();
  			swriter
  				.append("elem, err := decoder.DecodeNullableElement(Null")
  				.append(baseTypeName)
  				.append(")");
  			writer.addNewLine();
  			writer.addStatement("if err != nil {", 1);
  			writer.addStatement("return nil, err");
  			writer.closeBlock();
  			writer.addIndent();
  			swriter
  				.append("list[i] = elem.(*")
  				.append(baseTypeName)
  				.append(")");
  			writer.addNewLine();
  			writer.closeBlock();
  			writer.addStatement("return &list, nil");
  			writer.closeFunctionBody();
  		}
	  }
	  
	  protected void createCompositeList(CompositeContext compCtxt, List<TypeReference> compositeHierarchy) throws IOException
	  {
	    AreaType area = compCtxt.areaContext.area;
	    ServiceType service = null;
	    if (compCtxt.serviceContext != null) {
	    	service = compCtxt.serviceContext.summary.getService();
	    }
	    String malCompName = area.getName() + ":" + (service == null ? "_" : service.getName()) + ":" + compCtxt.compositeName;
	
	    getLog().info("Creating composite list " + malCompName);

      // create the writer structures
	    CompositeListWriter writer = new CompositeListWriter(compCtxt);	
	    writer.init();
	    createGenericTypeList(writer);

	  	if (compositeHierarchy != null && compositeHierarchy.size() > 0) {
	  		// implement the parent abstract composite marker interfaces
	  		String compositeName = compCtxt.compositeName + "List";

	  		for (TypeReference parent : compositeHierarchy) {
	  			String parentName = parent.getName().substring(0, 1).toUpperCase() + parent.getName().substring(1) + "List";
	  			String parentPackage = parent.getService() == null ? parent.getArea() : parent.getService();
	  			parentPackage = parentPackage.toLowerCase();
	  			String qfParentName = parentName;
	  			if (!parentPackage.equals(writer.packageName)) {
	  				qfParentName = parentPackage + "." + qfParentName;
	  			}

	  			String comment = "================================================================================";
	  			writer.addNewLine();
	  			writer.addSingleLineComment(comment);
	  			comment = "Defines " + compositeName + " type as a " + parentName;
	  			writer.addSingleLineComment(comment);

	  			// func (receiver *<Composite>) <Parent>() <Parent> {
	  			//   return receiver
	  			// }
	  			writer.openFunction(parentName, "*" + compositeName);
	  			writer.openFunctionBody(new String[] { qfParentName });
	  			writer.addStatement("return " + GoFileWriter.FUNC_RECEIVER);
	  			writer.closeFunctionBody();
	  			writer.addNewLine();
	  		}
	  	}
	  	
	    writer.close();
	  }
	  
	  private void processCompFields(CompositeContext compCtxt) throws IOException
	  {
	  	// generate code in memory for some constructs of the <composite>.c file
	  	// so that the algorithm is shared for all constructs
	
	  	String comment;
	  	GoFileWriter compositeContent = compCtxt.writer.compositeContent;

	    comment = "Defines " + compCtxt.compositeName + " type";
	    compositeContent.addNewLine();
	    compositeContent.addSingleLineComment(comment);
	    compositeContent.addNewLine();
	    
	    // open the structure definition
	    // type <composite name> struct {
	  	compositeContent.openStruct(compCtxt.compositeName);

	    // find the parent type, if not base Composite type
	    TypeReference parentType = null;
	    if ((null != compCtxt.composite.getExtends()) && (!StdStrings.COMPOSITE.equals(compCtxt.composite.getExtends().getType().getName())))
	    {
		  	// TODO heritage
	      parentType = compCtxt.composite.getExtends().getType();
	    }
	    
	    // build the list of all the component fields
	    TypeReference compositeTypeRef = TypeUtils.createTypeReference(
	    		compCtxt.areaContext.area.getName(),
	    		(compCtxt.serviceContext != null ? compCtxt.serviceContext.summary.getService().getName() : null),
	    		compCtxt.composite.getName(),
	    		false);
	    List<CompositeField> compElements = new LinkedList<CompositeField>();
	    createCompositeSuperElementsList(null, compositeTypeRef, compElements);
//	    List<CompositeField> compElements = createCompositeElementsList(null, compCtxt.composite);
	    if (!compElements.isEmpty())
	    {
	    	for (CompositeField element : compElements)
	    	{
	    		// keep the field package type for future import
	    		compCtxt.reqTypes.add(element.getTypeReference());
	    		
	    		// sets generation flags in a first step, filling in the CompositeFieldDetails structure
	    		CompositeFieldDetails cfDetails = new CompositeFieldDetails();
	    		cfDetails.fieldName = element.getFieldName();
	    		cfDetails.type = element.getTypeReference();
	    		// in the code below we cannot use element.getTypeName which should be set in createCompositeElementsDetails
	    		// according to the GeneratorLang framework

	    		if (element.isCanBeNull())
	    		{
	    			cfDetails.isNullable = true;
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
    				cfDetails.fieldType = "mal.Attribute";
	    		}
	    		else
	    		{
	    			cfDetails.qfTypeNameL = getTypeFQN(cfDetails.type, compositeContent.packageName);

	    			if (cfDetails.type.isList())
	    			{
	    				cfDetails.isList = true;
	    				//	<qualified type>List
	    				cfDetails.fieldType = cfDetails.qfTypeNameL + "List";
	    			}
	    			else if (isAttributeType(cfDetails.type))
	    			{
	    				cfDetails.isAttribute = true;
	    				cfDetails.fieldType = getAttributeDetails(cfDetails.type).getTargetType();
	    			}
	    			else if (isEnum(cfDetails.type))
	    			{
	    				compCtxt.holdsEnumField = true;
	    				cfDetails.isEnumeration = true;
	    				cfDetails.fieldType = cfDetails.qfTypeNameL;
	    			}
	    			else if (isComposite(cfDetails.type))
	    			{
	    				cfDetails.isComposite = true;
	    				cfDetails.fieldType = cfDetails.qfTypeNameL;
	    			}
	    			else
	    			{
	    				throw new IllegalArgumentException("unexpected type " + cfDetails.type.toString() + " for composite field " + element.getFieldName());
	    			}
	    			if (cfDetails.isNullable)
	    			{
	    				cfDetails.fieldType = "*" + cfDetails.fieldType;
	    			}
	    		}

	    		// generate code in a second step

	    		// add the field definition
	    		compositeContent.addStructField(cfDetails.fieldType, cfDetails.fieldName);

	  	    if (generateTransportMalbinary || generateTransportMalsplitbinary) {
	  	    	addCompFieldMalbinaryEncoding(compCtxt, element, cfDetails);
	  	    }

	    	}
	    }

	    // close the structure definition
	    // };
	    compositeContent.closeStruct();
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
	
	  protected String getTypeFQN(TypeReference ptype, String packageName) {
		  // build the fully qualified name of the field type for the go mapping
		  // [[<area>|<service>].]<field type>
		  // attribute type <attribute> naturally gives a qualified name mal.<attribute>
		  StringBuilder buf = new StringBuilder();
		  String typePackage = null;
		  if (ptype.getService() != null)
		  {
		  	typePackage = ptype.getService().toLowerCase();
		  } else {
		  	typePackage = ptype.getArea().toLowerCase();
		  }
		  if (! typePackage.equals(packageName)) {
		  	buf.append(typePackage)
		  		.append(".");
		  }
		  buf.append(ptype.getName().substring(0, 1).toUpperCase())
		  	.append(ptype.getName().substring(1));
		  return buf.toString();
	  }
	
	  protected ParameterDetails getParameterDetails(TypeInfo typeInfo, String packageName) {
	  	ParameterDetails paramDetails = new ParameterDetails();
	  	TypeReference ptype = typeInfo.getSourceType();
	  	paramDetails.isError = /* FIXME */ false;
	  	paramDetails.isAttribute = isAttributeType(ptype);
	  	paramDetails.isAbstract = isAbstract(ptype);
	  	paramDetails.isComposite = isComposite(ptype);
	  	paramDetails.isList = ptype.isList();
	  	paramDetails.isAbstractAttribute = false;
	  	paramDetails.isEnumeration = isEnum(ptype);
	  	paramDetails.isPolymorph = /* FIXME */ false;
	  	paramDetails.isPresenceFlag = false;
	  	paramDetails.isPubSub = /* FIXME */ false;
	  	paramDetails.qfTypeNameL = getTypeFQN(ptype, packageName);
	  	paramDetails.type = ptype;
	  	paramDetails.sourceType = typeInfo;

	  	String typeName = ptype.getName().substring(0, 1).toUpperCase() + ptype.getName().substring(1);
	  	
			StringBuffer targetTypeBuf = new StringBuffer();
			StringBuffer nilValueBuf = new StringBuffer();
			if (! paramDetails.isAbstract) {
				targetTypeBuf.append("*");
			}
			String typePackage;
			if (ptype.getService() != null) {
				typePackage = ptype.getService().toLowerCase();
			} else {
				typePackage = ptype.getArea().toLowerCase();
			}
			if (!typePackage.equals(packageName)) {
				targetTypeBuf.append(typePackage).append(".");
				nilValueBuf.append(typePackage).append(".");
			}
			targetTypeBuf.append(typeName);
			nilValueBuf
				.append("Null")
				.append(typeName);
			// TODO pas de mal.NullAttribute - est-ce un cas possible ?
			if (paramDetails.isList) {
				targetTypeBuf.append("List");
				nilValueBuf.append("List");
			}
			paramDetails.targetType = targetTypeBuf.toString();
			paramDetails.nilValue = nilValueBuf.toString();
			paramDetails.fieldName = typeInfo.getFieldName();
			
	  	return paramDetails;
	  }

	  protected List<ParameterDetails> getParameterDetailsList(List<TypeInfo> typeInfos, String packageName) {
	  	if (typeInfos == null || typeInfos.isEmpty())
	  		return null;
	  	List<ParameterDetails> pdlist = new ArrayList<>(typeInfos.size());
	  	for (int i = 0; i < typeInfos.size(); i ++) {
	  		pdlist.add(getParameterDetails(typeInfos.get(i), packageName));
	  	}
	  	pdlist.get(pdlist.size()-1).isLast = true;
	  	return pdlist;
	  }
	  
	  private void addCompFieldMalbinaryEncoding(CompositeContext compCtxt, CompositeField element, CompositeFieldDetails cfDetails) throws IOException
	  {
			GoFileWriter encode = compCtxt.writer.compositeEncode;
			StatementWriter encodeW = compCtxt.writer.compositeEncodeW;
			GoFileWriter decode = compCtxt.writer.compositeDecode;
			StatementWriter decodeW = compCtxt.writer.compositeDecodeW;
			GoFileWriter decodeCreate = compCtxt.writer.compositeDecodeCreate;
			StatementWriter decodeCreateW = compCtxt.writer.compositeDecodeCreateW;
			
			String fieldType;
			boolean needPointer;
			boolean useElement = false;
	  	if (cfDetails.isAbstractAttribute)
	  	{
	  		fieldType = "Attribute";
	  		needPointer = false;
	  	}
	  	else if (cfDetails.isAttribute)
	  	{
	  		fieldType = cfDetails.type.getName();
	  		needPointer = !cfDetails.isNullable;
	  	}
	  	else if (cfDetails.isList)
	  	{
	  		// TODO on peut peut-etre appeler ElementList
	  		fieldType = "Element";
	  		useElement = true;
	  		needPointer = !cfDetails.isNullable;
	  	}
	  	else
	  	{
	  		// enumerations also are Elements
	  		fieldType = "Element";
	  		useElement = true;
	  		needPointer = !cfDetails.isNullable;
	  	}	
			
	  	// err [:]= encoder.Encode<XXX>([&]<receiver>.<XXX>)
	  	encode.addIndent();
	  	encodeW.append("err ");
	  	if (! encode.isErrDefined) {
	  		encode.isErrDefined = true;
	  		encodeW.append(":");
	  	}
	  	encodeW.append("= encoder.Encode");
	  	if (element.isCanBeNull()) {
	  		encodeW.append("Nullable");
	  	}
  		encodeW.append(fieldType);
	  	encodeW.append("(");
	  	if (needPointer) {
	  		encodeW.append("&");
	  	}
	  	encodeW.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(".")
	  		.append(cfDetails.fieldName)
	  		.append(")");
	  	encode.addNewLine();
	  	// if err != nil {
			//   return err
			// }
	  	encode.addStatement("if err != nil {", 1);
	  	encode.addStatement("return err");
	  	encode.addStatement("}", -1, true);
	  	
	  	// <XXX>, err := decoder.Decode<XXX>()
	  	// <XXX>, err := decoder.Decode<XXX>(NullXXX)
	  	decode.addIndent();
	  	decodeW.append(cfDetails.fieldName)
	  		.append(", err := decoder.Decode");
	  	if (element.isCanBeNull()) {
	  		decodeW.append("Nullable");
	  	}
	  	decodeW.append(fieldType)
	  		.append("(");
	  	if (useElement) {
	  		// use the qualified name of the field type
	  		// TODO verifier la chasse de l'area
	  		String typePackage = cfDetails.type.getService() == null ? cfDetails.type.getArea().toLowerCase() : cfDetails.type.getService().toLowerCase();
	  		if (! typePackage.equals(decode.packageName)) {
	  			decodeW
	  				.append(typePackage)
	  				.append(".");
	  		}
	  		decodeW
	  			.append("Null")
	  			.append(cfDetails.type.getName());
	  		if (cfDetails.isList) {
	  			decodeW.append("List");
	  		}
	  	}
	  	decodeW.append(")");
	  	decode.addNewLine();
	  	// if err != nil {
			//   return nil, err
			// }
	  	decode.addStatement("if err != nil {", 1);
	  	decode.addStatement("return nil, err");
	  	decode.addStatement("}", -1, true);
	  	
	  	//   <field>: <field>.(<type>),
	  	decodeCreate.addIndent();
	  	decodeCreateW
	  		.append(cfDetails.fieldName)
	  		.append(": ");
	  	//if (! cfDetails.isNullable) {
	  	if (needPointer) {
	  		// TODO verifier types abstraits
	  		decodeCreateW.append("*");
	  	}
	  	decodeCreateW.append(cfDetails.fieldName);
	  	// TODO le besoin de cast est a verifier
	  	if (useElement) {
	  		decodeCreateW.append(".(");
	  		if (needPointer)
	  			decodeCreateW.append("*");
	  		decodeCreateW
	  			.append(cfDetails.fieldType)
	  			.append(")");
	  	}
	  	decodeCreateW.append(",");
	  	decodeCreate.addNewLine();
	  }
	
	  private void addCompositeNullValue(CompositeContext compCtxt) throws IOException
	  {
	  	// pour l'instant on genere en memoire
	  	GoFileWriter composite = compCtxt.writer.compositeContent;
	  	composite.addNewLine();
	  	// var (
	  	//     Null<composite> *<composite> = nil
	  	// )
	  	composite.openVarBlock();
	  	composite.addIndent();
	  	compCtxt.writer.compositeContentW.append("Null")
	  		.append(compCtxt.compositeName)
	  		.append(" *")
	  		.append(compCtxt.compositeName)
	  		.append(" = nil");
	  	composite.addNewLine();
	  	composite.closeVarBlock();
	  }
	  
	  private void addCompositeConstructor(CompositeContext compCtxt) throws IOException
	  {
	  	GoFileWriter composite = compCtxt.writer.compositeContent;
	  	String compositeName = compCtxt.compositeName;
	  	
	  	// func New<component>() *<component> {
	  	//     return new(<component>)
	  	// }
	  	composite.openFunction("New" + compositeName, null);
	  	composite.openFunctionBody(new String[] {"*" + compositeName});
	  	composite.addStatement("return new(" + compositeName + ")");
	  	composite.closeFunctionBody();
	  }
	  
	  private void addCompositeMalCompositeAPI(CompositeContext compCtxt) throws IOException
	  {
	  	GoFileWriter writer = compCtxt.writer.compositeContent;
	  	String compositeName = compCtxt.compositeName;

	  	String comment1 = "================================================================================";
	  	String comment2 = "Defines " + compositeName + " type as a MAL Composite";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment1);
	  	writer.addSingleLineComment(comment2);
	  	writer.addNewLine();
	  	
	  	// func (receiver *<composite>) Composite() mal.Composite {
	  	//     return receiver
	  	// }
	  	writer.openFunction("Composite", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.Composite"});
	  	writer.addStatement("return " + GoFileWriter.FUNC_RECEIVER);
	  	writer.closeFunctionBody();
	  }

	  private void addCompositeMalElementAPI(CompositeContext compCtxt) throws IOException
	  {
	  	GoFileWriter writer = compCtxt.writer.compositeContent;
	  	StatementWriter swriter = compCtxt.writer.compositeContentW;
	  	String compositeName = compCtxt.compositeName;

	  	String comment = "================================================================================";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	comment = "Defines " + compositeName + " type as a MAL Element";
	  	writer.addSingleLineComment(comment);
	  	writer.addNewLine();
	  	
	  	// const <COMPOSITE>_TYPE_SHORT_FORM mal.Integer = 0x<type short form>
	  	// const <COMPOSITE>_SHORT_FORM mal.Long = 0x<short form>
	  	int typeShortForm = compCtxt.composite.getShortFormPart().intValue();
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
	  		.append(compCtxt.compositeNameU)
	  		.append("_TYPE_SHORT_FORM mal.Integer = ")
	  		.append(Integer.toString(typeShortForm));
	  	writer.addNewLine();
	  	long absoluteShortForm = getAbsoluteShortForm(
	  			compCtxt.areaContext.area.getNumber(),
	  			compCtxt.isAreaType() ? 0 : compCtxt.serviceContext.summary.getService().getNumber(),
	  					compCtxt.areaContext.area.getVersion(),
	  					typeShortForm);
	  	writer.addIndent();
	  	swriter
	  		.append("const ")
  		.append(compCtxt.compositeNameU)
  		.append("_SHORT_FORM mal.Long = 0x")
  		.append(Long.toHexString(absoluteShortForm));
	  	writer.addNewLine();
	
	  	comment = "Registers " + compositeName + " type for polymorphism handling";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func init() {
	  	//   mal.RegisterMALElement(<COMPOSITE>_SHORT_FORM, Null<composite>)
	  	// }
	  	writer.openFunction("init", null);
	  	writer.openFunctionBody(null);
	  	writer.addIndent();
	  	swriter.append("mal.RegisterMALElement(")
  			.append(compCtxt.compositeNameU)
  			.append("_SHORT_FORM, Null")
  			.append(compositeName)
  			.append(")");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the absolute short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Composite>) GetShortForm() mal.Long {
	  	//   return <COMPOSITE>_SHORT_FORM
	  	// }
	  	writer.openFunction("GetShortForm", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.Long"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(compCtxt.compositeNameU)
				.append("_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Composite>) GetAreaNumber() mal.UShort {
	  	//   return [<area>.]AREA_NUMBER
	  	// }
	  	writer.openFunction("GetAreaNumber", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (!compCtxt.isAreaType()) {
	  		swriter
	  			.append(compCtxt.areaContext.areaNameL)
	  			.append('.');
	  	}
	  	swriter.append("AREA_NUMBER");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the version of the area this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Composite>) GetAreaVersion() mal.UOctet {
	  	//   return [<area>.]AREA_VERSION
	  	// }
	  	writer.openFunction("GetAreaVersion", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.UOctet"});
	  	writer.addIndent();
	  	swriter.append("return ");
	  	if (!compCtxt.isAreaType()) {
	  		swriter
	  			.append(compCtxt.areaContext.areaNameL)
	  			.append('.');
	  	}
	  	swriter.append("AREA_VERSION");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	comment = "Returns the number of the service this element type belongs to.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Composite>) GetServiceNumber() mal.UShort {
	  	//   return [mal.NULL_]SERVICE_NUMBER
	  	// }
	  	writer.openFunction("GetServiceNumber", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.UShort"});
	  	writer.addIndent();
	  	if (compCtxt.isAreaType()) {
	  		writer.addStatement("return mal.NULL_SERVICE_NUMBER");
	  	} else {
	  		writer.addStatement("return SERVICE_NUMBER");
	  	}
	  	writer.closeFunctionBody();

	  	comment = "Returns the relative short form of the element type.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (*<Composite>) GetTypeShortForm() mal.Integer {
	  	//   return <COMPOSITE>_TYPE_SHORT_FORM
	  	// }
	  	writer.openFunction("GetTypeShortForm", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.Integer"});
	  	writer.addIndent();
	  	swriter
	  		.append("return ")
				.append(compCtxt.compositeNameU)
				.append("_TYPE_SHORT_FORM");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	comment = "Allows the creation of an element in a generic way, i.e., using the MAL Element polymorphism.";
	  	writer.addNewLine();
	  	writer.addSingleLineComment(comment);
	  	// func (<receiver> *<Composite>) CreateElement() mal.Element {
	  	//   return new(<Composite>)
	  	// }
	  	writer.openFunction("CreateElement", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return new(")
				.append(compositeName)
				.append(")");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// func (<receiver> *<Composite>) IsNull() bool {
	  	//   return <receiver> == nil
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("IsNull", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"bool"});
	  	writer.addIndent();
	  	swriter.append("return ")
				.append(GoFileWriter.FUNC_RECEIVER)
				.append(" == nil");
	  	writer.addNewLine();
	  	writer.closeFunctionBody();

	  	// func (*<Composite>) Null() mal.Element {
	  	//   return Null<Composite>
	  	// }
	  	writer.addNewLine();
	  	writer.openFunction("Null", "*" + compositeName);
	  	writer.openFunctionBody(new String[] {"mal.Element"});
	  	writer.addIndent();
	  	swriter.append("return Null")
				.append(compositeName);
	  	writer.addNewLine();
	  	writer.closeFunctionBody();
	  	
	  	// TODO Stringer interface ?
	  }

	  private void addCompositeAbstractAPIs(CompositeContext compCtxt, List<TypeReference> compositeHierarchy) throws IOException
	  {
	  	if (compositeHierarchy == null || compositeHierarchy.size() == 0)
	  		return;
	  	
	  	GoFileWriter writer = compCtxt.writer.compositeContent;
	  	StatementWriter swriter = compCtxt.writer.compositeContentW;
	  	String compositeName = compCtxt.compositeName;

	  	for (TypeReference parent : compositeHierarchy) {
	  		String parentName = parent.getName().substring(0, 1).toUpperCase() + parent.getName().substring(1);
	  		String parentPackage = parent.getService() == null ? parent.getArea() : parent.getService();
	  		parentPackage = parentPackage.toLowerCase();
	  		String qfParentName = parentName;
	  		if (!parentPackage.equals(writer.packageName)) {
	  			qfParentName = parentPackage + "." + qfParentName;
	  		}
	  		
	  		String comment = "================================================================================";
	  		writer.addNewLine();
	  		writer.addSingleLineComment(comment);
	  		comment = "Defines " + compositeName + " type as a " + parentName;
	  		writer.addSingleLineComment(comment);

		  	// func (receiver *<Composite>) <Parent>() <Parent> {
		  	//   return receiver
		  	// }
		  	writer.openFunction(parentName, "*" + compositeName);
		  	writer.openFunctionBody(new String[] { qfParentName });
		  	writer.addStatement("return " + GoFileWriter.FUNC_RECEIVER);
		  	writer.closeFunctionBody();
	  		writer.addNewLine();
	  	}
	  }

	  private void processInitInteraction(OperationContext opContext, String opStage, List<TypeInfo> inParameters, List<TypeInfo> outParameters) throws IOException
	  {
	  	OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, true, inParameters, outParameters);
	  	addConsumerInteractionFunction(opStageCtxt);
	  	if (opContext.operation.getPattern() != InteractionPatternEnum.PUBSUB_OP) {
	  		addProviderInterfaceFunction(opStageCtxt);
	  		addProviderHandler(opStageCtxt);
	  	}
	  }

	  private void processNextInteraction(OperationContext opContext, String opStage, List<TypeInfo> inParameters, List<TypeInfo> outParameters) throws IOException
	  {
	  	processNextInteraction(opContext, opStage, inParameters, outParameters, false);
	  }
	  
	  private void processNextInteraction(OperationContext opContext, String opStage, List<TypeInfo> inParameters, List<TypeInfo> outParameters, boolean skipConsumer) throws IOException
	  {
	  	OpStageContext opStageCtxt = new OpStageContext(opContext, opStage, false, inParameters, outParameters);
	  	if (!skipConsumer) {
	  		// Ack or Response interactions in the consumer may be handled as the return of the initial interaction
	  		addConsumerInteractionFunction(opStageCtxt);
	  	}
	  	if (opContext.operation.getPattern() != InteractionPatternEnum.PUBSUB_OP) {
	  		addProviderHelperFunction(opStageCtxt);
	  	}
	  }

	  private void addConsumerInteractionFunction(OpStageContext opStageCtxt) throws IOException
	  {
	  	// this function produces code for the consumer part
	  	// enabling the user to activate the interaction with its own parameters
	  	ServiceContext serviceContext = opStageCtxt.opContext.serviceContext;
	  	GoFileWriter serviceCContent = serviceContext.consumer.serviceContent;
	  	StatementWriter serviceCContentW = serviceContext.consumer.serviceContentW;
	  	// func (*<operation>Operation) <consumerOpName>(<in parameters>) (<out parameters>, error) {
	  	serviceCContent.openFunction(opStageCtxt.consumerOpName, "*" + opStageCtxt.opContext.consumerStructName);
	  	int inParametersNumber = (opStageCtxt.inParameters == null ? 0 : opStageCtxt.inParameters.size());
	  	for (int i = 0; i < inParametersNumber; i++) {
	  		ParameterDetails param = opStageCtxt.inParameters.get(i);
	  		TypeReference sourceType = param.sourceType.getSourceType();
	  		serviceContext.reqTypes.add(sourceType);
	  		serviceCContent.addFunctionParameter(param.targetType,
	  				param.fieldName, i == opStageCtxt.inParameters.size()-1);
	  	}
	  	int outParametersNumber = (opStageCtxt.outParameters == null ? 0 : opStageCtxt.outParameters.size());
	  	String[] outParameters = new String[outParametersNumber+1];
	  	if (opStageCtxt.outParameters != null) {
	  		for (int i = 0; i < opStageCtxt.outParameters.size(); i++) {
	  			ParameterDetails param = opStageCtxt.outParameters.get(i);
	  			TypeReference sourceType = param.sourceType.getSourceType();
	  			serviceContext.reqTypes.add(sourceType);
	  			outParameters[i] = param.targetType;
	  		}
	  	}
	  	outParameters[outParameters.length-1] = "error";
	  	serviceCContent.openFunctionBody(outParameters);
	  	serviceCContent.isErrDefined = false;
  		StringBuffer nilReturnBuf = new StringBuffer();
  		nilReturnBuf.append("return ");
  		for (int i = 0; i < outParametersNumber; i++) {
  			nilReturnBuf.append("nil, ");
  		}
  		nilReturnBuf.append("err");
  		String nilReturn = nilReturnBuf.toString();
	  	if (inParametersNumber > 0) {
	  		//   // create a body for the operation call
	  		//   body := <receiver>.op.NewBody()
	  		String comment = "create a body for the operation call";
	  		serviceCContent.addSingleLineComment(comment);
	  		serviceCContent.addIndent();
	  		serviceCContentW
	  		.append("body := ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(".op.NewBody()");
	  		serviceCContent.addNewLine();
	  		//   // encode in parameters
	  		//   err := body.Encode[Last]Parameter(<>)
	  		//   if err != nil {
	  		//     return <nil>, err
	  		//   }
	  		comment = "encode in parameters";
	  		serviceCContent.addSingleLineComment(comment);
	    	for (int i = 0; i < inParametersNumber; i++) {
	    		boolean isLast = i == (opStageCtxt.inParameters.size() - 1);
	  			ParameterDetails param = opStageCtxt.inParameters.get(i);
	    		serviceCContent.addIndent();
	    		serviceCContentW.append("err ");
	    		if (! serviceCContent.isErrDefined) {
	    			serviceCContent.isErrDefined = true;
	    			serviceCContentW.append(":");
	    		}
	    		serviceCContentW.append("= body.Encode");
	    		if (isLast) {
	    			serviceCContentW.append("Last");
	    		}
	    		serviceCContentW
	    			.append("Parameter(")
	    			.append(param.fieldName);
	    		if (isLast) {
	    			serviceCContentW
	    				.append(", ")
	    				.append(param.isAbstract ? "true" : "false");
	    		}
	    		serviceCContentW.append(")");
	    		serviceCContent.addNewLine();
	    		serviceCContent.addStatement("if err != nil {", 1);
	    		serviceCContent.addStatement(nilReturn, -1);
	    		serviceCContent.addStatement("}");
	    	}
	    }
	    //   // operation call
	  	//   [resp, ]err := <receiver>.op.<consumerOpName>([body|nil])
	  	serviceCContent.addNewLine();
	    String comment = "operation call";
	    serviceCContent.addSingleLineComment(comment);
	    boolean noReplyMsg = "Send".equals(opStageCtxt.opStage) || "Publish".equals(opStageCtxt.opStage);
	  	serviceCContent.addIndent();
	  	if (noReplyMsg) {
		  	serviceCContentW.append("err ");
	  		if (! serviceCContent.isErrDefined) {
	  			serviceCContentW.append(":");
	  		}
	  	} else {
	  		serviceCContentW.append("resp, err :");
	  	}
			serviceCContent.isErrDefined = true;
	  	serviceCContentW
  			.append("= ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(".op.")
	  		.append(opStageCtxt.consumerOpName)
	  		.append("(")
	  		.append(inParametersNumber > 0 ? "body" : opStageCtxt.nilBody)
	  		.append(")");
	  	serviceCContent.addNewLine();

	  	//   if err != nil {
	  	//     // Verify if an error occurs during the operation
	  	//     if !resp.IsErrorMessage {
	  	//       return <nil>, err
	  	//     }
	  	serviceCContent.addStatement("if err != nil {", 1);
	  	if (!noReplyMsg) {
	  		comment = "Verify if an error occurs during the operation";
	  		serviceCContent.addSingleLineComment(comment);
	  		serviceCContent.addStatement("if !resp.IsErrorMessage {", 1);
	  		serviceCContent.addStatement(nilReturn);
	  		serviceCContent.closeBlock();
	  	}

	  	if (opStageCtxt.opContext.operation.getPattern() == InteractionPatternEnum.PUBSUB_OP) {
	  		// TODO Error handling un PUBSUB operations
	  		comment = "TODO Error handling un PUBSUB operations";
	  		serviceCContent.addSingleLineComment(comment);
	  	} else if (! noReplyMsg) {
		  	//     err = <receiver>.decodeError(resp, err)
	  		serviceCContent.addIndent();
	  		serviceCContentW
	  			.append("err = ")
	  			.append(GoFileWriter.FUNC_RECEIVER)
	  			.append(".decodeError(resp, err)");
	  		serviceCContent.addNewLine();
	  	}
	  	//     return <nil>, err
	  	//   }
  		serviceCContent.addStatement(nilReturn);
  		serviceCContent.closeBlock();
  		
  		// the Update message of the Progress IP may be null while err is null
  		if (opStageCtxt.opStage.equals("Update")) {
  			// if resp == nil {
  			//   return <nil>, err
	    	// }
    		serviceCContent.addStatement("if resp == nil {", 1);
    		serviceCContent.addStatement(nilReturn);
    		serviceCContent.closeBlock();
  		}
	    serviceCContent.addNewLine();
    	StringBuffer returnStatement = new StringBuffer("return ");
	    if (! noReplyMsg) {
	    	// // decode out parameters
	    	// outElem_<param>, err := resp.Decode[Last]Parameter([Null<param type>|nil], [false|true])
	    	// if err != nil {
	    	//   return <nil>, err
	    	// }
	    	// outParam_<param>, ok := outElem_<param>.(<targetType>)
	    	// if ! ok {
	  		//   // if targetType is abstract NullElement cannot be cast
	  		//   if outElem_<param> == mal.NullElement {
    		//     outParam_<param> = Null<targetType>
    		//   } else {
	    	//     err = errors.New("unexpected type for parameter " + <param>)
	    	//     return <nil>, err
	  		//   }
	    	// }
	    	comment = "decode out parameters";
	    	serviceCContent.addSingleLineComment(comment);
	    	for (int i = 0; i < outParametersNumber; i++) {
	    		ParameterDetails param = opStageCtxt.outParameters.get(i);
	    		boolean isLast = i == (outParametersNumber-1);
	    		serviceCContent.addIndent();
	    		serviceCContentW
	    			.append("outElem_")
	    			.append(param.fieldName)
	    			.append(", err := resp.Decode")
	    			.append(isLast ? "Last" : "")
	    			.append("Parameter(");
	    		if (param.isAbstract) {
	    			serviceCContentW.append("nil, true)");
	    		} else {
	    			serviceCContentW
	    				.append(param.nilValue)
	    				.append(isLast ? ", false)" : ")");
	    		}
	    		serviceCContent.addNewLine();
	    		serviceCContent.addStatement("if err != nil {", 1);
	    		serviceCContent.addStatement(nilReturn, -1);
	    		serviceCContent.addStatement("}");
	    		serviceCContent.addIndent();
	    		serviceCContentW
    				.append("outParam_")
	    			.append(param.fieldName)
	    			.append(", ok := outElem_")
	    			.append(param.fieldName)
	    			.append(".(")
	    			.append(param.targetType)
	    			.append(")");
	    		serviceCContent.addNewLine();
	    		serviceCContent.addStatement("if !ok {", 1);
		  		boolean needExplicitCasting = param.isAbstract && !param.isAbstractAttribute;
		  		if (needExplicitCasting) {
		  			serviceCContent.addIndent();
		  			serviceCContentW
		  				.append("if outElem_")
		  				.append(param.fieldName)
		  				.append(" == mal.NullElement {");
		  			serviceCContent.addNewLine(1);
		  			serviceCContent.addIndent();
		  			serviceCContentW
		  				.append("outParam_")
		  				.append(param.fieldName)
		  				.append(" = ")
		  				.append(param.nilValue);
		  			serviceCContent.addNewLine(-1);
		  			serviceCContent.addStatement("} else {", 1);
		  		}
	    		serviceCContent.addIndent();
	    		serviceCContentW
    				.append("err = errors.New(\"unexpected type for parameter ")
    				.append(param.fieldName)
    				.append("\")");
	    		serviceCContent.addNewLine();
	    		serviceCContent.addStatement(nilReturn);
		  		if (needExplicitCasting) {
		    		serviceCContent.closeBlock();
		  		}
	    		serviceCContent.closeBlock();
	    		serviceCContent.addNewLine();

	    		// return outParam_<param>, nil
	    		returnStatement
	    			.append("outParam_")
	    			.append(param.fieldName)
	    			.append(", ");
	    	}
	    }
	  	returnStatement.append("nil");
	  	serviceCContent.addStatement(returnStatement.toString());
	  	serviceCContent.closeFunctionBody();
	  }

	  /*
	   * Create the upcall function in the provider implementation interface.
	   */
	  private void addProviderInterfaceFunction(OpStageContext opStageCtxt) throws IOException
	  {
	  	ServiceContext serviceContext = opStageCtxt.opContext.serviceContext;
	  	GoFileWriter servicePItf = serviceContext.provider.servicePItf;
	  	StatementWriter servicePItfW = serviceContext.provider.servicePItfW;
	  	String opName = opStageCtxt.opContext.operationName;

	  	// <operation>(opHelper <operation>Helper, [<param paramType>]) error
	  	servicePItf.addIndent();
	  	servicePItfW
	  		.append(opName)
	  		.append("(");
	  	servicePItfW
				.append("opHelper")
				.append(" *")
				.append(opName)
				.append("Helper");
	  	if (opStageCtxt.inParameters != null) {
	  		for (int i = 0; i < opStageCtxt.inParameters.size(); i++) {
	  			ParameterDetails param = opStageCtxt.inParameters.get(i);
	  			TypeReference sourceType = param.sourceType.getSourceType();
	  			serviceContext.reqTypes.add(sourceType);
	  			servicePItfW
	  				.append(", ")
	  				.append(param.fieldName)
	  				.append(" ")
	  				.append(param.targetType);
	  		}
	  	}
	  	servicePItfW.append(") error");
	  	servicePItf.addNewLine();
	  }
	  
	  /*
	   * Create the provider handler for the operation.
	   */
	  private void addProviderHandler(OpStageContext opStageCtxt) throws IOException
	  {
	  	ServiceContext serviceContext = opStageCtxt.opContext.serviceContext;
	  	GoFileWriter servicePHandlers = serviceContext.provider.servicePHandlers;
	  	StatementWriter servicePHandlersW = serviceContext.provider.servicePHandlersW;
	  	String opName = opStageCtxt.opContext.operationName;
	  	String returnErrStatement = (opStageCtxt.opContext.isNoError ? "return err" : "return opHelper.ReturnError(err)");
	  	
	  	String comment = "define the handler for operation " + opName;
	  	servicePHandlers.addSingleLineComment(comment);
	  	// <operation>Handler := func(msg *mal.Message, t malapi.Transaction) error {
	  	// // create the Helper first to enable access to the error function
	  	// opHelper, err := New<operation>Helper(t)
			// if err != nil {
			//	 return err
			// }
	  	//   if msg == nil {
	  	//     err = errors.New("missing Message")
	  	//     <returnErrStatement>
	  	//   }
	  	servicePHandlers.addIndent();
	  	servicePHandlersW
		  	.append(opName)
		  	.append("Handler := func(msg *mal.Message, t malapi.Transaction) error {");
	  	servicePHandlers.addNewLine();
	  	servicePHandlers.addIndent();
	  	servicePHandlersW
  			.append("opHelper, err := New")
	  		.append(opName)
	  		.append("Helper(t)");
	  	servicePHandlers.addNewLine();
	  	servicePHandlers.addStatement("if err != nil {", 1);
	  	servicePHandlers.addStatement("return err");
	  	servicePHandlers.closeBlock();
	  	servicePHandlers.isErrDefined = true;
	  	servicePHandlers.addStatement("if msg == nil {", 1, true);
	  	servicePHandlers.addStatement("err := errors.New(\"missing Message\")", 1, true);
	  	servicePHandlers.addStatement(returnErrStatement);
	  	servicePHandlers.closeBlock();
	  	
	  	comment = "decode in parameters";
	  	servicePHandlers.addSingleLineComment(comment);
	  	int inParamSize = opStageCtxt.inParameters == null ? 0 : opStageCtxt.inParameters.size();
	  	for (int i = 0; i < inParamSize; i++) {
	  		ParameterDetails param = opStageCtxt.inParameters.get(i);
	  		// inElem_<param>, err := msg.Decode[Last]Parameter(<nilvalue>[, <isabstract>])
	  		// if err != nil {
	  		//	 // traitement de l'erreur specifique a l'operation
	  		// }
	    	// inParam_<param>, ok := inElem_<param>.(<targetType>)
	    	// if ! ok {
	  		//   // if targetType is abstract, NullElement cannot be cast
	  		//   if inElem_<param> == mal.NullElement {
    		//     inParam_<param> = Null<targetType>
    		//   } else {
	    	//     err = errors.New("unexpected type for parameter " + <param>)
	  		//	   // traitement de l'erreur specifique a l'operation
	  		//   }
	    	// }
	  		servicePHandlers.addIndent();
	  		servicePHandlersW
	  			.append("inElem_")
	  			.append(param.fieldName)
	  			.append(", err := msg.Decode");
	  		if (param.isLast) {
	  			servicePHandlersW.append("Last");
	  		}
	  		servicePHandlersW
	  			.append("Parameter(")
	  			.append(param.isAbstract ? "nil" : param.nilValue);
	  		if (param.isLast) {
	  			servicePHandlersW
	  				.append(", ")
	  				.append(param.isAbstract ? "true" : "false");
	  		}
	  		servicePHandlersW.append(")");
	  		servicePHandlers.addNewLine();
	  		servicePHandlers.addStatement("if err != nil {", 1);
		  	servicePHandlers.addStatement(returnErrStatement);
	  		servicePHandlers.closeBlock();
	  		servicePHandlers.addIndent();
	  		servicePHandlersW
  				.append("inParam_")
  				.append(param.fieldName)
  				.append(", ok := inElem_")
  				.append(param.fieldName)
  				.append(".(")
  				.append(param.targetType)
  				.append(")");
	  		servicePHandlers.addNewLine();
	  		servicePHandlers.addStatement("if !ok {", 1);
	  		boolean needExplicitCasting = param.isAbstract && !param.isAbstractAttribute;
	  		if (needExplicitCasting) {
	  			servicePHandlers.addIndent();
	  			servicePHandlersW
	  				.append("if inElem_")
	  				.append(param.fieldName)
	  				.append(" == mal.NullElement {");
	  			servicePHandlers.addNewLine(1);
	  			servicePHandlers.addIndent();
	  			servicePHandlersW
	  				.append("inParam_")
	  				.append(param.fieldName)
	  				.append(" = ")
	  				.append(param.nilValue);
	  			servicePHandlers.addNewLine(-1);
	  			servicePHandlers.addStatement("} else {", 1);
	  		}
	  		servicePHandlers.addIndent();
	  		servicePHandlersW
  				.append("err = errors.New(\"unexpected type for parameter ")
  				.append(param.fieldName)
  				.append("\")");
	  		servicePHandlers.addNewLine();
		  	servicePHandlers.addStatement(returnErrStatement);
	  		if (needExplicitCasting) {
		  		servicePHandlers.closeBlock();
	  		}
	  		servicePHandlers.closeBlock();
	  	}
	  	
	  	comment = "call the provider implementation";
	  	servicePHandlers.addSingleLineComment(comment);
	  	// err = providerImpl.<operation>(opHelper, [inParam_<param>])
			// if err != nil {
			//	 return opHelper.ReturnError(err)
			// }
	  	servicePHandlers.addIndent();
	  	servicePHandlersW
	  		.append("err ")
	  		.append(inParamSize > 0 ? "=" : ":=")
	  		.append(" providerImpl.")
	  		.append(opName)
	  		.append("(");
  		servicePHandlersW.append("opHelper");
	  	for (int i = 0; i < inParamSize; i++) {
	  		ParameterDetails param = opStageCtxt.inParameters.get(i);
	  		servicePHandlersW
	  			.append(", inParam_")
	  			.append(param.fieldName);
	  	}
	  	servicePHandlersW.append(")");
	  	servicePHandlers.addNewLine();
	  	servicePHandlers.addStatement("if err != nil {", 1);
	  	servicePHandlers.addStatement(returnErrStatement);
	  	servicePHandlers.closeBlock();
	  	servicePHandlers.addStatement("return nil");
	  	servicePHandlers.closeBlock();
	  	comment = "register the handler";
	  	servicePHandlers.addSingleLineComment(comment);
	  	// err = cctx.Register<IP>Handler(<area>, <areaVersion>, <service>, <operation>, <operation>Handler)
			// if err != nil {
			//	 TODO traitement de l'erreur specifique a l'operation
			// }
	  	servicePHandlers.addIndent();
	  	servicePHandlersW
  			.append("err =")
  			.append(" cctx.Register")
  			.append(opStageCtxt.opStage)
  			.append("Handler(")
  			.append(serviceContext.areaContext.areaNameL)
  			.append(".AREA_NUMBER, ")
  			.append(serviceContext.areaContext.areaNameL)
  			.append(".AREA_VERSION, SERVICE_NUMBER, ")
  			.append(opName.toUpperCase())
  			.append("_OPERATION_NUMBER, ")
  			.append(opName)
  			.append("Handler)");
	  	servicePHandlers.addNewLine();
	  	servicePHandlers.addStatement("if err != nil {", 1);
	  	servicePHandlers.addStatement("return nil, err");
	  	servicePHandlers.closeBlock();
	  }

	  private void addProviderHelperFunction(OpStageContext opStageCtxt) throws IOException
	  {
	  	// this function produces code for the provider part
	  	// enabling the user to activate the next interactions with its own parameters
	  	ServiceContext serviceContext = opStageCtxt.opContext.serviceContext;
	  	GoFileWriter servicePHelper = serviceContext.provider.servicePHelper;
	  	StatementWriter servicePHelperW = serviceContext.provider.servicePHelperW;
	  	String opName = opStageCtxt.opContext.operationName;
	  	
	  	// func (<receiver> *<operation>Helper) <pHelperOpName>(<out parameters>) error {
	  	servicePHelper.openFunction(opStageCtxt.pHelperOpName, "*" + opName + "Helper");
	  	int outParamSize = opStageCtxt.outParameters == null ? 0 : opStageCtxt.outParameters.size();
	  	for (int i = 0; i < outParamSize; i++) {
	  		ParameterDetails param = opStageCtxt.outParameters.get(i);
	  		TypeReference sourceType = param.sourceType.getSourceType();
	  		serviceContext.reqTypes.add(sourceType);
	  		servicePHelper.addFunctionParameter(param.targetType,
	  					param.fieldName, i == outParamSize-1);
	  	}
	  	servicePHelper.openFunctionBody(new String[] {"error"});
	  	// transaction := <receiver>.transaction
	  	servicePHelper.addIndent();
	  	servicePHelperW
	  		.append("transaction := ")
	  		.append(GoFileWriter.FUNC_RECEIVER)
	  		.append(".transaction");
	  	servicePHelper.addNewLine();
  		servicePHelper.isErrDefined = false;
  		String bodyVar = "nil";
	  	if (outParamSize > 0) {
	  		bodyVar = "body";
	  		//   // create a body for the interaction call
	  		//   body := transaction.NewBody()
	  		String comment = "create a body for the interaction call";
	  		servicePHelper.addSingleLineComment(comment);
	  		servicePHelper.addStatement("body := transaction.NewBody()");
	  		//   // encode parameters
	  		//   err := body.Encode[Last]Parameter(<>)
	  		//   if err != nil {
	  		//     return <nil>, err
	  		//   }
	  		comment = "encode parameters";
	  		servicePHelper.addSingleLineComment(comment);
	  		String nilReturn = "return nil";
	    	for (int i = 0; i < outParamSize; i++) {
	  			ParameterDetails param = opStageCtxt.outParameters.get(i);
	  			boolean isLast = i == (outParamSize-1);
	  			servicePHelper.addIndent();
	  			servicePHelperW.append("err ");
	    		if (! servicePHelper.isErrDefined) {
	    			servicePHelper.isErrDefined = true;
	    			servicePHelperW.append(":");
	    		}
	    		servicePHelperW.append("= body.Encode");
	    		if (isLast) {
	    			servicePHelperW.append("Last");
	    		}
	    		servicePHelperW
	    			.append("Parameter(")
	    			.append(param.fieldName);
	    		if (isLast) {
	    			servicePHelperW
	    				.append(", ")
	    				.append(param.isAbstract ? "true" : "false");
	    		}
	    		servicePHelperW
	    			.append(")");
	    		servicePHelper.addNewLine();
	    		servicePHelper.addStatement("if err != nil {", 1);
	    		servicePHelper.addStatement(nilReturn, -1);
	    		servicePHelper.addStatement("}");
	    	}
	    }
	    //   // interaction call
	  	//   err := transaction.<opStage>([body|nil], false)
	  	//   if err != nil {
	  	//       TODO
	  	//       return err
	  	//   }
	  	servicePHelper.addNewLine();
	    String comment = "interaction call";
	    servicePHelper.addSingleLineComment(comment);
	    servicePHelper.addIndent();
			servicePHelperW.append("err ");
  		if (! servicePHelper.isErrDefined) {
  			servicePHelper.isErrDefined = true;
  			servicePHelperW.append(":");
  		}
	    servicePHelperW
	  		.append("= transaction.")
	  		.append(opStageCtxt.opStage)
	  		.append("(")
	  		.append(bodyVar)
	  		.append(", false)");
	    servicePHelper.addNewLine();
	    servicePHelper.addStatement("if err != nil {", 1);
	    servicePHelper.addStatement("return err", -1);
	    servicePHelper.addStatement("}");
	    servicePHelper.addNewLine();
	  	switch (opStageCtxt.opContext.operation.getPattern()) {
	  	case SUBMIT_OP:
	  	case REQUEST_OP:
		  	break;
	  	case INVOKE_OP:
	  	case PROGRESS_OP:
		    servicePHelper.addIndent();
		    servicePHelperW
		    	.append(GoFileWriter.FUNC_RECEIVER)
		    	.append(".acked = true");
		    servicePHelper.addNewLine();
	  		break;
	  	default:
	  		throw new IllegalStateException("unexpected pattern in addProviderHelperFunction: " + opStageCtxt.opContext.operation.getPattern());
	  	}

	    servicePHelper.addStatement("return nil");
	    servicePHelper.closeFunctionBody();
	  }
	
	  /**
	   * Fill in the ParameterDetails structure from the paramType parameter.
	   * paramType refers to a concrete type.
	   */
	  private void fillInteractionParamDetails(ParameterDetails paramDetails, TypeReference paramType) throws IOException {
	  	// TODO modifier le parametre de getTypeFQN si cette fonction est effectivement appelee
			paramDetails.qfTypeNameL = getTypeFQN(paramType, null);
			
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
	   * Context of code generation for an area.
	   */
	  private class AreaContext {
	  	/** original area description */
	  	final AreaType area;
	  	/** folder for the area generated code files */
	  	final File areaFolder;
	    /** area name in lower case */
	  	final String areaNameL;
	    /** area name in upper case */
	  	final String areaNameU;
	  	/** writer to the helper.go file */
	  	final AreaHelperWriter helper;
	  	
	  	/** AREA_NUMBER */
	  	final String areaNumberConstName;
	  	/** <area>.AREA_NUMBER */
	  	final String qfAreaNumberConstName;
	  	/** AREA_VERSION */
	  	final String areaVersionConstName;
	  	/** <area>.AREA_VERSION */
	  	final String qfAreaVersionConstName;
	  	
	  	// base package for the area package
	  	final String basePackage = BASE_PACKAGE_DEFAULT;
	  	
	  	/** writer to the <area>.h file. * /
	  	final AreaHWriter areaH;
	  	/** buffer for the types part of the <area>.h file * /
	  	final StatementWriter areaHTypesW;
	  	/** writer for the types part of the <area>.h file * /
	  	final CFileWriter areaHTypes;
	  	/** buffer for the main content of the <area>.h file * /
	  	final StatementWriter areaHContentW;
	  	/** writer for the main content of the <area>.h file * /
	  	final CFileWriter areaHContent;
	  	/** writer to the <area>.c file. * /
	  	final AreaCWriter areaC;
	    /** buffer for the include of the structure specific files * /
	  	final StatementWriter structureIncludesW;
	    /** writer for the include of the structure specific files * /
	  	final CFileWriter structureIncludes;
	  	/** set of required areas */
	  	final Set<String> reqAreas;
	  	
	  	public AreaContext(File destinationFolder, AreaType area) throws IOException
	  	{
	  		this.area = area;
	  		// create folder for the area
	  		areaFolder = StubUtils.createFolder(destinationFolder, area.getName());
	      // area name in lower case
	      areaNameL = area.getName().toLowerCase();
	      // area name in uper case
	      areaNameU = area.getName().toUpperCase();
	      // create the Writer structures
	      helper = new AreaHelperWriter(this);

	      areaNumberConstName = "AREA_NUMBER";
	      qfAreaNumberConstName = areaNameL + "." + areaNumberConstName;
	      areaVersionConstName = "AREA_VERSION";
	      qfAreaVersionConstName = areaNameL + "." + areaVersionConstName;
	      
	      
	      /*
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
	    	*/
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
	    /** service name in upper case */
	  	final String serviceNameU;
	  	
	  	/** SERVICE_NUMBER */
	  	final String serviceNumberConstName;
	  	/** SERVICE_NAME */
	  	final String serviceNameConstName;
	  	
	  	/** writer to the helper.go file */
	  	final ServiceHelperWriter helper;
	  	/** writer to the consumer.go file */
	  	final ServiceConsumerWriter consumer;
	  	/** writer to the provider.go file */
	  	final ServiceProviderWriter provider;

	  	// list of packages to import
	  	final Set<TypeReference> reqTypes;
	  	
	  	public ServiceContext(AreaContext areaContext, ServiceType service) throws IOException
	  	{
	  		this.areaContext = areaContext;
	    	// files are created in a service specific directory
	      serviceFolder = StubUtils.createFolder(areaContext.areaFolder, service.getName());
	      serviceNameL = service.getName().toLowerCase();
	      serviceNameU = service.getName().toUpperCase();
	      // load service operation details
	      summary = createOperationElementList(service);

	      serviceNumberConstName = "SERVICE_NUMBER";
	      serviceNameConstName = "SERVICE_NAME";
	      
	      // create the Writer structures
	      helper = new ServiceHelperWriter(this);
	      
	      consumer = new ServiceConsumerWriter(this);

	      provider = new ServiceProviderWriter(this);

	      reqTypes = new LinkedHashSet<TypeReference>();
	  	}
	  }

	  /**
	   * Isolate generation of the service helper.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class ServiceHelperWriter extends GoFileWriter {
	  	// name of the service
	  	private final ServiceContext service;

//	  	/** buffer for the main content of the helper.go file */
//	  	final StatementWriter helperContentW;
//	  	/** writer for the main content of the helper.go file */
//	  	final GoFileWriter helperContent;

	  	/** buffer for the list of operations numbers in helper.go file */
	  	final StatementWriter serviceOperationsW;
	  	/** writer for the list of operations numbers in helper.go file */
	  	final GoFileWriter serviceOperations;
	  	
	    /**
	     * Constructor.
	     *
	     * @param service The Service context.
	     * @throws IOException If any problems creating the file.
	     */
	    public ServiceHelperWriter(ServiceContext service) throws IOException
	    {
	    	super();
	    	setPackageName(service.serviceNameL);
	    	this.service = service;
	      Writer file = StubUtils.createLowLevelWriter(service.serviceFolder, "helper", "go");
	      out = new StatementWriter(file);
//	      helperContentW = new StatementWriter();
//	      helperContent = new GoFileWriter(helperContentW);
	      serviceOperationsW = new StatementWriter();
	      serviceOperations = new GoFileWriter(serviceOperationsW);
	      serviceOperations.setPackageName(packageName);
	    }
	
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	      addStatement("package " + packageName);
	      addNewLine();
	      
	      // define imports
	      openImportBlock();
	      addIndent();
	      out.append('"')
	      	.append(BASE_PACKAGE_DEFAULT)
	      	.append("/mal\"");
	      addNewLine();
	      closeImportBlock();
	      addNewLine();
	      
	      // define service constants
	    	openConstBlock();
		    String comment = "standard service identifiers";
		    addSingleLineComment(comment);
	      // SERVICE_NUMBER mal.UShort = <Service number>
		    addVariableDeclare("mal.UShort", service.serviceNumberConstName, Integer.toString(service.summary.getService().getNumber()));
	      // SERVICE_NAME mal.Identifier = mal.Identifier("<Service name>")
		    addIndent();
	      out
	      	.append(service.serviceNameConstName)
	      	.append(" = mal.Identifier(\"")
	      	.append(service.summary.getService().getName())
	      	.append("\")");
	      out.addNewLine();

	      // define service errors
	      ErrorDefinitionList errors = service.summary.getService().getErrors();
	      if (errors != null && errors.getError() != null) {
	      	for (ErrorDefinitionType error : errors.getError()) {
	  	      // ERROR_<name> mal.UInteger = <number>
	      		String errConstName = "ERROR_" + error.getName();
	  	    	addVariableDeclare("mal.UInteger", errConstName, Long.toString(error.getNumber()));
	      	}
	      }
	    }
	    
	    /**
	     * Finalize the file contents and close the underlying writer.
	     * 
	     * @throws IOException
	     */
	    public void close() throws IOException
	    {
	    	// write the operations constants
		    addNewLine();
		    String comment = "standard operation identifiers";
		    addSingleLineComment(comment);
	    	addStatements(serviceOperationsW);
	    	closeConstBlock();
	    	addNewLine();
	    	
	    	super.close();
	    }
	  }

	  /**
	   * Isolate generation of the service consumer.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class ServiceConsumerWriter extends GoFileWriter {
	  	// name of the service
	  	private final ServiceContext service;

	  	/** buffer for the main content of the consumer.go file */
	  	final StatementWriter serviceContentW;
	  	/** writer for the main content of the consumer.go file */
	  	final GoFileWriter serviceContent;
	  	
	    /**
	     * Constructor.
	     *
	     * @param service The Service context.
	     * @throws IOException If any problems creating the file.
	     */
	    public ServiceConsumerWriter(ServiceContext service) throws IOException
	    {
	    	super();
	    	setPackageName(service.serviceNameL);
	    	this.service = service;
	      Writer file = StubUtils.createLowLevelWriter(service.serviceFolder, "consumer", "go");
	      out = new StatementWriter(file);
	      serviceContentW = new StatementWriter();
	      serviceContent = new GoFileWriter(serviceContentW);
	      serviceContent.setPackageName(packageName);
	    }
	
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	      addStatement("package " + packageName);
	      addNewLine();

	      // the list of required imports should follow
	    }
	    
	    /**
	     * Finalize the file contents and close the underlying writer.
	     * 
	     * @throws IOException
	     */
	    public void close() throws IOException
	    {
	    	// write imports
	    	openImportBlock();
	    	Set<String> imports = new LinkedHashSet<String>();
	    	// import errors
	    	addStatement("\"errors\"");
	    	// import mal and mal/api areas first
	    	imports.add("mal");
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();
	    	imports.add("malapi");
	    	addIndent();
	    	out.append("malapi \"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal/api\"");
	    	addNewLine();
	    	// import this service area
	    	// TODO peut etre pas necessaire, et risque si pas utilise ?
	    	String pkg = service.areaContext.areaNameL;
	    	imports.add(pkg);
	    	addIndent();
	    	out.append("\"")
	    		.append(getTypePath(TypeUtils.createTypeReference(service.areaContext.area.getName(), null, "", false)))
	    		.append("\"");
	    	addNewLine();
	    	// do not import this package
	    	String thisPackage = service.serviceNameL;
	    	for (TypeReference type : service.reqTypes) {
	    		if (type.getService() == null) {
	    			pkg = type.getArea().toLowerCase();
	    		} else {
	    			pkg = type.getService().toLowerCase();
	    		}
	    		if (!pkg.equals(thisPackage) && imports.add(pkg)) {
	  	    	addIndent();
	  	    	out.append("\"")
	  	    		.append(getTypePath(type))
	  	    		.append("\"");
	  	    	addNewLine();
	    		}
	    	}
	    	closeImportBlock();
		    addNewLine();
	    	
		    // set the global variables
		    // var Cctx malapi.ClientContext
		    // func Init(cctxin *malapi.ClientContext) error {
		    //   if cctxin == nil {
		    //     return errors.New("Illegal nil client context in Init")
		    //   }
		    //   Cctx = cctxin
		    //   return nil
		    // }
		    addStatement("var Cctx *malapi.ClientContext");
		    openFunction("Init", null);
		    addFunctionParameter("*malapi.ClientContext", "cctxin", true);
		    openFunctionBody(new String[] { "error" });
		    addStatement("if cctxin == nil {", 1);
		    addStatement("return errors.New(\"Illegal nil client context in Init\")");
		    closeBlock();
		    addStatement("Cctx = cctxin");
		    addStatement("return nil");
		    closeFunctionBody();
		    
	    	// write the main content
	    	addStatements(serviceContentW);
	    	
	    	super.close();
	    }
	  }

	  /**
	   * Isolate generation of the service provider.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class ServiceProviderWriter extends GoFileWriter {
	  	// name of the service
	  	private final ServiceContext service;

	  	/** buffer for the main content of the provider.go file */
	  	final StatementWriter serviceContentW;
	  	/** writer for the main content of the provider.go file */
	  	final GoFileWriter serviceContent;
	  	/** buffer for the provider handlers definition */
	  	final StatementWriter servicePHandlersW;
	  	/** writer for the provider handlers definition */
	  	final GoFileWriter servicePHandlers;
	  	/** buffer for the provider interface definition */
	  	final StatementWriter servicePItfW;
	  	/** writer for the provider interface definition */
	  	final GoFileWriter servicePItf;
	  	/** buffer for the provider helper functions */
	  	final StatementWriter servicePHelperW;
	  	/** writer for the provider helper functions */
	  	final GoFileWriter servicePHelper;
	  	
	    /**
	     * Constructor.
	     *
	     * @param service The Service context.
	     * @throws IOException If any problems creating the file.
	     */
	    public ServiceProviderWriter(ServiceContext service) throws IOException
	    {
	    	super();
	    	setPackageName(service.serviceNameL);
	    	this.service = service;
	      Writer file = StubUtils.createLowLevelWriter(service.serviceFolder, "provider", "go");
	      out = new StatementWriter(file);
	      servicePHandlersW = new StatementWriter();
	      servicePHandlers = new GoFileWriter(servicePHandlersW);
	      servicePHandlers.setPackageName(packageName);
	      serviceContentW = new StatementWriter();
	      serviceContent = new GoFileWriter(serviceContentW);
	      serviceContent.setPackageName(packageName);
	      servicePItfW = new StatementWriter();
	      servicePItf = new GoFileWriter(servicePItfW);
	      servicePItf.setPackageName(packageName);
	      servicePHelperW = new StatementWriter();
	      servicePHelper = new GoFileWriter(servicePHelperW);
	      servicePHelper.setPackageName(packageName);
	    }
	
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	      addStatement("package " + packageName);
	      addNewLine();

	      // the list of required imports should follow

	      // open the definition of the internal provider interface
		    String comment = "service provider internal interface";
		    servicePItf.addNewLine();
		    servicePItf.addSingleLineComment(comment);
		    servicePItf.addIndent();
		    servicePItf.addStatement("type ProviderInterface interface {", 1);
	      
		    comment = "service provider structure";
		    serviceContent.addNewLine();
		    serviceContent.addSingleLineComment(comment);
		    serviceContent.openStruct("Provider");
		    serviceContent.addStructField("*malapi.ClientContext", "Cctx");
		    serviceContent.addStructField("ProviderInterface", "provider");
		    serviceContent.closeStruct();
		    
		    comment = "create a service provider";
		    servicePHandlers.addNewLine();
		    servicePHandlers.addSingleLineComment(comment);
		    servicePHandlers.openFunction("NewProvider", null);
		    servicePHandlers.addFunctionParameter("*mal.Context", "ctx", false);
		    servicePHandlers.addFunctionParameter("string", "uri", false);
		    servicePHandlers.addFunctionParameter("ProviderInterface", "providerImpl", true);
		    servicePHandlers.openFunctionBody(new String[] { "*Provider", "error" });
		    // cctx, err := malapi.NewClientContext(ctx, uri)
		    // if err != nil {
		    //   return nil, err
		    // }
		    servicePHandlers.addStatement("cctx, err := malapi.NewClientContext(ctx, uri)");
		    servicePHandlers.addStatement("if err != nil {", 1);
		    servicePHandlers.addStatement("return nil, err", -1);
		    servicePHandlers.addStatement("}");
	    }
	    
	    /**
	     * Finalize the file contents and close the underlying writer.
	     * 
	     * @throws IOException
	     */
	    public void close() throws IOException
	    {
	    	// write imports
	    	openImportBlock();
	    	Set<String> imports = new LinkedHashSet<String>();
	    	// import mal and mal/api areas first
	    	imports.add("errors");
	    	addIndent();
	    	out.append("\"errors\"");
	    	addNewLine();
	    	imports.add("mal");
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();
	    	imports.add("malapi");
	    	addIndent();
	    	out.append("malapi \"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal/api\"");
	    	addNewLine();
	    	// import this service area
	    	// TODO peut etre pas necessaire, et risque si pas utilise ?
	    	String pkg = service.areaContext.areaNameL;
	    	imports.add(pkg);
	    	addIndent();
	    	out.append("\"")
	    		.append(getTypePath(TypeUtils.createTypeReference(service.areaContext.area.getName(), null, "", false)))
	    		.append("\"");
	    	addNewLine();
	    	// do not import this package
	    	String thisPackage = service.serviceNameL;
	    	for (TypeReference type : service.reqTypes) {
	    		if (type.getService() == null) {
	    			pkg = type.getArea().toLowerCase();
	    		} else {
	    			pkg = type.getService().toLowerCase();
	    		}
	    		if (!pkg.equals(thisPackage) && imports.add(pkg)) {
	  	    	addIndent();
	  	    	out.append("\"")
	  	    		.append(getTypePath(type))
	  	    		.append("\"");
	  	    	addNewLine();
	    		}
	    	}
	    	closeImportBlock();
		    addNewLine();
	    	
		    // write the provider internal interface definition
		    servicePItf.addStatement("}", -1, true);
		    addStatements(servicePItfW);
		    addNewLine();
		    
	    	// write the main content
	    	addStatements(serviceContentW);
	    	
	    	// finalize the NewProvider function
	    	// provider := &Provider { cctx, providerImpl }
	    	// return provider, nil
	    	servicePHandlers.addStatement("provider := &Provider { cctx, providerImpl }");
	    	servicePHandlers.addStatement("return provider, nil");
	    	servicePHandlers.closeFunctionBody();
	    	addStatements(servicePHandlersW);
	    	
	    	// add a provider Close function
	    	// func (receiver *Provider) Close() error {
	    	//   if receiver.Cctx != nil {
	    	//     err := receiver.Cctx.Close()
	    	//     if err != nil {
	    	//       return err
	    	//     }
	    	//   }
	    	//   return nil
	    	// }
	    	addNewLine();
	    	openFunction("Close", "*Provider");
	    	openFunctionBody(new String[] {"error"});
	    	addIndent();
	    	out.append("if ")
	    		.append(FUNC_RECEIVER)
	    		.append(".Cctx != nil {");
	    	addNewLine(1);
	    	addIndent();
	    	out.append("err := ")
	    		.append(FUNC_RECEIVER)
	    		.append(".Cctx.Close()");
	    	addNewLine();
	    	addStatement("if err != nil {", 1);
	    	addStatement("return err");
	    	closeBlock();
	    	closeBlock();
	    	addStatement("return nil");
	    	closeFunctionBody();
	    	
	    	addStatements(servicePHelperW);
	    	
	    	super.close();
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
	  	/** capitalized name of the operation */
	  	final String operationName;
	  	/** name of the operation specific consumer structure */
	  	final String consumerStructName;
	  	/** name of the operation specific provider helper structure */
	  	final String helperStructName;
	  	/** operation type */
	  	final String operationType;
	  	/** operation IP */
	  	String operationIp;
	  	/** <OPERATION>_OPERATION_NUMBER */
	  	final String operationNumberConstName;
	  	
	  	boolean isNoError = true;
	  	/** buffer for the error handling of the consumer part */
	  	final StatementWriter consumerErrorHandlingW;
	  	/** writer for the error handling of the consumer part */
	  	final GoFileWriter consumerErrorHandling;
	  	
	    /** fully qualified name of the operation in lower case */
	  	final String qfOpNameL;
	  	
	  	public OperationContext(ServiceContext serviceContext, OperationSummary operation) throws IOException
	  	{
	  		this.serviceContext = serviceContext;
	  		this.operation = operation;
	  		operationName = operation.getName().substring(0, 1).toUpperCase() + operation.getName().substring(1);
	  		consumerStructName = operationName + "Operation";
	  		helperStructName = operationName + "Helper";
	  		operationType = "malapi." + operationTypes.get(operation.getPattern()) + "Operation";
	  		operationIp = operationTypes.get(operation.getPattern());
	  		operationNumberConstName = operation.getName().toUpperCase() + "_OPERATION_NUMBER";

	  		consumerErrorHandlingW = new StatementWriter();
	      consumerErrorHandling = new GoFileWriter(consumerErrorHandlingW);
	  		
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

		  /**
		   * Special constructor for a PUBSUB operation.
		   * 
		   * @param serviceContext
		   * @param operation
		   * @throws IOException
		   */
	  	public OperationContext(ServiceContext serviceContext, OperationSummary operation, boolean isPublisher) throws IOException
	  	{
	  		this.serviceContext = serviceContext;
	  		this.operation = operation;
	  		operationName = operation.getName().substring(0, 1).toUpperCase() + operation.getName().substring(1);
	  		helperStructName = null;
	  		if (isPublisher) {
	  			consumerStructName = operationName + "PublisherOperation";
	  			operationType = "malapi.PublisherOperation";
		  		operationIp = "Publisher";
	  		} else {
	  			consumerStructName = operationName + "SubscriberOperation";
	  			operationType = "malapi.SubscriberOperation";
		  		operationIp = "Subscriber";
	  		}
	  		operationNumberConstName = operation.getName().toUpperCase() + "_OPERATION_NUMBER";

	  		consumerErrorHandlingW = new StatementWriter();
	      consumerErrorHandling = new GoFileWriter(consumerErrorHandlingW);
	      
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
	  	/** name of the consumer operation stage function */
	  	final String consumerOpName;
	  	/** name of the provider helper operation stage function */
	  	final String pHelperOpName;
	  	/** list of the function in parameters */
	  	final List<ParameterDetails> inParameters;
	  	/** list of the function out parameters */
	  	final List<ParameterDetails> outParameters;
	  	/** value to be used for a nilBody */
	  	final String nilBody;
	  	
	  	public OpStageContext(OperationContext opContext, String opStage, boolean isInit, List<TypeInfo> inParameters, List<TypeInfo> outParameters) throws IOException
	  	{
	  		this.opContext = opContext;
	  		this.opStage = opStage;
	  		this.isInit = isInit;
	      // build the fully qualified name of the operation stage function for the C mapping (lower case)
	      // <area>_[<service>_]<operation>_<stage>
	      qfOpStageNameL = opContext.qfOpNameL + "_" + opStage;
	      if ("Send".equals(opStage) ||
	      		"Submit".equals(opStage) ||
	      		"Request".equals(opStage) ||
	      		"Invoke".equals(opStage) ||
	      		"Progress".equals(opStage) ||
	      		"Register".equals(opStage) ||
	      		"Deregister".equals(opStage) ||
	      		"Publish".equals(opStage)) {
	      	consumerOpName = opStage;
	      	pHelperOpName = null;
	      	nilBody = "nil";
	      } else if ("Ack".equals(opStage)) {
	      	consumerOpName = null;
	      	pHelperOpName = opStage;
	      	nilBody = "";
	      } else if ("Update".equals(opStage)) {
	      	consumerOpName = "GetUpdate";
	      	pHelperOpName = opStage;
	      	nilBody = "";
	      } else if ("Reply".equals(opStage)) {
	      	consumerOpName = "GetResponse";
	      	pHelperOpName = opStage;
	      	nilBody = "";
	      } else if ("Notify".equals(opStage)) {
	      	consumerOpName = "GetNotify";
	      	pHelperOpName = null;
	      	nilBody = "";
	      } else {
	      	// TODO PUB/SUB
	      	consumerOpName = null;
	      	pHelperOpName = null;
	      	nilBody = "";
//	      	throw new IllegalArgumentException("unexpected opStage: " + opStage);
	      }
	      String packageName = opContext.serviceContext.serviceNameL;
	      this.inParameters = getParameterDetailsList(inParameters, packageName);
	      this.outParameters = getParameterDetailsList(outParameters, packageName);
	  	}
	  }
	  
	  /**
	   * Holds details about the composite, to be used in code generation.
	   */
	  private class CompositeContext {
	  	final AreaContext areaContext;
	  	final ServiceContext serviceContext;
	  	final CompositeType composite;
	  	final String mapCompNameL; 
	  	final String compositeNameL;
	  	final String compositeNameU;
	  	final String compositeName;
	  	final File folder;
	  	final CompositeWriter writer;
	  	
	  	// list of packages to import
	  	final Set<TypeReference> reqTypes;
	  	ArrayList<String> imports;
	  	
	  	
//	  	final CompositeHWriter compositeH;
//	  	final CompositeCWriter compositeC;
//	    final EncodingCode encodingCode;
//	    final StatementWriter destroyCodeW;
//	    final CFileWriter destroyCode;
	  	boolean holdsOptionalField = false;
	  	boolean holdsEnumField = false;
	  	
	  	public CompositeContext(AreaContext areaContext, ServiceContext serviceContext, CompositeType composite, File folder) throws IOException
	  	{
	  		this.areaContext = areaContext;
	  		this.serviceContext = serviceContext;
	  		this.composite = composite;
	      // composite name in lower case
	  		compositeNameL = composite.getName().toLowerCase();
	      // composite name in upper case
	  		compositeNameU = composite.getName().toUpperCase();
	  		// composite name with a capital first letter
	  		compositeName = compositeNameU.substring(0, 1) + composite.getName().substring(1);
	  		this.folder = folder;
	  		
	      // build the fully qualified composite name for the go mapping (lower case)
	      // <area>_[<service>_]<Composite>
	      StringBuilder buf = new StringBuilder();
	      buf.append(areaContext.areaNameL);
	      buf.append("_");
	      if (serviceContext != null)
	      {
	      	buf.append(serviceContext.serviceNameL);
	      	buf.append("_");
	      }
	      buf.append(composite.getName().toLowerCase());
	      mapCompNameL = composite.getName().substring(0, 1).toUpperCase() + composite.getName().substring(1);
	      
	      // create the writer structures
	      writer = new CompositeWriter(this);

	      reqTypes = new LinkedHashSet<TypeReference>();
	      
	      /*
	      File hFolder = new File(folder,"include");
	      hFolder.mkdirs();
	      File cFolder = new File(folder,"src");
	      cFolder.mkdirs();
	      compositeH = new CompositeHWriter(hFolder, mapCompNameL);
	      compositeC = new CompositeCWriter(cFolder, mapCompNameL);
	      encodingCode = new EncodingCode();
	      destroyCodeW = new StatementWriter();
	      destroyCode = new CFileWriter(destroyCodeW);
	      */
	  	}
	  	
	  	public boolean isAreaType() {
	  		return serviceContext == null;
	  	}
	  }
	  
	  /**
	   * Holds details about a composite field, to be used in code generation.
	   * It could be interesting to make this class derive from CompositeField, however that class is declared final.
	   * 
	   * This class is presented as a simple structure, with no code.
	   */
	  private class CompositeFieldDetails {
	  	boolean isNullable = false;
	  	boolean isAbstractAttribute = false;
	  	boolean isAttribute = false;
	  	boolean isComposite = false;
	  	boolean isEnumeration = false;
	  	boolean isList = false;
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
	  	
	  	TypeInfo sourceType = null;
	  	String targetType = null;
	  	String nilValue = null;
	  	String fieldName = null;
	
	  	public String getShortForm() {
			return this.qfTypeNameL.toUpperCase() +
					(this.isList ? "_LIST_SHORT_FORM" : "_SHORT_FORM");
	  	}
	  	
	  	@Override
	  	public String toString() {
	  		StringBuffer buf = new StringBuffer();
	  		buf.append(this.getClass());
	  		buf.append("{");
	  		buf.append("paramIndex = "+paramIndex);
	  		buf.append(", isLast = "+isLast);
	  		buf.append(", isPolymorph = "+isPolymorph);
	  		buf.append(", isError = "+isError);
	  		buf.append(", isPresenceFlag = "+isPresenceFlag);
	  		buf.append(", isAbstract = "+isAbstract);
	  		buf.append(", isAbstractAttribute = "+isAbstractAttribute);
	  		buf.append(", isAttribute = "+isAttribute);
	  		buf.append(", isComposite = "+isComposite);
	  		buf.append(", isEnumeration = "+isEnumeration);
	  		buf.append(", isList = "+isList);
	  		buf.append(", isPubSub = "+isPubSub);
	  		buf.append(", paramType = "+paramType);
	  		buf.append(", qfTypeNameL = "+qfTypeNameL);
	  		buf.append(", paramName = "+paramName);
	  		buf.append("}");
	  		return buf.toString();
	  	}
	  }
	  
	  /**
	   * Isolate generation of the <area>_helper.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class AreaHelperWriter extends GoFileWriter {
	  	// name of the area
	  	private final AreaContext area;

	  	/** buffer for the main content of the helper.go file */
	  	final StatementWriter helperContentW;
	  	/** writer for the main content of the helper.go file */
	  	final GoFileWriter helperContent;
	  	
	    /**
	     * Constructor.
	     *
	     * @param area The Area context.
	     * @throws IOException If any problems creating the file.
	     */
	    public AreaHelperWriter(AreaContext area) throws IOException
	    {
	    	super();
	    	setPackageName(area.areaNameL);
	    	this.area = area;
	      Writer file = StubUtils.createLowLevelWriter(area.areaFolder, "helper", "go");
	      out = new StatementWriter(file);
	      helperContentW = new StatementWriter();
	      helperContent = new GoFileWriter(helperContentW);
	      helperContent.setPackageName(packageName);
	    }
	
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	    	// define package
	    	// package <package name>
	      addStatement("package " + packageName);
	      addNewLine();
	      
	      // define imports
	      // import (
	    	//   "<basePackage>/mal"
	      // )
	      openImportBlock();
	      addIndent();
	      out.append('"')
	      	.append(area.basePackage)
	      	.append("/mal\"");
	      addNewLine();
	      closeImportBlock();
	      addNewLine();
	      
	      // define area constants in helperContent
	      // const block remains open
	      // const (
	      //   AREA_NUMBER mal.UShort = <Area number>
	      //   AREA_VERSION mal.UOctet = <Area version>
	      //   AREA_NAME = mal.Identifier("<Area name>")
	    	helperContent.openConstBlock();
	    	helperContent.addVariableDeclare("mal.UShort", area.areaNumberConstName, Integer.toString(area.area.getNumber()));
	      helperContent.addVariableDeclare("mal.UOctet", area.areaVersionConstName, Integer.toString(area.area.getVersion()));
	      helperContent.addIndent();
	      helperContentW
	      	.append("AREA_NAME = mal.Identifier(\"")
	      	.append(area.area.getName())
	      	.append("\")");
	      helperContentW.addNewLine();
	      
	      // define area errors in helperContent
	      // close const clock
	      //   ERROR_<name> mal.UInteger = <number>
	      // )
	      ErrorDefinitionList errors = area.area.getErrors();
	      if (errors != null && errors.getError() != null) {
	      	for (ErrorDefinitionType error : errors.getError()) {
	      		String errConstName = "ERROR_" + error.getName();
	  	    	helperContent.addVariableDeclare("mal.UInteger", errConstName, Long.toString(error.getNumber()));
	      	}
	      }
	      helperContent.closeConstBlock();
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
	   * Isolate generation of an abstract composite go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class AbstractCompositeWriter extends GoFileWriter {

	  	private final AreaContext areaContext;
	  	private final ServiceContext serviceContext;
	  	private final CompositeType composite;
	  	
	    /**
	     * Constructor.
	     * @param compCtxt The Composite context.
	     *
	     * @throws IOException If any problems creating the file.
	     */
	    public AbstractCompositeWriter(File folder, AreaContext areaContext, ServiceContext serviceContext, CompositeType composite) throws IOException
	    {
	    	super(folder, composite.getName().toLowerCase());
	    	String packageName = (serviceContext == null ? areaContext.areaNameL : serviceContext.serviceNameL);
	    	setPackageName(packageName);
	    	this.areaContext = areaContext;
	    	this.serviceContext = serviceContext;
	    	this.composite = composite;
	    }
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	    	// declare package name
	    	// package <package name>
	      addStatement("package " + packageName);
	      addNewLine();

	    	// write imports
	    	openImportBlock();
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();
	    	closeImportBlock();
	    	
	    	// An abstract composite type and its list type are turned into marker interfaces
  			String comment = "Defines the abstract composite interfaces.";
  			addNewLine();
  			addSingleLineComment(comment);

  			String compositeName = composite.getName().substring(0, 1).toUpperCase() + composite.getName().substring(1);
	    	// type <Composite> interface {
  			//   mal.Composite
  			//   <Composite>() <Composite>
	    	// }
  			addIndent();
  			out.append("type ")
  				.append(compositeName)
  				.append(" interface {");
  			addNewLine(1);
  			addStatement("mal.Composite");
  			addIndent();
  			out.append(compositeName)
  				.append("() ")
  				.append(compositeName);
  			addNewLine();
  			closeBlock();
  			// var Null<Composite> <Composite> = nil
  			addIndent();
  			out.append("var Null")
					.append(compositeName)
					.append(" ")
					.append(compositeName)
					.append(" = nil");
  			addNewLine();

	    	// type <Composite>List interface {
  			//   mal.ElementList
  			//   <Composite>List() <Composite>List
	    	// }
  			addNewLine();
  			addIndent();
  			out.append("type ")
  				.append(compositeName)
  				.append("List interface {");
  			addNewLine(1);
  			addStatement("mal.ElementList");
  			addIndent();
  			out.append(compositeName)
  				.append("List() ")
  				.append(compositeName)
  				.append("List");
  			addNewLine();
  			closeBlock();
  			// var Null<Composite>List <Composite>List = nil
  			addIndent();
  			out.append("var Null")
					.append(compositeName)
					.append("List ")
					.append(compositeName)
					.append("List = nil");
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
	   * Isolate generation of the <type>_list.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private abstract class TypeListWriter extends GoFileWriter {

	    /**
	     * Constructor.
	     *
	     * @throws IOException If any problems creating the file.
	     */
	    public TypeListWriter() throws IOException
	    {
	    	super();
	    }
	
	    protected abstract boolean isAreaType();
	    protected abstract String getAreaNameL();
	    protected abstract String getBaseTypeName();
	    protected abstract int getTypeShortForm();
	    protected abstract long getShortForm() throws IOException;
	    
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	    	// declare package name
	    	// package <package name>
	      addStatement("package " + packageName);
	      addNewLine();

	    	// write imports
	    	openImportBlock();
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();

	    	// import this service area if required
	    	if (!isAreaType()) {
	    		addIndent();
	    		out.append("\"")
	    			.append(getTypePath(TypeUtils.createTypeReference(getAreaNameL(), null, "", false)))
	    			.append("\"");
	    		addNewLine();
	    	}
	    	
	    	closeImportBlock();
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
	   * Isolate generation of the <composite>.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class CompositeWriter extends GoFileWriter {
	  	// composite context
	  	private final CompositeContext composite;

	  	/** buffer for the main content of the <composite>.go file */
	  	final StatementWriter compositeContentW;
	  	/** writer for the main content of the <composite>.go file */
	  	final GoFileWriter compositeContent;

	  	/** buffer for the encoding content of the <composite>.go file */
	  	final StatementWriter compositeEncodeW;
	  	/** writer for the encoding content of the <composite>.go file */
	  	final GoFileWriter compositeEncode;

	  	/** buffer for the decoding content of the <composite>.go file */
	  	final StatementWriter compositeDecodeW;
	  	/** writer for the decoding content of the <composite>.go file */
	  	final GoFileWriter compositeDecode;

	  	/** buffer for the final decoding content of the <composite>.go file */
	  	final StatementWriter compositeDecodeCreateW;
	  	/** writer for the final decoding content of the <composite>.go file */
	  	final GoFileWriter compositeDecodeCreate;
	  	
	    /**
	     * Constructor.
	     *
	     * @param composite The Composite context.
	     * @throws IOException If any problems creating the file.
	     */
	    public CompositeWriter(CompositeContext composite) throws IOException
	    {
	    	super();
	    	setPackageName(composite.isAreaType() ? composite.areaContext.areaNameL : composite.serviceContext.serviceNameL);
	    	this.composite = composite;
	      Writer file = StubUtils.createLowLevelWriter(composite.folder, composite.compositeNameL, "go");
	      out = new StatementWriter(file);
	      compositeContentW = new StatementWriter();
	      compositeContent = new GoFileWriter(compositeContentW);
	      compositeContent.setPackageName(packageName);
	      compositeEncodeW = new StatementWriter();
	      compositeEncode = new GoFileWriter(compositeEncodeW);
	      compositeEncode.setPackageName(packageName);
	      compositeDecodeW = new StatementWriter();
	      compositeDecode = new GoFileWriter(compositeDecodeW);
	      compositeDecode.setPackageName(packageName);
	      compositeDecodeCreateW = new StatementWriter();
	      compositeDecodeCreate = new GoFileWriter(compositeDecodeCreateW);
	      compositeDecodeCreate.setPackageName(packageName);
	    }
	
	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	    	// declare package name
	    	// package <package name>
	      addStatement("package " + packageName);
	      addNewLine();
	      
	      // the list of required imports should follow

	  		// initialize encoding code in compositeEncode and compositeDecode writers
	  		if (generateTransportMalbinary || generateTransportMalsplitbinary)
	  		{
	  			String mapCompNameU = composite.mapCompNameL.toUpperCase();
	  			String comment = "Encodes this element using the supplied encoder.";
	  			compositeEncode.addNewLine();
	  			compositeEncode.addSingleLineComment(comment);
	  			comment = "@param encoder The encoder to use, must not be null.";
	  			compositeEncode.addSingleLineComment(comment);
	  			// func (<receiver> *<composite>) Encode(encoder mal.Encoder) error {
	  			compositeEncode.openFunction("Encode", "*" + composite.compositeName);
	  			compositeEncode.addFunctionParameter("mal.Encoder", "encoder", true);
	  			compositeEncode.openFunctionBody(new String[] {"error"});
	  			//   specific := encoder.LookupSpecific(<COMPOSITE>_SHORT_FORM)
	  			//   if specific != nil {
	  			//     return specific(<receiver>, encoder)
	  			//   }
	  			compositeEncode.addIndent();
	  			compositeEncodeW.append("specific := encoder.LookupSpecific(")
	  				.append(composite.compositeNameU)
	  				.append("_SHORT_FORM)");
	  			compositeEncode.addNewLine();
	  			compositeEncode.addStatement("if specific != nil {", 1);
	  			compositeEncode.addIndent();
	  			compositeEncodeW.append("return specific(")
	  				.append(GoFileWriter.FUNC_RECEIVER)
	  				.append(", encoder)");
	  			compositeEncode.addNewLine();
	  			compositeEncode.addStatement("}", -1, true);
	  			compositeEncode.addNewLine();

	  			comment = "Decodes an instance of this element type using the supplied decoder.";
	  			compositeDecode.addNewLine();
	  			compositeDecode.addSingleLineComment(comment);
	  			comment = "@param decoder The decoder to use, must not be null.";
	  			compositeDecode.addSingleLineComment(comment);
	  			comment = "@return the decoded instance, may be not the same instance as this Element.";
	  			compositeDecode.addSingleLineComment(comment);
	  			// func (<receiver> *<composite>) Decode(decoder mal.Decoder) (mal.Element, error) {
	  			compositeDecode.openFunction("Decode", "*" + composite.compositeName);
	  			compositeDecode.addFunctionParameter("mal.Decoder", "decoder", true);
	  			compositeDecode.openFunctionBody(new String[] {"mal.Element", "error"});
	  			//   specific := decoder.LookupSpecific(<COMPOSITE>_SHORT_FORM)
	  			//   if specific != nil {
	  			//     return specific(decoder)
	  			//   }
	  			compositeDecode.addIndent();
	  			compositeDecodeW.append("specific := decoder.LookupSpecific(")
	  				.append(composite.compositeNameU)
	  				.append("_SHORT_FORM)");
	  			compositeDecode.addNewLine();
	  			compositeDecode.addStatement("if specific != nil {", 1);
	  			compositeDecode.addStatement("return specific(decoder)");
	  			compositeDecode.addStatement("}", -1, true);
	  			compositeDecode.addNewLine();
	  			
	  			// final create statement in the decode function
	  			compositeDecodeCreate.addIndent();
	  			// var composite = <composite> {
	  			compositeDecodeCreate.addStatement("var composite = " + composite.compositeName + " {", 1);
	  		}
	  		
	    }
	    
	    /**
	     * Finalize the file contents and close the underlying writer.
	     * 
	     * @throws IOException
	     */
	    public void close() throws IOException
	    {
	    	// write imports
	    	openImportBlock();
	    	Set<String> imports = new LinkedHashSet<String>();
	    	// import mal area first
	    	imports.add("mal");
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();
	    	
	    	// import this service area
	    	// TODO peut etre pas necessaire, et risque si pas utilise ?
	    	if (!composite.isAreaType()) {
	    		String pkg = composite.areaContext.areaNameL;
	    		imports.add(pkg);
	    		addIndent();
	    		out.append("\"")
	    			.append(getTypePath(TypeUtils.createTypeReference(composite.areaContext.area.getName(), null, "", false)))
	    			.append("\"");
	    		addNewLine();
	    	}
	    	
	    	String packageName = compositeContent.packageName;
	    	for (TypeReference type : composite.reqTypes) {
	    		String typePackage = type.getArea().toLowerCase();
	    		if (type.getService() != null) {
	    			typePackage = type.getService().toLowerCase();
	    		}
	    		if (!typePackage.equals(packageName) && imports.add(typePackage)) {
	  	    	addIndent();
	  	    	out.append("\"")
	  	    		.append(getTypePath(type))
	  	    		.append("\"");
	  	    	addNewLine();
	    		}
	    	}
	    	closeImportBlock();
	    	
	    	// write local code
	    	addStatements(compositeContentW);
	    	
	    	// finalize and write encoding functions
	    	compositeEncode.addNewLine();
	    	compositeEncode.addStatement("return nil");
	    	compositeEncode.closeFunctionBody();
	    	addStatements(compositeEncodeW);

	    	// TODO eviter la double copie de compositeDecodeCreateW
	    	compositeDecodeCreate.closeBlock();
	    	compositeDecode.addNewLine();
	    	compositeDecode.addStatements(compositeDecodeCreateW);
	    	// return &composite, nil
	    	compositeDecode.addStatement("return &composite, nil");
	    	compositeDecode.closeFunctionBody();
	    	addStatements(compositeDecodeW);
	    	
	    	super.close();
	    }
	
	  }

	  /**
	   * Isolate generation of the <component>_list.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class CompositeListWriter extends TypeListWriter {
	  	// composite context
	  	private final CompositeContext compCtxt;

	    public CompositeListWriter(CompositeContext compCtxt) throws IOException
	    {
	    	super();
	    	setPackageName(compCtxt.isAreaType() ? compCtxt.areaContext.areaNameL : compCtxt.serviceContext.serviceNameL);
	    	this.compCtxt = compCtxt;
	      Writer file = StubUtils.createLowLevelWriter(compCtxt.folder, compCtxt.compositeNameL + "_list", "go");
	      out = new StatementWriter(file);
	    }

	    protected boolean isAreaType() {
	    	return compCtxt.isAreaType();
	    }

	    protected String getAreaNameL() {
	    	return compCtxt.areaContext.areaNameL;
	    }

			protected String getBaseTypeName() {
				return compCtxt.compositeName;
			}

	    protected int getTypeShortForm() {
	    	return -compCtxt.composite.getShortFormPart().intValue();
	    }

	    protected long getShortForm() throws IOException {
	    	return getAbsoluteShortForm(
	  			compCtxt.areaContext.area.getNumber(),
	  			(compCtxt.isAreaType() ? 0 : compCtxt.serviceContext.summary.getService().getNumber()),
	  			compCtxt.areaContext.area.getVersion(),
	  			getTypeShortForm());
	    }
	  }
	  
	  /**
	   * Holds details about the enumeration, to be used in code generation.
	   */
	  private class EnumerationContext {
	  	final AreaContext areaContext;
	  	final ServiceContext serviceContext;
	  	final EnumerationType enumeration;
	  	final String enumNameL;
	  	final String enumNameU;
	  	final String enumName;
	  	final File folder;
	  	final EnumerationWriter writer;

	  	public EnumerationContext(AreaContext areaContext, ServiceContext serviceContext, EnumerationType enumeration, File folder) throws IOException
	  	{
	  		this.areaContext = areaContext;
	  		this.serviceContext = serviceContext;
	  		this.enumeration = enumeration;
	      // enumeration name in lower case
	  		enumNameL = enumeration.getName().toLowerCase();
	      // enumeration name in upper case
	  		enumNameU = enumNameL.toUpperCase();
	  		// enumeration name with a capital first letter
	  		enumName = enumNameU.substring(0, 1) + enumeration.getName().substring(1);
	  		this.folder = folder;

	      // create the writer structures
	      writer = new EnumerationWriter(this);
	  	}

	  	public boolean isAreaType() {
	  		return serviceContext == null;
	  	}
	  }

	  /**
	   * Isolate generation of the <enum>.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class EnumerationWriter extends GoFileWriter {

	  	// enumeration context
	  	private final EnumerationContext enumeration;
	  	
	  	/** buffer for the conversion table OVAL->NVAL */
	  	final StatementWriter nvalTableW;
	  	/** writer for the conversion table OVAL->NVAL */
	  	final GoFileWriter nvalTable;

	    /**
	     * Constructor.
	     *
	     * @param compCtxt The Composite context.
	     * @throws IOException If any problems creating the file.
	     */
	    public EnumerationWriter(EnumerationContext enumeration) throws IOException
	    {
	    	super();
	    	setPackageName(enumeration.isAreaType() ? enumeration.areaContext.areaNameL : enumeration.serviceContext.serviceNameL);
	    	this.enumeration = enumeration;
	      Writer file = StubUtils.createLowLevelWriter(enumeration.folder, enumeration.enumNameL, "go");
	      out = new StatementWriter(file);
	      nvalTableW = new StatementWriter();
	      nvalTable = new GoFileWriter(nvalTableW);
	      nvalTable.setPackageName(packageName);
	    }

	    /**
	     * Initialize file sections.
	     */
	    public void init() throws IOException
	    {
	    	// declare package name
	    	// package <package name>
	      addStatement("package " + packageName);
	      addNewLine();

	    	// write imports
	    	openImportBlock();
	    	addIndent();
	    	out.append("\"")
	    		.append(BASE_PACKAGE_DEFAULT)
	    		.append("/mal\"");
	    	addNewLine();

	    	// import this service area if required
	    	if (!enumeration.isAreaType()) {
	    		addIndent();
	    		out.append("\"")
	    			.append(getTypePath(TypeUtils.createTypeReference(enumeration.areaContext.area.getName(), null, "", false)))
	    			.append("\"");
	    		addNewLine();
	    	}
	    	
	    	closeImportBlock();
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
	   * Isolate generation of the <enum>_list.go file in this class.
	   * 
	   * @author lacourte
	   *
	   */
	  private class EnumerationListWriter extends TypeListWriter {
	  	// composite context
	  	private final EnumerationContext enumCtxt;

	    public EnumerationListWriter(EnumerationContext enumCtxt) throws IOException
	    {
	    	super();
	    	setPackageName(enumCtxt.isAreaType() ? enumCtxt.areaContext.areaNameL : enumCtxt.serviceContext.serviceNameL);
	    	this.enumCtxt = enumCtxt;
	      Writer file = StubUtils.createLowLevelWriter(enumCtxt.folder, enumCtxt.enumNameL + "_list", "go");
	      out = new StatementWriter(file);
	    }

	    protected boolean isAreaType() {
	    	return enumCtxt.isAreaType();
	    }

	    protected String getAreaNameL() {
	    	return enumCtxt.areaContext.areaNameL;
	    }

			protected String getBaseTypeName() {
				return enumCtxt.enumName;
			}

	    protected int getTypeShortForm() {
	    	return - (int) enumCtxt.enumeration.getShortFormPart();
	    }

	    protected long getShortForm() throws IOException {
	    	return getAbsoluteShortForm(
	    			enumCtxt.areaContext.area.getNumber(),
	    			(enumCtxt.isAreaType() ? 0 : enumCtxt.serviceContext.summary.getService().getNumber()),
	    			enumCtxt.areaContext.area.getVersion(),
	    			getTypeShortForm());
	    }
	  }
	  
	}