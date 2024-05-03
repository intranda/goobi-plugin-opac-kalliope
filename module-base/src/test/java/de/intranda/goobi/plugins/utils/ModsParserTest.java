package de.intranda.goobi.plugins.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.intranda.goobi.plugins.KalliopeOpacImport;
import de.intranda.utils.DocumentUtils;

public class ModsParserTest {

    private static final String DATE_INPUT_PATTERN = "yyyyMMdd||dd.MM.yyyy||yyyy-MM-dd||dd-MM-yyyy||MM/dd/yyyy||yyyy/MM/dd";

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testGetFormattedDate() {
        try {
            String s = "18771201/19770203";
            String output = ModsParser.getFormattedDate(s, DATE_INPUT_PATTERN, "dd.MM.yyyy");
            System.out.println(output);
            assertEquals("01.12.1877-03.02.1977", output);
        } catch(Throwable e) {
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

    
    private List<Element> getMods(Document doc) {
        Iterator<Element> iterator = doc.getRootElement().getDescendants(Filters.element("mods", ModsParser.NS_MODS));
        List<Element> modsElements = new ArrayList<Element>();
        while (iterator.hasNext()) {
            modsElements.add(iterator.next());
        }
        return modsElements;
    }

}
