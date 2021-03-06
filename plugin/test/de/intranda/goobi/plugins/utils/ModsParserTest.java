package de.intranda.goobi.plugins.utils;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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

}
