/**
 * This file is part of the SRU opac import plugin for the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information. 
 *          - http://digiverso.com 
 *          - http://www.intranda.com
 * 
 * Copyright 2013, intranda GmbH, Göttingen
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * 
 */

package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.Filters;

import de.intranda.goobi.plugins.utils.ModsParser;
import de.intranda.goobi.plugins.utils.ModsParser.ParserException;
import de.intranda.goobi.plugins.utils.SRUClient;
import de.intranda.goobi.plugins.utils.SRUClient.SRUClientException;
import de.intranda.utils.DocumentUtils;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
public class KalliopeOpacImport implements IOpacPlugin {
    private static final Logger logger = Logger.getLogger(KalliopeOpacImport.class);

    private static final String PLUGIN_NAME = "Kalliope-SRU";
    private static final String PLUGIN_VERSION = "v1.1";
    private static final String PLUGIN_BUILDDATE = "20141127";

    private HierarchicalConfiguration config;
    private String inputEncoding;
    private File mappingFile;
    private String defaultDocType;
    private int hitcount;
    private String gattung = "Ag";
    private String atstsl;
    private ConfigOpacCatalogue coc;
    private Prefs prefs;

    private Map<String, String> searchFieldMap;

    private String docStructType = "";

    public KalliopeOpacImport() throws ImportPluginException {
        this.config = ConfigPlugins.getPluginConfig(this);
        init();
    }

    public KalliopeOpacImport(HierarchicalConfiguration config) throws ImportPluginException {
        this.config = config;
        init();
    }

    private void init() throws ImportPluginException {
        this.inputEncoding = config.getString("charset", "utf-8");
        String mappingPath = config.getString("mapping", "mods_map_kalliope.xml");
        this.gattung = config.getString("defaultPicaType", "Ag");
        if (mappingPath.startsWith("/")) {
            mappingFile = new File(mappingPath);
        } else {
            mappingFile = new File(ConfigurationHelper.getInstance().getXsltFolder(), mappingPath);
        }
        if (!mappingFile.isFile()) {
            throw new ImportPluginException("Cannot locate mods mapping file " + mappingFile.getAbsolutePath());
        }
        defaultDocType = config.getString("defaultDocType", "ArchiveDocument");
        initSearchFieldMap();
    }

    private void initSearchFieldMap() {
        searchFieldMap = new HashMap<String, String>();
        searchFieldMap.put("12", "ead.id");
        searchFieldMap.put("4", "ead.title");
        searchFieldMap.put("5024", "ead.unitid");
        searchFieldMap.put("8218", "gi.index");
    }

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue catalogue, Prefs inPrefs) throws ImportPluginException {
        inSuchfeld = getMappedSearchField(inSuchfeld);
        Fileformat ff = retrieveFileformat(inSuchfeld, inSuchbegriff, catalogue, inPrefs);
        this.docStructType = getLogicalDocType(ff);
        return ff;
    }

    private String getLogicalDocType(Fileformat ff) throws ImportPluginException {
        try {
            DigitalDocument digitalDocument = ff.getDigitalDocument();
            DocStruct ds = digitalDocument.getLogicalDocStruct();
            if(ds.getType().isAnchor() && !ds.getAllChildren().isEmpty()) {
                ds = ds.getAllChildren().get(0);
            }
            return ds.getType().getName();
        } catch (PreferencesException e) {
            throw new ImportPluginException("no digitial document created");
        }
    }

    private String getMappedSearchField(String fieldCode) {
        String fieldName = searchFieldMap.get(fieldCode);
        if (fieldName != null) {
            return fieldName;
        } else {
            return fieldCode;
        }
    }

    protected Fileformat retrieveFileformat(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue catalogue, Prefs inPrefs)
            throws ImportPluginException {
        this.coc = catalogue;
        this.prefs = inPrefs;
        Fileformat ff = null;
        String recordSchema = "mods37";

        try {
            String answer = SRUClient.querySRU(catalogue, inSuchfeld + "=" + inSuchbegriff, recordSchema);
            List<Element> records = getModsRecords(answer, this.inputEncoding);

            hitcount = records.size();
            if (hitcount < 1) {
                throw new ImportPluginException("Unable to find record");
            } else if (hitcount > 1) {
                throw new ImportPluginException("Query ambigious: Found more than one record");
            }

            List<String> anchorMetadataList = new ArrayList<String>();

            Element recordElement = records.get(0);
            ModsParser parser = new ModsParser(inPrefs, mappingFile, config, anchorMetadataList);
            LinkedHashMap<String, Map<String, String>> docTypeMappings = createDocTypeMapping(config);
            String docType = parser.determineDocType(recordElement, docTypeMappings, config.getString("defaultDocType", "Monograph"));
            ff = createEmptyFileformat(inPrefs, docType);
            DocStruct topStruct = getMainStruct(ff);
            DocStruct anchor = getAnchorStruct(ff);
            DocStruct boundBook = ff.getDigitalDocument().getPhysicalDocStruct();
            parser.parseModsSection(topStruct, anchor, boundBook, recordElement);

            if (config.getBoolean("writeTempMetadataFile", false)) {
                String catalogIDDigital = topStruct.getAllMetadata()
                        .stream()
                        .filter(md -> "CatalogIDDigital".equals(Optional.ofNullable(md).map(Metadata::getType).map(MetadataType::getName).orElse("")))
                        .findAny()
                        .map(Metadata::getValue)
                        .orElse("");
                if (StringUtils.isNotBlank(catalogIDDigital)) {
                    Path importDataFilePath = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), catalogIDDigital);
                    try {
                        FileUtils.write(importDataFilePath.toFile(), answer, "utf-8");
                    } catch (IOException e) {
                        logger.error("Unable to write kalliope mods data to " + importDataFilePath + ". Reason: " + e.toString());
                    }
                }
            }

