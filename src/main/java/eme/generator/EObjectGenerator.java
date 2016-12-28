package eme.generator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EcoreFactory;

import eme.model.ExtractedClass;
import eme.model.ExtractedDataType;
import eme.model.ExtractedEnumeration;
import eme.model.ExtractedInterface;
import eme.model.ExtractedMethod;
import eme.model.ExtractedPackage;
import eme.model.ExtractedType;
import eme.model.ExtractedVariable;
import eme.model.IntermediateModel;
import eme.parser.JavaProjectParser;
import eme.properties.ExtractionProperties;

/**
 * This class allows to generate Ecore metamodel components, which are EObjects, with simple method
 * calls. It utilizes the EcoreFactory class.
 * @author Timur Saglam
 */
public class EObjectGenerator {

    private static final Logger logger = LogManager.getLogger(JavaProjectParser.class.getName());
    private final Map<String, EClassifier> createdEClassifiers;
    private final EcoreFactory ecoreFactory;
    private IntermediateModel model;
    private final ExtractionProperties properties;
    private final EDataTypeGenerator typeGenerator;

    /**
     * Basic constructor
     * @param properties is the properties class for the extraction.
     */
    public EObjectGenerator(ExtractionProperties properties) {
        this.properties = properties;
        ecoreFactory = EcoreFactory.eINSTANCE;
        typeGenerator = new EDataTypeGenerator();
        createdEClassifiers = new HashMap<String, EClassifier>();
    }

    /**
     * Clears all the information about the previously created metamodel.
     */
    public void clear() {
        createdEClassifiers.clear();
        model = null;
    }

    /**
     * Generates a EClassifier from an ExtractedType, if the type was not already generated.
     * @param type is the ExtractedType.
     * @return the EClassifier, which is either an EClass, an EInterface or an EEnum.
     */
    public EClassifier generateEClassifier(ExtractedType type) {
        String fullName = type.getFullName();
        if (createdEClassifiers.containsKey(fullName)) {
            return createdEClassifiers.get(fullName);
        }
        EClassifier eClassifier = null;
        if (type.getClass() == ExtractedInterface.class) {
            eClassifier = generateEClass((ExtractedInterface) type);
        } else if (type.getClass() == ExtractedClass.class) {
            eClassifier = generateEClass((ExtractedClass) type);
        } else if (type.getClass() == ExtractedEnumeration.class) {
            eClassifier = generateEEnum((ExtractedEnumeration) type);
        } else {
            throw new UnsupportedOperationException("Not implemented for " + type.getClass());
        }
        eClassifier.setName(type.getName());
        createdEClassifiers.put(fullName, eClassifier);
        return eClassifier;
    }

    /**
     * Generates an EPackage from an extractedPackage. Recursively calls this method to all
     * contained elements.
     * @param extractedPackage is the package the EPackage gets generated from.
     * @param projectName is the name of the project.
     * @return the generated EPackage.
     */
    public EPackage generateEPackage(ExtractedPackage extractedPackage) {
        EPackage ePackage = ecoreFactory.createEPackage();
        if (extractedPackage.isRoot()) { // set root name & prefix:
            ePackage.setName(properties.getDefaultPackageName());
            ePackage.setNsPrefix(properties.getDefaultPackageName());
        } else { // set name & prefix for non root packages:
            ePackage.setName(extractedPackage.getName());
            ePackage.setNsPrefix(extractedPackage.getName());
        }
        ePackage.setNsURI(model.getProjectName() + "/" + extractedPackage.getFullName()); // Set URI
        for (ExtractedPackage subpackage : extractedPackage.getSubpackages()) { // for all packages
            if (!subpackage.isEmpty() || properties.getExtractEmptyPackages()) { // if is allowed to
                ePackage.getESubpackages().add(generateEPackage(subpackage)); // extract
            }
        }
        for (ExtractedType type : extractedPackage.getTypes()) { // for all types
            if (!type.isInnerType() || properties.getExtractNestedTypes()) { // if is allowed to
                ePackage.getEClassifiers().add(generateEClassifier(type)); // extract
            }
        }
        return ePackage;
    }

    /**
     * Sets the intermediate model.
     * @param model is the new model.
     */
    public void setModel(IntermediateModel model) {
        this.model = model;
    }

    /**
     * Adds the operations of an extracted type to a specific List of EOperations.
     * @param type is the extracted type.
     * @param list is the list of EOperations.
     */
    private void addOperations(ExtractedType type, List<EOperation> list) {
        EOperation operation;
        for (ExtractedMethod method : type.getMethods()) {
            operation = ecoreFactory.createEOperation();
            operation.setName(method.getName());
            if (method.getReturnType() != null) {
                operation.setEType(getEDataType(method.getReturnType()));
            }
            addParameters(method, operation.getEParameters());
            list.add(operation);
        }

    }

