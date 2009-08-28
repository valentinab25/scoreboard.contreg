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
 * The Original Code is Content Registry 2.0.
 *
 * The Initial Owner of the Original Code is European Environment
 * Agency.  Portions created by Tieto Eesti are Copyright
 * (C) European Environment Agency.  All Rights Reserved.
 *
 * Contributor(s):
 * Jaanus Heinlaid, Tieto Eesti
 */
package eionet.cr.web.action;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.RedirectResolution;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.UrlBinding;

import org.apache.commons.lang.StringUtils;

import eionet.cr.common.Predicates;
import eionet.cr.config.GeneralConfig;
import eionet.cr.dao.DAOException;
import eionet.cr.dao.HelperDao;
import eionet.cr.dto.ObjectDTO;
import eionet.cr.dto.SubjectDTO;
import eionet.cr.harvest.HarvestException;
import eionet.cr.harvest.scheduled.UrgentHarvestQueue;
import eionet.cr.search.FactsheetSearch;
import eionet.cr.search.SearchException;
import eionet.cr.search.util.SubProperties;
import eionet.cr.search.util.UriLabelPair;

/**
 * 
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
@UrlBinding("/factsheet.action")
public class FactsheetActionBean extends AbstractActionBean{
	
	/** */
	private static final String ADDIBLE_PROPERTIES_SESSION_ATTR = FactsheetActionBean.class.getName() + ".addibleProperties";

	/** */
	private String uri;
	private Long uriHash;
	private SubjectDTO subject;
	
	/** */
	private Map<String,String> predicateLabels;
	private SubProperties subProperties;
	
	/** */
	private boolean anonymous;
	private String propertyUri;
	private String propertyValue;
	
	/** */
	private List<String> rowId;
	
	/**
	 * 
	 * @return
	 * @throws SearchException
	 */
	@DefaultHandler
	public Resolution view() throws SearchException{
		
		FactsheetSearch factsheetSearch = uriHash==null ? new FactsheetSearch(uri) : new FactsheetSearch(uriHash);
		factsheetSearch.execute();
		Collection<SubjectDTO> coll = factsheetSearch.getResultList();
		if (coll!=null && !coll.isEmpty())
			subject = coll.iterator().next();
		predicateLabels = factsheetSearch.getPredicateLabels().getByLanguagePreferences(createPreferredLanguages(), "en");
		subProperties = factsheetSearch.getSubProperties();
		
		return new ForwardResolution("/pages/factsheet.jsp");
	}
	
	/**
	 * schedules a harvest for resource.
	 * 
	 * @return view resolution
	 * @throws HarvestException
	 * @throws SearchException
	 */
	public Resolution harvest() throws HarvestException, SearchException {
		if(isUserLoggedIn()){
			if (!StringUtils.isBlank(uri)){
				UrgentHarvestQueue.addPullHarvest(StringUtils.substringBefore(uri, "#"));
				showMessage("The source has been scheduled for urgent harvest!");
			}
		}
		else {
			handleCrException(getBundle().getString("not.logged.in"), GeneralConfig.SEVERITY_WARNING);
		}
		return view();
	}

	/**
	 * 
	 * @return
	 * @throws SearchException
	 */
	public Resolution edit() throws SearchException{
		
		return view();
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution save() throws DAOException{
		
		SubjectDTO subjectDTO = new SubjectDTO(uri, anonymous);
		
		ObjectDTO objectDTO = new ObjectDTO(propertyValue, true);		
		objectDTO.setSourceUri(getUser().getRegistrationsUri());		
		subjectDTO.addObject(propertyUri, objectDTO);
		
		HelperDao spoHelperDao = factory.getDao(HelperDao.class);			 
		spoHelperDao.addTriples(subjectDTO);
		
		return new RedirectResolution(this.getClass(), "edit").addParameter("uri", uri);
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution delete() throws DAOException{
		
		if (rowId!=null && !rowId.isEmpty()){
			
			System.out.println("The following rows were selected for deletion by user:");
			for (String s:rowId){
				System.out.println(s);
			}
		}
		
		return new RedirectResolution(this.getClass(), "edit").addParameter("uri", uri);
	}
	
	/**
	 * @return the resourceUri
	 */
	public String getUri() {
		return uri;
	}

	/**
	 * @param resourceUri the resourceUri to set
	 */
	public void setUri(String resourceUri) {
		this.uri = resourceUri;
	}

	/**
	 * @return the resource
	 */
	public SubjectDTO getSubject() {
		return subject;
	}
	
	/**
	 * @return the predicateLabels
	 */
	public Map<String, String> getPredicateLabels() {
		return predicateLabels;
	}

	/**
	 * @return the subProperties
	 */
	public SubProperties getSubProperties() {
		return subProperties;
	}

	/**
	 * @return the uriHash
	 */
	public Long getUriHash() {
		return uriHash;
	}

	/**
	 * @param uriHash the uriHash to set
	 */
	public void setUriHash(Long uriHash) {
		this.uriHash = uriHash;
	}

	/**
	 * @return the addibleProperties
	 * @throws DAOException 
	 */
	public Collection<UriLabelPair> getAddibleProperties() throws DAOException {

		/* get the addible properties from session */
		
		HttpSession session = getContext().getRequest().getSession();
		ArrayList<UriLabelPair> result = (ArrayList<UriLabelPair>)session.getAttribute(ADDIBLE_PROPERTIES_SESSION_ATTR);
		
		// if not in session, create them and add to session
		if (result==null || result.isEmpty()){
		
			/* get addible properties from database */
			
			HelperDao helperDao = factory.getDao(HelperDao.class);
			HashMap<String,String> props = helperDao.getAddibleProperties(getSubjectTypesHashes());
			
			// add some hard-coded properties, HashMap assures there won't be duplicates
			props.put(Predicates.RDF_TYPE, "Type");
			props.put(Predicates.CR_TAG, "Tag");
			props.put(Predicates.CR_COMMENT, "Comment");
			props.put(Predicates.CR_HAS_SOURCE, "hasSource");
			props.put(Predicates.ROD_PRODUCT_OF, "productOf");
			
			/* create the result object from the found and hard-coded properties, sort it */
			
			result = new ArrayList<UriLabelPair>();
			if (props!=null && !props.isEmpty()){
				
				for (String uri:props.keySet()){
					result.add(UriLabelPair.create(uri, props.get(uri)));
				}
				Collections.sort(result);
			}
			
			// put into session
			session.setAttribute(ADDIBLE_PROPERTIES_SESSION_ATTR, result);
		}
		
		return result;
	}
	
	/**
	 * 
	 * @return
	 */
	private Collection<String> getSubjectTypesHashes(){
		
		HashSet<String> result = new HashSet<String>();
		Collection<ObjectDTO> typeObjects = subject.getObjects(Predicates.RDF_TYPE, ObjectDTO.Type.RESOURCE);
		if (typeObjects!=null && !typeObjects.isEmpty()){
			
			for (ObjectDTO object:typeObjects){
				
				result.add(String.valueOf(object.getHash()));
			}
		}
		
		return result;
	}

	/**
	 * @param anonymous the anonymous to set
	 */
	public void setAnonymous(boolean anonymous) {
		this.anonymous = anonymous;
	}

	/**
	 * @param subject the subject to set
	 */
	public void setSubject(SubjectDTO subject) {
		this.subject = subject;
	}

	/**
	 * @param predicateLabels the predicateLabels to set
	 */
	public void setPredicateLabels(Map<String, String> predicateLabels) {
		this.predicateLabels = predicateLabels;
	}

	/**
	 * @param subProperties the subProperties to set
	 */
	public void setSubProperties(SubProperties subProperties) {
		this.subProperties = subProperties;
	}

	/**
	 * @param propertyUri the propertyUri to set
	 */
	public void setPropertyUri(String propertyUri) {
		this.propertyUri = propertyUri;
	}

	/**
	 * @param propertyValue the propertyValue to set
	 */
	public void setPropertyValue(String propertyValue) {
		this.propertyValue = propertyValue;
	}

	/**
	 * @param rowId the rowId to set
	 */
	public void setRowId(List<String> rowId) {
		this.rowId = rowId;
	}
}
