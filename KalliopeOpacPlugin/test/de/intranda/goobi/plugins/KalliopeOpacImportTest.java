package de.intranda.goobi.plugins;

import static org.junit.Assert.fail;

import java.io.File;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import de.intranda.utils.DocumentUtils;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;

public class KalliopeOpacImportTest {

    private static final String prefsPath = "resources/ruleset_gbv_sim.xml";
    
    private ConfigOpacCatalogue catalogue;
    private Prefs prefs;
    
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        catalogue = new ConfigOpacCatalogue("Kalliope (SRU)", "SRU-Schnittstelle des Kalliope-Verbunds", "kalliope-verbund.info", "sru", null, 80, "utf-8", null, null, "Kalliope");
        prefs = new Prefs();
        prefs.loadPrefs(prefsPath);
    
    }
    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testRetrieveFileformat() throws ImportPluginException {
        KalliopeOpacImport importer = new KalliopeOpacImport();
        
        String inSuchfeld = "ead.title";
        String inSuchbegriff = "Auflistung über Neuernannte ausserordentliche Mitglieder von 1919";
//        String inSuchbegriff = "Katalog des Musikhistorischen Museums";
        try {
            Fileformat ff = importer.retrieveFileformat(inSuchfeld, inSuchbegriff, catalogue, prefs);
            System.out.println(ff.getDigitalDocument());
            File outputFile = new File("output", "meta.xml");
            ff.write(outputFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        
    }

}
