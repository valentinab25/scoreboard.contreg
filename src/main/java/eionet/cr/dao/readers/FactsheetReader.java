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
 * Agency. Portions created by Zero Technologies are Copyright
 * (C) European Environment Agency.  All Rights Reserved.
 *
 * Contributor(s):
 *        Jaanus Heinlaid
 */

package eionet.cr.dao.readers;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.openrdf.model.URI;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.BindingSet;

import eionet.cr.common.CRRuntimeException;
import eionet.cr.dto.FactsheetDTO;
import eionet.cr.dto.ObjectDTO;
import eionet.cr.util.sesame.SPARQLResultSetBaseReader;

/**
 *
 * @author Jaanus Heinlaid
 */
public class FactsheetReader extends SPARQLResultSetBaseReader<FactsheetDTO> {

    /** */
    private static final Logger LOGGER = Logger.getLogger(FactsheetReader.class);

    /** */
    public static final String OBJECT_DATA_SPLITTER = "<|>";

    /** */
    private FactsheetDTO factsheetDTO;

    /** */
    private String subjectUri;

    /**
     * @param subjectUri
     */
    public FactsheetReader(String subjectUri) {

        if (StringUtils.isBlank(subjectUri)) {
            throw new IllegalArgumentException("Subject URI must not be blank!");
        }
        this.subjectUri = subjectUri;
    }

    /**
     * @see eionet.cr.util.sesame.SPARQLResultSetReader#readRow(org.openrdf.query.BindingSet)
     */
    @Override
    public void readRow(BindingSet bindingSet) throws ResultSetReaderException {

        if (factsheetDTO == null) {
            factsheetDTO = new FactsheetDTO(subjectUri, Integer.parseInt(getStringValue(bindingSet, "anonSubj")) > 0);
        }

        String predicateUri = getStringValue(bindingSet, "pred");
        factsheetDTO.setObjectCount(predicateUri, Integer.parseInt(getStringValue(bindingSet, "objCount")));

        ObjectDTO objectDTO = parseObjectData(getStringValue(bindingSet, "objData"));
        if (objectDTO != null) {
            factsheetDTO.addObject(predicateUri, objectDTO);
        }
    }

    /**
     *
     * @param objectData
     * @return
     */
    private ObjectDTO parseObjectData(String objectData) {

        if (StringUtils.isBlank(objectData)) {
            return null;
        }

        String[] split = StringUtils.splitByWholeSeparatorPreserveAllTokens(objectData, OBJECT_DATA_SPLITTER);
        if (split == null || split.length > 7) {
            throw new CRRuntimeException("Was expecting a length >=7 for the split array");
        }

        String label = StringUtils.isBlank(split[0]) ? "" : split[0].trim();
        String language = StringUtils.isBlank(split[1]) ? null : split[1].trim();
        URI datatype = StringUtils.isBlank(split[2]) ? null : new URIImpl(split[2].trim());
        String objectUri = StringUtils.isBlank(split[3]) ? null : split[3].trim();

        boolean isLiteral = objectUri == null;
        boolean isAnonymous = StringUtils.isBlank(split[4]) ? false : split[4].trim().equals("1");
        String graphUri = StringUtils.isBlank(split[5]) ? null : split[5].trim();
        String objectMD5 = StringUtils.isBlank(split[6]) ? "" : split[6].trim();

        ObjectDTO objectDTO = null;
        if (isLiteral) {
            objectDTO = new ObjectDTO(label, language, true, false, datatype);
        } else {
            objectDTO = new ObjectDTO(objectUri, null, false, isAnonymous);
            if (!StringUtils.isBlank(label) && !label.equals(objectUri)) {
                ObjectDTO labelObjectDTO = new ObjectDTO(label, language, true, false, datatype);
                objectDTO.setLabelObject(labelObjectDTO);
            }
        }
        objectDTO.setSourceUri(graphUri);
        objectDTO.setObjectMD5(objectMD5);

        if (LOGGER.isTraceEnabled()){
            if (isLiteral && !objectDTO.isEqualMD5()){
                LOGGER.trace("Object's MD5 is different");
            }
        }

        return objectDTO;
    }

    /**
     * @return the factsheetDTO
     */
    public FactsheetDTO getFactsheetDTO() {
        return factsheetDTO;
    }

    public static void main(String[] args) {

        String str = "Aiova<|>lv<|><|><|>0<|>http://sws.geonames.org/4862182/about.rdf";
        String[] ss = StringUtils.splitByWholeSeparatorPreserveAllTokens(str, "<|>");
        for (int i = 0; i < ss.length; i++) {
            System.out.println(i + " = _" + ss[i] + "_");
        }

        // str = "<> <>  <>  <> http://lod.geospecies.org/ses/22ERa <> false <> http://rdf.geospecies.org/geospecies.rdf.gz";
        // ss = StringUtils.splitByWholeSeparator(str, "<>");
        // for (int i=0; i<ss.length; i++){
        // System.out.println(i + " = _" + ss[i].trim() + "_");
        // }
    }
}