//            try {
//                String anchorId = parser.getAchorID(recordElement);
//                if (anchorId != null) {
//                    Fileformat aff = retrieveFileformat("", anchorId, catalogue, inPrefs);
//                    attachToAnchor(ff.getDigitalDocument(), aff);
//                }
//            } catch (Exception e) {
//                logger.warn("Unable to append record to anchor: " + e.toString());
//            }
            createAtstsl(ff.getDigitalDocument());
            return ff;
        } catch (SRUClientException | UGHException | ParserException e) {
            logger.error(e);
            throw new ImportPluginException("Failed to create fileformat: " + e.getMessage());
        }
    }

    public static LinkedHashMap<String, Map<String, String>> createDocTypeMapping(HierarchicalConfiguration conf) {
        LinkedHashMap<String, Map<String, String>> mappings = new LinkedHashMap<>();
        List<HierarchicalConfiguration> docTypeConfigs = conf.configurationsAt("docTypes.docType");
        for (HierarchicalConfiguration docTypeConf : docTypeConfigs) {
            String docTypeName = docTypeConf.getString("name", "");
            if(StringUtils.isNotBlank(docTypeName)) {
                Map<String, String> mappingConditions = new HashMap<>();
                List<HierarchicalConfiguration> conditions = docTypeConf.configurationsAt("condition");
                for (HierarchicalConfiguration condition : conditions) {
                    String xpath = condition.getString("xpath", "");
                    if(StringUtils.isNotBlank(xpath)) {
                        String value = condition.getString("value", "");
                        mappingConditions.put(xpath, value);
                    }
                }
                mappings.put(docTypeName, mappingConditions);
            }
        }
        return mappings;
    }

    /**
     * Returns the first logical DocStruct that is no anchor.
     * 
     * @param ff
     * @return
     * @throws PreferencesException if no suitable docStruct could be found
     */
    private DocStruct getMainStruct(Fileformat ff) throws PreferencesException {
        DocStruct ds = ff.getDigitalDocument().getLogicalDocStruct();
        if (ds.getType().isAnchor()) {
            try {
                return ds.getAllChildren().get(0);
            } catch (IndexOutOfBoundsException e) {
                throw new PreferencesException("Anchor element contains no children");
            }
        } else {
            return ds;
        }
    }

    /**
     * Returns the top logical DocStruct if it is an anchor, or null otherwise
     * 
     * @param ff
     * @return
     * @throws PreferencesException
     */
    private DocStruct getAnchorStruct(Fileformat ff) throws PreferencesException {
        DocStruct ds = ff.getDigitalDocument().getLogicalDocStruct();
        if (ds.getType().isAnchor()) {
            return ds;
        } else {
            return null;
        }
    }

    /**
     * Creates a MetsMods fileformat based on the information of the typeOfResourceElement with no metadata. Creates a monograph if information is
     * insufficient.
     * 
     * @param inPrefs
     * @param typeOfResourceElement
     * @return
     * @throws PreferencesException
     */
    private Fileformat createEmptyFileformat(Prefs inPrefs, String docTypeName) throws PreferencesException {

        //create base structures
        Fileformat ff = new MetsMods(inPrefs);
        DigitalDocument dd = new DigitalDocument();
        ff.setDigitalDocument(dd);
        String dsName = StringUtils.isBlank(docTypeName) ? defaultDocType : docTypeName;
        //        String anchorName = null;
        String boundBookName = "BoundBook";

        //evaluate element
        //        if (typeOfResourceElement != null) {
        //
        //            boolean isManuscript = "yes".equals(typeOfResourceElement.getAttributeValue("manuscript").toLowerCase());
        //
        //            if (isManuscript) {
        //                dsName = "Manuscript";
        //            }
        //
        //        }

        //create docTypes
        DocStructType physType = inPrefs.getDocStrctTypeByName(boundBookName);
        DocStructType dsType = inPrefs.getDocStrctTypeByName(dsName);
        //        DocStructType anchorType = null;
        //        if (anchorName != null) {
        //            anchorType = inPrefs.getDocStrctTypeByName(anchorName);
        //        }

        //create structure
        if (dsType != null) {
            try {
                DocStruct physStruct = ff.getDigitalDocument().createDocStruct(physType);
                ff.getDigitalDocument().setPhysicalDocStruct(physStruct);
                DocStruct ds = ff.getDigitalDocument().createDocStruct(dsType);
                //                if (anchorType != null) {
                //                    DocStruct dsAnchor = ff.getDigitalDocument().createDocStruct(anchorType);
                //                    dsAnchor.addChild(ds);
                //                    ff.getDigitalDocument().setLogicalDocStruct(dsAnchor);
                //                } else {
                ff.getDigitalDocument().setLogicalDocStruct(ds);
                //                }
            } catch (TypeNotAllowedForParentException e) {
                throw new PreferencesException(e.getMessage());
            }
        } else {
            throw new PreferencesException("Unable to create DocStructType " + dsName);
        }
        return ff;
    }

    private List<Element> getModsRecords(String queryResponse, String encoding) throws ImportPluginException {
        try {
            Document wholeDoc = DocumentUtils.getDocumentFromString(queryResponse, encoding);
            Element root = wholeDoc.getRootElement();
            //System.out.println(DocumentUtils.getStringFromDocument(wholeDoc, "utf-8"));
            Iterator<Element> iterator = root.getDescendants(Filters.element("mods", ModsParser.NS_MODS));
            List<Element> modsElements = new ArrayList<Element>();
            while (iterator.hasNext()) {
                modsElements.add(iterator.next());
            }
            return modsElements;
        } catch (JDOMException | IOException e) {
            throw new ImportPluginException(e);
        }
    }

    /**
     * Integrates the anchorFormats anchor element to the DigitalDocument
     * 
     * @param dd
     * @param anchorFormat
     * @throws PreferencesException
     * @throws TypeNotAllowedAsChildException
     */
    private void attachToAnchor(DigitalDocument dd, Fileformat anchorFormat) throws PreferencesException, TypeNotAllowedAsChildException {
        if (anchorFormat != null && anchorFormat.getDigitalDocument().getLogicalDocStruct().getType().isAnchor()) {
            logger.info("Retrieved anchor record ");
            DocStruct topStruct = dd.getLogicalDocStruct();
            if (topStruct.getType().isAnchor()) {
                topStruct = topStruct.getAllChildren().get(0);
            }
            DocStruct anchor = anchorFormat.getDigitalDocument().getLogicalDocStruct();
            anchor.addChild(topStruct);
            dd.setLogicalDocStruct(anchor);
        } else {
            logger.error("Failed to retrieve anchor record ");
        }
    }

    private void createAtstsl(DigitalDocument dd) {
        DocStruct logStruct = dd.getLogicalDocStruct();
        if (logStruct.getType().isAnchor() && logStruct.getAllChildren() != null && !logStruct.getAllChildren().isEmpty()) {
            logStruct = logStruct.getAllChildren().get(0);
        }

        String author = "";
        String title = "";

        List<? extends Metadata> authorList = logStruct.getAllMetadataByType(prefs.getMetadataTypeByName("Author"));
        if (authorList != null && !authorList.isEmpty()) {
            author = ((Person) authorList.get(0)).getLastname();
        }
        List<? extends Metadata> titleShortList = logStruct.getAllMetadataByType(prefs.getMetadataTypeByName("TitleDocMainShort"));
        if (titleShortList != null && !titleShortList.isEmpty()) {
            title = titleShortList.get(0).getValue();
        } else {
            List<? extends Metadata> titleList = logStruct.getAllMetadataByType(prefs.getMetadataTypeByName("TitleDocMain"));
            if (titleList != null && !titleList.isEmpty()) {
                title = titleList.get(0).getValue();
            }
        }
        this.atstsl = createAtstsl(title, author).toLowerCase();
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getHitcount()
     */
    @Override
    public int getHitcount() {
        return this.hitcount;
    }

    /* (non-Javadoc)
     * @see de.sub.goobi.Import.IOpac#getAtstsl()
     */
    @Override
    public String getAtstsl() {
        return this.atstsl;
    }

    @Override
    public PluginType getType() {
        return PluginType.Opac;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    public String getDescription() {
        return PLUGIN_NAME + " " + PLUGIN_VERSION;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        try {
            ConfigOpac co = ConfigOpac.getInstance();
            ConfigOpacDoctype cod = null;
            if(StringUtils.isNotBlank(this.docStructType)) {
                cod = co.getDoctypeByName(this.docStructType.toLowerCase());
            } else {
                cod = co.getDoctypeByMapping(this.gattung.substring(0, 2), this.coc.getTitle());               
            }
            if (cod == null) {

                cod = ConfigOpac.getInstance().getAllDoctypes().get(0);
                this.gattung = cod.getMappings().get(0);

            }
            return cod;
        } catch (Exception e) {
            logger.error("OpacDoctype unknown", e);
            return null;
        }
    }

    @Override
    public String createAtstsl(String myTitle, String autor) {
        String myAtsTsl = "";
        if (autor != null && !autor.equals("")) {
            /* autor */
            if (autor.length() > 4) {
                myAtsTsl = autor.substring(0, 4);
            } else {
                myAtsTsl = autor;
                /* titel */
            }

            if (myTitle.length() > 4) {
                myAtsTsl += myTitle.substring(0, 4);
            } else {
                myAtsTsl += myTitle;
            }
        }

        /*
         * -------------------------------- bei Zeitschriften Tsl berechnen --------------------------------
         */
        // if (gattung.startsWith("ab") || gattung.startsWith("ob")) {
        if (autor == null || autor.equals("")) {
            myAtsTsl = "";
            StringTokenizer tokenizer = new StringTokenizer(myTitle);
            int counter = 1;
            while (tokenizer.hasMoreTokens()) {
                String tok = tokenizer.nextToken();
                if (counter == 1) {
                    if (tok.length() > 4) {
                        myAtsTsl += tok.substring(0, 4);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                if (counter == 2 || counter == 3) {
                    if (tok.length() > 2) {
                        myAtsTsl += tok.substring(0, 2);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                if (counter == 4) {
                    if (tok.length() > 1) {
                        myAtsTsl += tok.substring(0, 1);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                counter++;
            }
        }
        /* im ATS-TSL die Umlaute ersetzen */

        myAtsTsl = myAtsTsl.replaceAll("[\\W]", "");
        return myAtsTsl;
    }

    @Override
    public void setAtstsl(String createAtstsl) {
        atstsl = createAtstsl;
    }

    public String getGattung() {
        return gattung;
    }

    public static String getFullPluginName() {
        return PLUGIN_NAME + " " + PLUGIN_VERSION + " " + PLUGIN_BUILDDATE;
    }

}
