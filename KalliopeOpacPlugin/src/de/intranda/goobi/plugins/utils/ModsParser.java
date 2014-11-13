package de.intranda.goobi.plugins.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import ugh.dl.DocStruct;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.IncompletePersonObjectException;
import ugh.exceptions.MetadataTypeNotAllowedException;

public class ModsParser {

    /** Logger for this class. */
    private static final Logger logger = Logger.getLogger(ModsParser.class);

    private Prefs prefs;
    private List<String> anchorMetadataList;
    private Document mapDoc;
    private HashMap<String, String> personRoleMap;
    private boolean writeLogical, writePhysical;
    private DocStruct dsLogical, dsPhysical, dsAnchor;
    private String separator = " ";
    private Configuration pluginConfiguration;

    public static final Namespace NS_MODS = Namespace.getNamespace("mods", "http://www.loc.gov/mods/v3");
    private boolean mergeXPathInstances = false;
    private boolean mergeXPaths = true;
    private static final String defaultPersonRole = "Author";

    /**
     * 
     * 
     * @param prefs The ruleset used for this import
     * @param modsMappingFile The file containing the rules for mapping mods to goobi
     * @param pluginConfig The config for this plugin
     * @param anchorMetadataList The list of metadata to write in the anchor DocStruct, or null if all Metadata should be written to anchor
     * @throws ParserException 
     * @throws IOException
     * @throws JDOMException
     */
    public ModsParser(Prefs prefs, File modsMappingFile, Configuration pluginConfig, List<String> anchorMetadataList) throws ParserException {
        this.prefs = prefs;
        this.anchorMetadataList = anchorMetadataList;
        this.pluginConfiguration = pluginConfig;
        try {
            mapDoc = new SAXBuilder().build(modsMappingFile);
        } catch (JDOMException | IOException e) {
           throw new ParserException(e);
        }
        fillPersonRoleMap();
    }

    /**
     * 
     * @param dsLogical logical docStruct of TopStruct
     * @param dsAnchor logical docStruct of Anchor
     * @param dsPhysical main physical docStruct
     * @param eleMods mods:mods element containing the mods metadata
     */
    public void parseModsSection(DocStruct dsLogical, DocStruct dsAnchor, DocStruct dsPhysical, Element eleMods) {

        this.dsLogical = dsLogical;
        this.dsPhysical = dsPhysical;
        this.dsAnchor = dsAnchor;

        //create temporary mods-document for xpath-nodes
        Document modsDoc = new Document();
        Element eleNewMods = (Element) eleMods.clone();
        modsDoc.setRootElement(eleNewMods);

        //iterate over all entries of mods_map
        for (Object obj : mapDoc.getRootElement().getChildren("metadata", null)) {
            if (obj instanceof Element) {
                Element eleMetadata = (Element) obj;

                setStructsToWrite(eleMetadata);
                setSeparator(eleMetadata);
                setMergeXPaths(eleMetadata);
                setMergeXPathInstances(eleMetadata);

                String mdName = eleMetadata.getChildTextTrim("name", null);
                MetadataType mdType = prefs.getMetadataTypeByName(mdName);
                if (mdType != null) {
                    List<Element> eleXpathList = eleMetadata.getChildren("xpath", null);
                    if (mdType.getIsPerson()) {
                        writePersonXPaths(eleXpathList, mdType, modsDoc);
                    } else {
                        writeMetadataXPaths(eleXpathList, mdType, modsDoc);
                    }
                }
            }
        }

    }

    private void setSeparator(Element eleMetadata) {
        if (eleMetadata.getAttribute("separator") != null) {
            separator = eleMetadata.getAttributeValue("separator");
        }
    }

    private void setStructsToWrite(Element eleMetadata) {
        if (eleMetadata.getAttribute("logical") != null && eleMetadata.getAttributeValue("logical").equalsIgnoreCase("true")) {
            writeLogical = true;
        } else {
            writeLogical = false;
        }
        if (eleMetadata.getAttribute("physical") != null && eleMetadata.getAttributeValue("physical").equalsIgnoreCase("true")) {
            writePhysical = true;
        } else {
            writePhysical = false;
        }
    }

