package de.intranda.goobi.plugins.utils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

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
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;

import de.intranda.utils.SimpleMatrix;
import de.intranda.utils.SimpleMatrix.MatrixFiller;
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
    private String ignoreRegex = "";
    private String datePattern;
    private String dateInputPattern;
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
                setIgnoreRegex(eleMetadata);
                setDatePatterns(eleMetadata);

                String mdName = eleMetadata.getChildTextTrim("name", null);
                if ("Person".equals(mdName)) {
                    List<Element> eleXpathList = eleMetadata.getChildren("xpath", null);
                    writePersonXPaths(eleXpathList, modsDoc);
                } else {
                    MetadataType mdType = prefs.getMetadataTypeByName(mdName);
                    if (mdType != null) {
                        List<Element> eleXpathList = eleMetadata.getChildren("xpath", null);
                        if (mdType.getIsPerson()) {
                            writePersonXPaths(eleXpathList, modsDoc);
                        } else {
                            writeMetadataXPaths(eleXpathList, mdType, modsDoc);
                        }
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

        List<Metadata> valueList = new ArrayList<Metadata>();
        List<List<Object>> nodeList = new ArrayList<List<Object>>();

        for (Element eleXpath : eleXpathList) {
            String query = eleXpath.getTextTrim();
            XPathExpression<Object> xpath = XPathFactory.instance().compile(query, Filters.fpassthrough(), null, NS_MODS);
            List<Object> xPathNodeList = new ArrayList<Object>(xpath.evaluate(modsDoc));
            xPathNodeList.add(0, eleXpath);
            nodeList.add(xPathNodeList);
        }

        SimpleMatrix<Object> nodeMatrix = new SimpleMatrix<Object>(nodeList);
        //        logger.debug("Created xpath node matrix\n" + nodeMatrix);
        SimpleMatrix<Metadata> mdMatrix = convertToMetadataMatrix(nodeMatrix, mdType);
        List<Metadata> mdList = convertToMetadataList(mdMatrix);

        for (Metadata metadata : mdList) {
            if (metadata != null) {
                writeMetadata(metadata);
            }
        }

    }

    /**
     * Collapses the matrix into a list: One entry for each matrix-position if mergeXPaths=false; one for each column otherwise
     * 
     * @param mdMatrix
     * @return
     */
    private List<Metadata> convertToMetadataList(SimpleMatrix<Metadata> mdMatrix) {
        List<Metadata> mdList = new ArrayList<Metadata>();
        if (mergeXPaths) {
            for (int i = 0; i < mdMatrix.getColumns(); i++) {
                Metadata columnMd = mergeMetadata(mdMatrix.getColumnAsList(i));
                mdList.add(columnMd);
            }
        } else {
            for (int i = 0; i < mdMatrix.getRows(); i++) {
                for (int j = 0; j < mdMatrix.getColumns(); j++) {
                    mdList.add(mdMatrix.get(i, j));
                }
            }
        }
        return mdList;
    }

    /**
     * Creates a matrix of metadata, one row for each xPath, each row containing the values of different hits with the same xPath. If the xPath calls
     * to merge the hits into one, a single metadata is written into the first column
     * 
     * @param nodeMatrix
     * @param mdType
     * @return
     */
    private SimpleMatrix<Metadata> convertToMetadataMatrix(final SimpleMatrix<Object> nodeMatrix, final MetadataType mdType) {
        SimpleMatrix<Metadata> mdMatrix = new SimpleMatrix<Metadata>(nodeMatrix.getRows(), nodeMatrix.getColumns() - 1);
        MatrixFiller<Metadata> filler = new MatrixFiller<Metadata>() {

            @Override
            public Metadata calculateValue(int row, int column) {
                Element eleXPath = (Element) nodeMatrix.get(row, 0);
                boolean mergeRow = isMergeXPathInstances(eleXPath);
                if (mergeRow && column > 0) {
                    return null;
                } else {
                    List<Object> xPathNodeList = nodeMatrix.getRowAsList(row);
                    if (xPathNodeList.size() < 2) {
                        return null;
                    } else {
                        xPathNodeList = xPathNodeList.subList(1, xPathNodeList.size());
                        if (!mergeRow) { //if we don't merge the whole row, we only want one single metadata node
                            xPathNodeList = xPathNodeList.subList(column, column + 1);
                        }
                        List<Metadata> xPathMetadata = getMetadataNodeValues(xPathNodeList, mdType);
                        Metadata metadata = mergeMetadata(xPathMetadata);
                        return metadata;
                    }
                }

            }
        };
        mdMatrix.fill(filler);
        return mdMatrix;
    }

    private void writePersonXPaths(List<Element> eleXpathList, Document modsDoc) {

        for (Element eleXpath : eleXpathList) {
            String query = eleXpath.getTextTrim();
            XPathExpression<Element> xpath = XPathFactory.instance().compile(query, Filters.element(), null, NS_MODS);
            List<Element> nodeList = xpath.evaluate(modsDoc);
            if (nodeList != null) {
                writePersonNodeValues(nodeList);
            }
        }
    }

    private List<Metadata> getMetadataNodeValues(List nodeList, MetadataType mdType) {

        List<Metadata> valueList = new ArrayList<Metadata>();

        for (Object objValue : nodeList) {
            try {
                Metadata md = new Metadata(mdType);
                String value = null;
                if (objValue instanceof Element) {
                    Element eleValue = (Element) objValue;
                    createAuthorityFile(md, eleValue);
                    logger.debug("mdType: " + mdType.getName() + "; Value: " + eleValue.getTextTrim());
                    value = getElementValue(eleValue, ", ");
                    //                                      value = eleValue.getTextTrim();
                } else if (objValue instanceof Attribute) {
                    Attribute atrValue = (Attribute) objValue;
                    logger.debug("mdType: " + mdType.getName() + "; Value: " + atrValue.getValue());
                    value = atrValue.getValue();
                }
                if (value != null && !value.trim().isEmpty()) {
                    md.setValue(value);
                    valueList.add(md);
                }
            } catch (MetadataTypeNotAllowedException e) {
                logger.error("Failed to create metadata " + mdType.getName());
            }
        }

        return valueList;
    }

    private Metadata mergeMetadata(List<Metadata> mdList) {
        if (mdList == null || mdList.isEmpty() || mdList.get(0) == null) {
            return null;
        } else {
            MetadataType type = mdList.get(0).getType();
            StringBuilder valueBuilder = new StringBuilder();
            Metadata newMetadata;
            try {
                newMetadata = new Metadata(type);
            } catch (MetadataTypeNotAllowedException e) {
                return null; //should never happen
            }
            for (Metadata metadata : mdList) {
                valueBuilder.append(metadata.getValue());
                valueBuilder.append(separator);
                if (metadata.getAuthorityValue() != null) {
                    newMetadata.setAuthorityValue(metadata.getAuthorityValue());
                }
                if (metadata.getAuthorityID() != null) {
                    newMetadata.setAuthorityID(metadata.getAuthorityID());
                }
                if (metadata.getAuthorityURI() != null) {
                    newMetadata.setAuthorityURI(metadata.getAuthorityURI());
                }
            }
            String value = valueBuilder.toString();
            value = value.substring(0, value.length() - separator.length());
            newMetadata.setValue(value);
            return newMetadata;
        }
    }

    private void createAuthorityFile(Metadata md, Element eleValue) {
        if (eleValue.getAttribute("valueURI") != null) {
            String authorityURI = eleValue.getAttributeValue("valueURI");
            String authorityValue = authorityURI.substring(authorityURI.lastIndexOf("/") + 1);
            authorityURI = authorityURI.substring(0, authorityURI.lastIndexOf("/") + 1);
            String authorityID = eleValue.getAttributeValue("authority");
            if (StringUtils.isBlank(authorityID)) {
                authorityID = Path.of(URI.create(authorityURI).getPath()).getName(0).toString();
            }
            md.setAuthorityFile(authorityID, authorityURI, authorityValue);
        }
    }

    private void writePersonNodeValues(List<Element> xPathNodeList) {
        for (Element node : xPathNodeList) {
            String displayName = "";
            String firstName = "";
            String lastName = "";
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
                        } else if (type.contentEquals("given")) {
                            firstName = eleNamePart.getValue();
                        } else if (type.contentEquals("family")) {
                            lastName = eleNamePart.getValue();
                        }
                    }
                }
            }

            // set metadata type to role
            MetadataType mdType = setPersonRoleTerm(roleTerm, typeName);

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
                    createAuthorityFile(person, node);
                } catch (MetadataTypeNotAllowedException e) {
                    logger.error("Failed to create person " + typeName + " " + roleTerm);
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

        person.setLastname(person.getLastname().replaceAll(ignoreRegex, "").trim());
        person.setFirstname(person.getFirstname().replaceAll(ignoreRegex, "").trim());

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

        metadata.setValue(metadata.getValue().replaceAll(ignoreRegex, "").trim());
        if (getDatePattern() != null) {
            metadata.setValue(getFormattedDate(metadata.getValue(), getDateInputPattern(), getDatePattern()));
        }

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

    public static String getFormattedDate(String value, String inputPattern, String outputPattern) {
        String inputSeparatorPattern = "[\\-_/]";
        String outputSeparator = "-";
        String[] valueParts = value.split(inputSeparatorPattern);
        if (valueParts.length > 1) {
            StringBuilder partsBuilder = new StringBuilder();
            for (String part : valueParts) {
                partsBuilder.append(getFormattedDate(part, inputPattern, outputPattern));
                partsBuilder.append(outputSeparator);
            }
            return partsBuilder.substring(0, partsBuilder.length() - outputSeparator.length());
        } else {
            try {
                List<DateTimeParser> inputParsers = new ArrayList<DateTimeParser>();
                String[] inputPatterns = inputPattern.split("\\|\\|");
                for (String pattern : inputPatterns) {
                    if (!pattern.trim().isEmpty()) {
                        inputParsers.add(DateTimeFormat.forPattern(pattern.trim()).getParser());
                    }
                }
                DateTimeFormatter inputFormat =
                        new DateTimeFormatterBuilder().append(null, inputParsers.toArray(new DateTimeParser[inputParsers.size()])).toFormatter();
                DateTimeFormatter outputFormat = DateTimeFormat.forPattern(outputPattern);
                DateTime date = inputFormat.parseDateTime(value.trim());
                return date.toString(outputFormat);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                System.out.println(value + " cannot be parsed as date: " + e.getMessage());
                return value;
            }
        }
    }

    //    protected static String getFormattedDate(String value) {
    //        String [] valueParts = value.split("[/\\-_]");
    //        if(valueParts.length > 1) {
    //            StringBuilder partsBuilder = new StringBuilder();
    //            for (String part : valueParts) {
    //                partsBuilder.append(getFormattedDate(part));
    //                partsBuilder.append("-");
    //            }
    //            return partsBuilder.substring(0, partsBuilder.length()-1);
    //        } else if(value.matches("\\d+") && value.length() == 8){
    //            
    //            DateTimeFormatter inputFormat = DateTimeFormat.forPattern("yyyyMMdd");
    //            DateTimeFormatter outputFormat = DateTimeFormat.forPattern("dd.MM.yyyy");
    //            DateTime date = inputFormat.parseDateTime(value);
    //            return date.toString(outputFormat);
    //        } else {
    //            return value;
    //        }
    //    }

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
            Element elePerson = getElementBySubElement("name", "Person", mapDoc.getRootElement());
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

    public void setIgnoreRegex(Element ele) {
        String value = ele.getAttributeValue("ignoreRegex");
        if (value != null) {
            ignoreRegex = value;
        } else {
            ignoreRegex = "";
        }
    }

    public boolean isMergeXPathInstances(Element ele) {
        String value = ele.getAttributeValue("mergeXPathInstances");
        if ("true".equals(value)) {
            return true;
        } else if ("false".equals(value)) {
            return false;
        } else {
            return isMergeXPathInstances();
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
        XPathExpression<Element> xpath = XPathFactory.instance().compile(xPathQuery, Filters.element(), null, NS_MODS);
        List<Element> nodeList = xpath.evaluate(source);
        return nodeList;
    }

    public static List<Attribute> findAttribute(String xPathQuery, Object source) {
        XPathExpression<Attribute> xpath = XPathFactory.instance().compile(xPathQuery, Filters.attribute());
        List<Attribute> nodeList = xpath.evaluate(source);
        return nodeList;
    }

    public String getAchorID(Object modsSource) {
        String query = "mods:relatedItem[@type='host']/mods:recordInfo/mods:recordIdentifier";
        List<Element> list = findElement(query, modsSource);
        if (list.size() > 0) {
            if (list.size() > 1) {
                logger.warn("Found more than one instance of anchor identifier");
            }
            return list.get(0).getValue();
        } else {
            return null;
        }
    }

    public static String determineDocType(Element recordElement, LinkedHashMap<String, Map<String, String>> docTypeMappings, String defaultType) {
        for (Entry<String, Map<String, String>> entry : docTypeMappings.entrySet()) {
            String docTypeName = entry.getKey();
            Map<String, String> conditions = entry.getValue();
            boolean match = true;
            for (Entry<String, String> condition : conditions.entrySet()) {
                String xpath = condition.getKey();
                String value = condition.getValue();
                List<Element> list = findElement(xpath, recordElement);
                if (list.isEmpty()) {
                    match = StringUtils.isBlank(value);
                } else {
                    match = list.stream().anyMatch(ele -> Objects.equals(value, ele.getText()));
                }
                if (!match) {
                    break; //leave the loop if no match. In this case mapping doesn't apply and we don't need to check the other conditions
                }
            }
            if (match) {
                return docTypeName;
            }
        }
        return defaultType;
    }

    private void setDatePatterns(Element eleMetadata) {
        datePattern = eleMetadata.getAttributeValue("datePattern");
        dateInputPattern = eleMetadata.getAttributeValue("dateInputPattern");

        if (datePattern != null && dateInputPattern == null) {
            dateInputPattern = "yyyyMMdd||dd.MM.yyyy||yyyy-MM-dd||dd-MM-yyyy||MM/dd/yyyy||yyyy/MM/dd";
        }

    }

    public String getDatePattern() {
        return datePattern;
    }

    public String getDateInputPattern() {
        return dateInputPattern;
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
