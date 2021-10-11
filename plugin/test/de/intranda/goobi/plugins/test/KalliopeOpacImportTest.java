package de.intranda.goobi.plugins.test;

import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.XMLConfiguration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import de.intranda.goobi.plugins.KalliopeOpacImport;
import de.intranda.utils.DocumentUtils;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;

public class KalliopeOpacImportTest {

    private static final String prefsPath = "resources/ruleset_gbv_sim.xml";
    private static final String prefsPathRostock = "resources/Handschriften_Produktion_20150609.xml";
    private static final String configPath = "resources/plugin_KalliopeOpacImport.xml";
    private static final String configPathRostock = "resources/plugin_KalliopeOpacImport_Rostock.xml";
    
    private ConfigOpacCatalogue catalogue;
    private Prefs prefs;
    private Prefs prefsRostock;
    private Configuration config;
    private Configuration configRostock;

    
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
    	Map<String, String> searchList = new HashMap<>();
    	searchList.put("Identifier", "12");
        catalogue =  new ConfigOpacCatalogue("Kalliope (SRU)", "SRU-Schnittstelle des Kalliope-Verbunds", "kalliope-verbund.info", "sru", null, 80, "utf-8", null, null, "Kalliope", "http://", searchList);
        prefs = new Prefs();
        prefs.loadPrefs(prefsPath);
        prefsRostock = new Prefs();
        prefsRostock.loadPrefs(prefsPathRostock);
        config = new XMLConfiguration(new File(configPath));
        configRostock = new XMLConfiguration(new File(configPathRostock));

    
    }
    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testRetrieveFileformat() throws ImportPluginException {
        KalliopeOpacImport importer = new KalliopeOpacImport(config);
        
        String inSuchfeld = "ead.title";
        String inSuchbegriff = "Verzeichnis alterthümlicher Musikinstrumente Paul de Wit Leipzig";
        
//        String inSuchfeld = "ead.title";
//        String inSuchbegriff = "Auflistung über Neuernannte ausserordentliche Mitglieder von 1919";
//        String inSuchbegriff = "Katalog des Musikhistorischen Museums";
        try {
            Fileformat ff = importer.search(inSuchfeld, inSuchbegriff, catalogue, prefs);
            System.out.println(ff.getDigitalDocument());
            File outputFile = new File("output", "meta.xml");
            ff.write(outputFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        
    }


    @Test
    public void testRetrieveFileformatRostock() throws ImportPluginException {
        KalliopeOpacImport importer = new KalliopeOpacImport(configRostock);
        
        String inSuchfeld = "ead.id";
        String inSuchbegriff = "DE-611-HS-3468675";

        try {
            Fileformat ff = importer.search(inSuchfeld, inSuchbegriff, catalogue, prefsRostock);
            System.out.println(ff.getDigitalDocument());
            File outputFile = new File("output", "meta.xml");
            ff.write(outputFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        
    }
}