    private void writeMetadataXPaths(List<Element> eleXpathList, MetadataType mdType, Document modsDoc) {

        List<String> valueList = new ArrayList<String>();
        for (Element eleXpath : eleXpathList) {
            String query = eleXpath.getTextTrim();
            XPathExpression<Object> xpath = XPathFactory.instance().compile(query, Filters.fpassthrough(), null, NS_MODS);
            List<Object> nodeList = xpath.evaluate(modsDoc);
            setMergeXPathInstances(eleXpath);

            //read values
            if (nodeList != null) {
                List<String> nodeValueList = getMetadataNodeValues(nodeList, mdType);
                if (mergeXPathInstances) {
                    StringBuilder sb = new StringBuilder();
                    for (String string : nodeValueList) {
                        sb.append(string);
                        sb.append(separator);
                    }
                    valueList.add(sb.substring(0, sb.length() - separator.length()));
                } else {
                    if (mergeXPaths) {
                        int count = 0;
                        for (String value : nodeValueList) {
                            if (value != null && valueList.size() <= count) {
                                valueList.add(value);
                            } else if (value != null) {
                                value = valueList.get(count) + separator + value;
                                valueList.set(count, value);
                            }
                            count++;
                        }
                    } else {
                        valueList.addAll(nodeValueList);
                    }
                }
            }
        }

        //create and write medadata
        for (String value : valueList) {
            try {
                Metadata md = new Metadata(mdType);
                md.setValue(value);
                writeMetadata(md);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to create metadata " + mdType.getName());
            }

        }

    }

    private void writePersonXPaths(List<Element> eleXpathList, MetadataType mdType, Document modsDoc) {

        for (Element eleXpath : eleXpathList) {
            String query = eleXpath.getTextTrim();
            XPathExpression<Element> xpath = XPathFactory.instance().compile(query, Filters.element(), null, NS_MODS);
            List<Element> nodeList = xpath.evaluate(modsDoc);
            if (nodeList != null) {
                writePersonNodeValues(nodeList, mdType);
            }
        }
    }

    private List<String> getMetadataNodeValues(List nodeList, MetadataType mdType) {

        List<String> valueList = new ArrayList<String>();

        for (Object objValue : nodeList) {
            String value = null;
            if (objValue instanceof Element) {
                Element eleValue = (Element) objValue;
                
                logger.debug("mdType: " + mdType.getName() + "; Value: " + eleValue.getTextTrim());
                value = getElementValue(eleValue, ", ");
                //                                      value = eleValue.getTextTrim();
            } else if (objValue instanceof Attribute) {
                Attribute atrValue = (Attribute) objValue;
                logger.debug("mdType: " + mdType.getName() + "; Value: " + atrValue.getValue());
                value = atrValue.getValue();
            }

            valueList.add(value);
        }

        return valueList;
    }

    private void writePersonNodeValues(List<Element> xPathNodeList, MetadataType mdType) {
        for (Element node : xPathNodeList) {
            String displayName = "";
            String firstName = "";
            String lastName = "";
            String termsOfAddress = "";
            String date = "";
            String roleTerm = "";
            String typeName = defaultPersonRole;

            //get subelements of person
            for (Object o : node.getChildren()) {
                if (o instanceof Element) {
                    Element eleNamePart = (Element) o;
                    if (eleNamePart.getName().contentEquals("role")) {
                        Element eleRoleTerm = eleNamePart.getChild("roleTerm", null);
                        if (eleRoleTerm != null) {
                            roleTerm = eleRoleTerm.getValue();
                        }
                    } else {
                        String type = eleNamePart.getAttributeValue("type");
                        if (type == null || type.isEmpty()) {
                            // no type
                            displayName = eleNamePart.getValue();
                        } else if (type.contentEquals("date")) {
                            // do nothing?
                        } else if (type.contentEquals("termsOfAddress")) {
                            termsOfAddress = eleNamePart.getValue();
                        } else if (type.contentEquals("date")) {
                            date = eleNamePart.getValue();
                        } else if (type.contentEquals("given")) {
                            firstName = eleNamePart.getValue();
                        } else if (type.contentEquals("family")) {
                            lastName = eleNamePart.getValue();
                        }
                    }
                }
            }

            // set metadata type to role
            mdType = setPersonRoleTerm(roleTerm, typeName);

            //get first and last name from displayName if required
            if (firstName.isEmpty() && lastName.isEmpty() && displayName.contains(",")) {
                String[] nameSplit = displayName.split("[,]");
                if (nameSplit.length > 0 && StringUtils.isEmpty(lastName)) {
                    lastName = nameSplit[0].trim();
                }
                if (nameSplit.length > 1 && StringUtils.isEmpty(firstName)) {
                    for (int i = 1; i < nameSplit.length; i++) {
                        firstName += nameSplit[i].trim() + ", ";
                    }
                    firstName = firstName.substring(0, firstName.length() - 2);
                }
            } else if (lastName.isEmpty()) {
                lastName = displayName;
            }

            //create and write metadata
            if (StringUtils.isNotEmpty(lastName)) {
                Person person = null;
                try {
                    person = new Person(mdType);
                    person.setFirstname(firstName);
                    person.setLastname(lastName);
                    person.setRole(mdType.getName());
                } catch (MetadataTypeNotAllowedException e) {
                    logger.error("Failed to create person metadata " + mdType.getName());
                }

                if (person != null) {
                    writePerson(person);
                }
            }
        }

    }

