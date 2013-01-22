/*
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Content Registry 3
 *
 * The Initial Owner of the Original Code is European Environment
 * Agency. Portions created by TripleDev or Zero Technologies are Copyright
 * (C) European Environment Agency.  All Rights Reserved.
 *
 * Contributor(s):
 *        jaanus
 */

package eionet.cr.staging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import eionet.cr.common.CRRuntimeException;
import eionet.cr.config.GeneralConfig;
import eionet.cr.util.URLUtil;

/**
 * A thread that downloads from a given URL a file that will be available for creating staging databases.
 *
 * @author jaanus
 */
public class FileDownloader extends Thread {

    /** */
    private static final Logger LOGGER = Logger.getLogger(FileDownloader.class);

    /** */
    public static final File FILES_DIR = new File(GeneralConfig.getRequiredProperty(GeneralConfig.STAGING_FILES_DIR));

    /** */
    public static final String FILE_SUFFIX = ".downloading";

    /** */
    private String url;

    /** */
    private String newFileName;

    /**
     * Constructs a new instance of this class, that will be used to download from the given URL.
     * The downloaded file will be renamed to the one given in the method's second input, unless it is null or blank.
     * The file will be saved into {@link #FILES_DIR}.
     *
     * @param url
     * @param newFileName
     */
    public FileDownloader(String url, String newFileName) {
        super();
        this.url = url;
        this.newFileName = newFileName;
    }

    /*
     * (non-Javadoc)
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        try {
            File file = execute();
            // Remove the SUFFIX from the file name, now that it's downloaded.
            file.renameTo(new File(FILES_DIR, StringUtils.substringBeforeLast(file.getName(), FILE_SUFFIX)));
        } catch (IOException e) {
            LOGGER.error("Failed to download from the given URL: " + url, e);
        }
    }

    /**
     * @return
     * @throws IOException
     *
     */
    private File execute() throws IOException {

        URLConnection connection = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection = new URL(url).openConnection();
            String fileName = ensureUniqueFileName(getFileName(connection)) + FILE_SUFFIX;
            File file = new File(FILES_DIR, fileName);
            outputStream = new FileOutputStream(file);
            inputStream = connection.getInputStream();
            IOUtils.copy(inputStream, outputStream);
            return file;
        } finally {
            IOUtils.closeQuietly(inputStream);
            IOUtils.closeQuietly(outputStream);
            URLUtil.disconnect(connection);
        }
    }

    /**
     *
     * @param connection
     * @return
     */
    private String getFileName(URLConnection connection) {

        // If file name already given, just return it.
        if (StringUtils.isNotBlank(newFileName)){
            return newFileName;
        }

        // Attempt detection from the response's "Content-Disposition" header.
        String contentDisposition = connection.getHeaderField("Content-Disposition");
        if (StringUtils.isNotBlank(contentDisposition)) {
            String s = StringUtils.substringAfter(contentDisposition, "filename");
            if (StringUtils.isNotBlank(s)) {
                s = StringUtils.substringAfter(s, "=");
                if (StringUtils.isNotBlank(s)) {
                    s = StringUtils.substringAfter(s, "\"");
                    if (StringUtils.isNotBlank(s)) {
                        s = StringUtils.substringBefore(s, "\"");
                        if (StringUtils.isNotBlank(s)) {
                            return s.trim();
                        }
                    }
                }
            }
        }

        // Attempt detection from the response's "Content-Location" header.
        String contentLocation = connection.getHeaderField("Content-Location");
        if (StringUtils.isNotBlank(contentLocation)) {
            String s = new File(contentLocation).getName();
            if (StringUtils.isNotBlank(s)) {
                return s.trim();
            }
        }

        // Attempt detection from the URL itself.

        String s = StringUtils.substringAfterLast(connection.getURL().toString(), "#");
        if (StringUtils.isBlank(s)) {
            s = StringUtils.substringAfterLast(connection.getURL().toString(), "/");
        }

        if (StringUtils.isNotBlank(s)) {
            // Remove all characters that are not one of these: a latin letter, a digit, a minus, a dot, an underscore.
            s = s.replaceAll("[^a-zA-Z0-9-._]+", "");
        }

        // If still no success, then just generate a hash from the URL.
        return StringUtils.isBlank(s) ? DigestUtils.md5Hex(connection.getURL().toString()) : s;
    }

    /**
     *
     * @param fileName
     * @return
     */
    public static String ensureUniqueFileName(String fileName) {

        if (!fileExists(fileName)) {
            return fileName;
        }

        for (int i = 1; i <= 100; i++) {
            String newFileName = fileName + "." + i;
            if (!fileExists(newFileName)) {
                return newFileName;
            }
        }

        throw new CRRuntimeException("Unable generate unique file name for [" + fileName + "] in " + FILES_DIR);
    }

    /**
     *
     * @param fileName
     * @return
     */
    private static boolean fileExists(String fileName){
        return new File(FILES_DIR, fileName).exists() || new File(FILES_DIR, fileName + FILE_SUFFIX).exists();
    }
}
