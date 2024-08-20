package de.intranda.goobi.plugins.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.Filters;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import de.intranda.goobi.plugins.KalliopeOpacImport;
import de.intranda.goobi.plugins.utils.ModsParser.ParserException;
import de.intranda.utils.DocumentUtils;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

public class ModsParserTest {

    private static final String DATE_INPUT_PATTERN = "yyyyMMdd||dd.MM.yyyy||yyyy-MM-dd||dd-MM-yyyy||MM/dd/yyyy||yyyy/MM/dd";

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
    }

    @Test
    public void testGetFormattedDate() {
        try {
            String s = "18771201/19770203";
            String output = ModsParser.getFormattedDate(s, DATE_INPUT_PATTERN, "dd.MM.yyyy");
            System.out.println(output);
            assertEquals("01.12.1877-03.02.1977", output);
        } catch (Throwable e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testDetermineDocType() throws JDOMException, IOException, ConfigurationException {
        Path testConfig = Paths.get("src/test/resources/plugin_KalliopeOpacImport.xml");
        Path sampleManuscript = Paths.get("src/test/resources/samples/kassel/DE-611-HS-3631187.xml");
        Path sampleLetter = Paths.get("src/test/resources/samples/kassel/DE-611-HS-3660553.xml");

        Document docLetter = DocumentUtils.getDocumentFromFile(sampleLetter.toFile());
        Element eleLetter = getMods(docLetter).get(0);

        Document docManuscript = DocumentUtils.getDocumentFromFile(sampleManuscript.toFile());
        Element eleManuscript = getMods(docManuscript).get(0);

        XMLConfiguration config = new XMLConfiguration(testConfig.toFile());
        LinkedHashMap<String, Map<String, String>> mappings = KalliopeOpacImport.createDocTypeMapping(config);

        assertEquals("Manuscript", ModsParser.determineDocType(eleManuscript, mappings, "wrong"));
        assertEquals("SingleLetter", ModsParser.determineDocType(eleLetter, mappings, "wrong"));
    }

    @Test
    public void test_writeAuthorityData()
            throws ConfigurationException, PreferencesException, ParserException, JDOMException, IOException, WriteException {

        Path ruleset = Path.of("src/test/resources/samples/baggesen/baggesen.xml").toAbsolutePath();
        Path modsMappings = Path.of("src/test/resources/samples/baggesen/mods_map_kalliope.xml").toAbsolutePath();
        Path record = Path.of("src/test/resources/samples/baggesen/DE-611-HS-4209014.xml").toAbsolutePath();
        Path config = Path.of("src/test/resources/samples/baggesen/plugin_KalliopeOpacImport.xml").toAbsolutePath();

        Prefs prefs = new Prefs();
        prefs.loadPrefs(ruleset.toString());
        XMLConfiguration pluginConfig = new XMLConfiguration(config.toFile());
        Document recordDoc = DocumentUtils.getDocumentFromFile(record.toFile());
        Element recordElement =
                recordDoc.getRootElement().getChild("records", null).getChild("record", null).getChild("recordData", null).getChild("mods", null);
        assertNotNull(recordElement);
        ModsParser parser = new ModsParser(prefs, modsMappings.toFile(), pluginConfig, Collections.emptyList());

        Fileformat ff = createEmptyFileformat(prefs, "Manuscript");
        assertNotNull(ff.getDigitalDocument());

        parser.parseModsSection(ff.getDigitalDocument().getLogicalDocStruct(), null, ff.getDigitalDocument().getPhysicalDocStruct(), recordElement);
        System.out.println(ff.getDigitalDocument());

        Metadata pubPlace =
                ff.getDigitalDocument().getLogicalDocStruct().getAllMetadataByType(prefs.getMetadataTypeByName("PlaceOfPublication")).get(0);
        assertEquals("Leipzig", pubPlace.getValue());
        assertEquals("4035206-7", pubPlace.getAuthorityValue());
        assertEquals("https://d-nb.info/gnd/", pubPlace.getAuthorityURI());
        assertEquals("gnd", pubPlace.getAuthorityID());

        ff.write("src/test/resources/samples/baggesen/meta.xml");

    }

    private List<Element> getMods(Document doc) {
        Iterator<Element> iterator = doc.getRootElement().getDescendants(Filters.element("mods", ModsParser.NS_MODS));
        List<Element> modsElements = new ArrayList<Element>();
        while (iterator.hasNext()) {
            modsElements.add(iterator.next());
        }
        return modsElements;
    }

    private Fileformat createEmptyFileformat(Prefs inPrefs, String docTypeName) throws PreferencesException {

        //create base structures
        Fileformat ff = new MetsMods(inPrefs);
        DigitalDocument dd = new DigitalDocument();
        ff.setDigitalDocument(dd);
        String boundBookName = "BoundBook";

        DocStructType physType = inPrefs.getDocStrctTypeByName(boundBookName);
        DocStructType dsType = inPrefs.getDocStrctTypeByName(docTypeName);

        if (dsType != null) {
            try {
                DocStruct physStruct = ff.getDigitalDocument().createDocStruct(physType);
                ff.getDigitalDocument().setPhysicalDocStruct(physStruct);
                DocStruct ds = ff.getDigitalDocument().createDocStruct(dsType);
                ff.getDigitalDocument().setLogicalDocStruct(ds);
            } catch (TypeNotAllowedForParentException e) {
                throw new PreferencesException(e.getMessage());
            }
        } else {
            throw new PreferencesException("Unable to create DocStructType " + docTypeName);
        }
        return ff;
    }

}