    /**
     * Adds the parameters of an extracted method to a specific List of EParameters.
     * @param method is the extracted method.
     * @param list is the list of EParameters.
     */
    private void addParameters(ExtractedMethod method, List<EParameter> list) {
        EParameter eParameter;
        for (ExtractedVariable parameter : method.getParameters()) {
            if (!parameter.isArray() && !parameter.isGeneric()) {  // TODO (HIGH) generics & arrays
                eParameter = ecoreFactory.createEParameter();
                eParameter.setName(parameter.getIdentifier());
                eParameter.setEType(getEDataType(parameter));
                list.add(eParameter);
            }
        }
    }

    /**
     * Adds the super class of an extracted class to a specific List of EClasses. If the extracted
     * class has no super class, no EClass is added.
     * @param extractedClass is the extracted class.
     * @param toList is the list of EClasses.
     */
    private void addSuperClass(ExtractedClass extractedClass, List<EClass> toList) {
        String superClassName = extractedClass.getSuperClass();
        if (superClassName != null) { // if has super type
            if (createdEClassifiers.containsKey(superClassName)) { // if is already created:
                toList.add((EClass) createdEClassifiers.get(superClassName)); // get from map.
            } else { // if not already created:
                try { // create from type if found in model.
                    toList.add((EClass) generateEClassifier(model.getType(superClassName)));
                } catch (IllegalArgumentException exception) {
                    logger.warn("Could not generate super class " + superClassName);
                }
            }
        }
    }

    /**
     * Adds all super interfaces of an extracted type to a specific List of EClasses. If the
     * extracted type has no super interfaces, no EClass is added.
     * @param type is the extracted type.
     * @param toList is the list of EClasses.
     */
    private void addSuperInterfaces(ExtractedType type, List<EClass> toList) {
        for (String interfaceName : type.getSuperInterfaces()) { // for all interfaces
            if (createdEClassifiers.containsKey(interfaceName)) { // if already created
                toList.add((EClass) createdEClassifiers.get(interfaceName)); // add
            } else {
                try { // if not already created, try to create with type from model
                    toList.add((EClass) generateEClassifier(model.getType(interfaceName)));
                } catch (IllegalArgumentException exception) {
                    logger.warn("Could not generate super interface " + interfaceName);
                }
            }
        }
    }

    /**
     * Generates an EClass from an ExtractedClass.
     * @param extractedClass is the ExtractedClass.
     * @return the EClass.
     */
    private EClass generateEClass(ExtractedClass extractedClass) {
        EClass eClass = ecoreFactory.createEClass();
        eClass.setAbstract(extractedClass.isAbstract());
        List<EClass> eSuperTypes = eClass.getESuperTypes();
        addSuperClass(extractedClass, eSuperTypes); // get super
        addSuperInterfaces(extractedClass, eSuperTypes);
        addOperations(extractedClass, eClass.getEOperations());
        return eClass; // TODO (MEDIUM) remove duplicate code from class & interface
    }

    /**
     * Generates an EClass from an ExtractedInterface.
     * @param extractedInterface is the ExtractedInterface.
     * @return the EClass.
     */
    private EClass generateEClass(ExtractedInterface extractedInterface) {
        EClass eClass = ecoreFactory.createEClass();
        eClass.setAbstract(true);
        eClass.setInterface(true);
        addSuperInterfaces(extractedInterface, eClass.getESuperTypes());
        addOperations(extractedInterface, eClass.getEOperations());
        return eClass;
    }

    /**
     * Generates an EEnum from an ExtractedEnumeration.
     * @param extractedEnum is the ExtractedEnumeration.
     * @return the EEnum.
     */
    private EEnum generateEEnum(ExtractedEnumeration extractedEnum) {
        EEnum eEnum = ecoreFactory.createEEnum(); // create EEnum
        for (String enumeral : extractedEnum.getEnumerals()) { // for very Enumeral
            EEnumLiteral literal = ecoreFactory.createEEnumLiteral(); // create literal
            literal.setName(enumeral); // set name.
            literal.setValue(eEnum.getELiterals().size()); // set ordinal.
            eEnum.getELiterals().add(literal); // add literal to enum.
        }
        return eEnum;
    }

    private EClassifier getEDataType(ExtractedDataType extractedDataType) {
        String fullName = extractedDataType.getFullTypeName();
        if (createdEClassifiers.containsKey(fullName)) { // if is custom class
            System.out.println("### is custom class: " + extractedDataType.toString()); // TODO
            return createdEClassifiers.get(fullName);
        } else if (typeGenerator.knows(extractedDataType)) { // if is basic type or already known
            System.out.println("### is already known: " + extractedDataType.toString()); // TODO
            return typeGenerator.get(extractedDataType); // access EDataType
        } // if its an external type
        System.out.print("### will be created: " + extractedDataType.toString()); // TODO
        System.out.println("      " + createdEClassifiers.toString()); // TODO
        return typeGenerator.create(extractedDataType); // create new EDataType
    }
}