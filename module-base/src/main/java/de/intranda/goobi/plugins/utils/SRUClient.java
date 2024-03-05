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

package de.intranda.goobi.plugins.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;

import de.intranda.goobi.plugins.KalliopeOpacImport;
import de.intranda.utils.DocumentUtils;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;

public class SRUClient {

    private static final Logger logger = Logger.getLogger(KalliopeOpacImport.class);
    
    /**
     * Queries the given catalog via Z.3950 (SRU) and returns its response.
     * 
     * @param cat The catalog to query.
     * @param query The query.
     * @param recordSchema The expected record schema.
     * @return Query result XML string.
     * @throws SRUClientException
     */
    public static String querySRU(ConfigOpacCatalogue cat, String query, String recordSchema) throws SRUClientException {
        String ret = null;
        if (query != null && !query.isEmpty()) {
            query = query.trim();
        }

        if (cat != null) {
            String urlString;
            try {
                query = URLEncoder.encode(query, "utf-8");
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append(cat.getProtocol());
                urlBuilder.append(cat.getAddress());
                urlBuilder.append(":" + cat.getPort());
                urlBuilder.append("/" + cat.getDatabase());
                urlBuilder.append("?version=1.2");
                urlBuilder.append("&operation=searchRetrieve");
                urlBuilder.append("&query=" + query);
                urlBuilder.append("&maximumRecords=100");
                urlBuilder.append("&recordSchema=" + recordSchema);
                urlString = urlBuilder.toString();
            } catch (UnsupportedEncodingException e) {
                throw new SRUClientException(e);
            }
            logger.debug("SRU URL: " + urlString);
         
            ret = getStringFromUrl(urlString);
            
            return ret;
            
        } else {
            throw new SRUClientException("No catalog provided for sru query");
        }

    }

    public static String getStringFromUrl(String url) {
        String response = "";
        CloseableHttpClient client = null;
        HttpGet method = new HttpGet(url);
        client = HttpClientBuilder.create().build();
        try {
            response = client.execute(method, stringResponseHandler);
        } catch (IOException e) {
            logger.error("Cannot execute URL " + url, e);
        } finally {
            method.releaseConnection();

            if (client != null) {
                try {
                    client.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
        }
        return response;
    }

    public static ResponseHandler<String> stringResponseHandler = new ResponseHandler<String>() {
        @Override
        public String handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Wrong status code : " + response.getStatusLine().getStatusCode());
                return null;
            }
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toString(entity);
            } else {
                return null;
            }
        }
    };

    /**
     * Converts a <code>String</code> from one given encoding to the other.
     * 
     * @param string The string to convert.
     * @param from Source encoding.
     * @param to Destination encoding.
     * @return The converted string.
     */
    public static String convertStringEncoding(String string, String from, String to) {
        try {
            Charset charsetFrom = Charset.forName(from);
            Charset charsetTo = Charset.forName(to);
            CharsetEncoder encoder = charsetFrom.newEncoder();
            CharsetDecoder decoder = charsetTo.newDecoder();
            ByteBuffer bbuf = encoder.encode(CharBuffer.wrap(string));
            CharBuffer cbuf = decoder.decode(bbuf);
            return cbuf.toString();
        } catch (CharacterCodingException e) {
            logger.error(e.getMessage(), e);
        }

        return string;
    }

    public static Document retrieveMarcRecord(String input) throws JDOMException, IOException {
        Document wholeDoc = DocumentUtils.getDocumentFromString(input, null);
        try {
            Element record =
                    wholeDoc.getRootElement().getChild("records", null).getChild("record", null).getChild("recordData", null)
                            .getChild("record", null);
            Document outDoc = new Document((Element) record.detach());
            return outDoc;
        } catch (NullPointerException e) {
            return null;
        }
    }

    public static class SRUClientException extends Exception {

        private static final long serialVersionUID = -1016166144685656635L;

        public SRUClientException() {
            super();
        }

        public SRUClientException(String message) {
            super(message);
        }

        public SRUClientException(Throwable cause) {
            super(cause);
        }

    }
}