    private MetadataType setPersonRoleTerm(String roleTerm, String typeName) {
        MetadataType mdType;
        if (roleTerm != null && !roleTerm.isEmpty() && personRoleMap != null) {
            roleTerm = roleTerm.replaceAll("\\.", "");
            typeName = personRoleMap.get(roleTerm.toLowerCase());
            if (typeName == null) {
                String[] parts = roleTerm.split(" ");
                if (parts != null && parts.length > 0) {
                    typeName = personRoleMap.get(parts[0].toLowerCase());
                }
                if (typeName == null) {
                    typeName = defaultPersonRole;
                }
            }
        }
        mdType = prefs.getMetadataTypeByName(typeName);
        return mdType;
    }

    private void writePerson(Person person) {

        if (writeLogical) {

            try {
                dsLogical.addPerson(person);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to write person " + person.getType().getName() + " to logical topStruct: " + e.getMessage());
            } catch (IncompletePersonObjectException e) {
                logger.error("Failed to write person " + person.getType().getName() + " to logical topStruct: " + e.getMessage());
            }

            if (dsAnchor != null && (anchorMetadataList == null || anchorMetadataList.contains(person.getType().getName()))) {
                try {
                    dsAnchor.addPerson(person);
                } catch (MetadataTypeNotAllowedException e) {
                    logger.warn("Failed to write person " + person.getType().getName() + " to logical anchor: " + e.getMessage());

                } catch (IncompletePersonObjectException e) {
                    logger.warn("Failed to write person " + person.getType().getName() + " to logical anchor: " + e.getMessage());

                }
            }

        }

        if (writePhysical) {
            try {
                dsPhysical.addPerson(person);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to write person " + person.getType().getName() + " to physical topStruct: " + e.getMessage());

            } catch (IncompletePersonObjectException e) {
                logger.error("Failed to write person " + person.getType().getName() + " to physical topStruct: " + e.getMessage());

            }
        }

    }

    private void writeMetadata(Metadata metadata) {

        if (writeLogical) {

            try {
                dsLogical.addMetadata(metadata);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to write metadata " + metadata.getType().getName() + " to logical topStruct: " + e.getMessage());
            } catch (IncompletePersonObjectException e) {
                logger.error("Failed to write metadata " + metadata.getType().getName() + " to logical topStruct: " + e.getMessage());
            }

            if (dsAnchor != null && (anchorMetadataList == null || anchorMetadataList.contains(metadata.getType().getName()))) {
                try {
                    dsAnchor.addMetadata(metadata);
                } catch (MetadataTypeNotAllowedException e) {
                    logger.warn("Failed to write metadata " + metadata.getType().getName() + " to logical anchor: " + e.getMessage());

                } catch (IncompletePersonObjectException e) {
                    logger.warn("Failed to write metadata " + metadata.getType().getName() + " to logical anchor: " + e.getMessage());

                }
            }

        }

        if (writePhysical) {
            try {
                dsPhysical.addMetadata(metadata);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to write metadata " + metadata.getType().getName() + " to physical topStruct: " + e.getMessage());

            } catch (IncompletePersonObjectException e) {
                logger.error("Failed to write metadata " + metadata.getType().getName() + " to physical topStruct: " + e.getMessage());

            }
        }

    }

    private static String getElementValue(Element eleValue, String separator) {
        if (separator == null) {
            separator = " ";
        }
        String value = eleValue.getTextTrim() == null ? "" : eleValue.getTextTrim();
        List<Element> namePartList = eleValue.getChildren("namePart", null);
        if (namePartList != null && !namePartList.isEmpty()) {
            for (Element element : namePartList) {
                String namePart = element.getTextTrim();
                if (namePart != null && !namePart.isEmpty()) {
                    value += separator + namePart;
                }
            }
            if (value.startsWith(separator)) {
                value = value.substring(separator.length());
            }
        }
        return value;
    }

    private void fillPersonRoleMap() {
        if (mapDoc != null) {
            Element elePerson = getElementBySubElement("name", "Author", mapDoc.getRootElement());
            if (elePerson != null) {
                Element eleMapping = elePerson.getChild("roleMapping");
                if (eleMapping != null && !eleMapping.getChildren().isEmpty()) {
                    personRoleMap = new HashMap<String, String>();
                    for (Object o : eleMapping.getChildren()) {
                        if (o instanceof Element) {
                            Element person = (Element) o;
                            String mdName = person.getChildText("name");
                            for (Object o2 : person.getChildren("roleTerm")) {
                                if (o2 instanceof Element) {
                                    String roleTerm = ((Element) o2).getValue();
                                    personRoleMap.put(roleTerm, mdName);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static Element getElementBySubElement(String eleName, String eleValue, Element parent) {
        for (Object o : parent.getChildren()) {
            if (o instanceof Element) {
                Element child = (Element) o;
                if (child.getChild(eleName) != null && child.getChild(eleName).getValue().contentEquals(eleValue)) {
                    return child;
                }
            }
        }
        return null;
    }

    public boolean isMergeXPathInstances() {
        return mergeXPathInstances;
    }

    public void setMergeXPathInstances(Element ele) {
        String value = ele.getAttributeValue("mergeXPathInstances");
        if ("true".equals(value)) {
            mergeXPathInstances = true;
        } else if ("false".equals(value)) {
            mergeXPathInstances = false;
        }
    }

    public boolean isMergeXPaths() {
        return mergeXPaths;
    }

    public void setMergeXPaths(Element ele) {
        String value = ele.getAttributeValue("mergeXPaths");
        if ("true".equals(value)) {
            mergeXPaths = true;
        } else if ("false".equals(value)) {
            mergeXPaths = false;
        }
    }

    /**
     * Returns the first element "typeOfResource" withing the recordElement, or null if no such element was found
     * 
     * @param recordElement
     * @return
     */
    public Element getTypeOfResource(Element recordElement) {
        Iterator<Element> iterator = recordElement.getDescendants(Filters.element("typeOfResource", NS_MODS));
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
        //        throw new ParserException("No element \"typeOfResource\" found in mods section.");
    }
    
    public static List<Object> find(String xPathQuery, Object source) {
        XPathExpression<Object> xpath = XPathFactory.instance().compile(xPathQuery);
        List<Object> nodeList = xpath.evaluate(source);
        return nodeList;
    }
    
    public static List<Element> findElement(String xPathQuery, Object source) {
        XPathExpression<Element> xpath = XPathFactory.instance().compile(xPathQuery, Filters.element());
        List<Element> nodeList = xpath.evaluate(source);
        return nodeList;
    }
    
    public static List<Attribute> findAttribute(String xPathQuery, Object source) {
        XPathExpression<Attribute> xpath = XPathFactory.instance().compile(xPathQuery, Filters.attribute());
        List<Attribute> nodeList = xpath.evaluate(source);
        return nodeList;
    }

    public String getAchorID(Object modsSource) {
        String query = "relatedItem[@type='host']/identifier";
        List<Element> list = findElement(query, modsSource);
        if(list.size() > 0) {
            if(list.size() > 1) {
                logger.warn("Found more than one instance of anchor identifier");
            }
            return list.get(0).getValue();
        } else {
            return null;
        }
        
    }
    
    public static class ParserException extends Exception {

        public ParserException() {
            super();
        }

        public ParserException(String message) {
            super(message);
        }

        public ParserException(Throwable cause) {
            super(cause);
        }

    }

}
