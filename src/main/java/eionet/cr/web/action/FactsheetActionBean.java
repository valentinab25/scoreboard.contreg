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
import net.sourceforge.stripes.action.StreamingResolution;
import net.sourceforge.stripes.action.UrlBinding;
import net.sourceforge.stripes.validation.SimpleError;
import net.sourceforge.stripes.validation.ValidationMethod;

import org.apache.commons.lang.StringUtils;

import eionet.cr.common.Predicates;
import eionet.cr.config.GeneralConfig;
import eionet.cr.dao.DAOException;
import eionet.cr.dao.DAOFactory;
import eionet.cr.dao.HarvestSourceDAO;
import eionet.cr.dao.HelperDAO;
import eionet.cr.dao.util.SubProperties;
import eionet.cr.dao.util.UriLabelPair;
import eionet.cr.dto.HarvestSourceDTO;
import eionet.cr.dto.ObjectDTO;
import eionet.cr.dto.SubjectDTO;
import eionet.cr.harvest.HarvestException;
import eionet.cr.harvest.InstantHarvester;
import eionet.cr.harvest.statistics.dto.HarvestedUrlCountDTO;
import eionet.cr.util.Hashes;
import eionet.cr.util.Pair;
import eionet.cr.util.URLUtil;
import eionet.cr.web.util.FactsheetObjectId;

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
	private long uriHash;
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
	
	/** */
	private boolean noCriteria;
	
	private boolean urlFoundInHarvestSource = false;
	private boolean adminLoggedIn = false;
	
	private boolean urlUserBookmark = false;
	
	/**
	 * 
	 * @return
	 * @throws DAOException TODO
	 */
	@DefaultHandler
	public Resolution view() throws DAOException{
		
		if (StringUtils.isBlank(uri) && uriHash==0){
			noCriteria = true;
			addCautionMessage("Resource identifier not specified!");
		}
		else{
			Long subjectHash = uriHash==0 ? Hashes.spoHash(uri) : uriHash;
			HelperDAO helperDAO = DAOFactory.get().getDao(HelperDAO.class);
			
			if (getUser()!=null){
				if (getUser().isAdministrator()){
					setAdminLoggedIn(true);
				} else {
					setAdminLoggedIn(false);
				}
			} else {
				setAdminLoggedIn(false);
			}

			subject = helperDAO.getSubject(subjectHash);
			
			if (subject!=null) {
				uri = subject.getUri();
				uriHash = subject.getUriHash();
				
				predicateLabels = helperDAO.getPredicateLabels(
						Collections.singleton(subjectHash)).getByLanguages(getAcceptedLanguages());
				subProperties = helperDAO.getSubProperties(Collections.singleton(subjectHash));
				
				if (isAdminLoggedIn()){
					urlFoundInHarvestSource = helperDAO.isUrlInHarvestSource(subject.getUrl());
				}

			}
			
			
		}
		
		return new ForwardResolution("/pages/factsheet.jsp");
	}
	
	/**
	 * Handle for ajax harvesting.
	 * 
	 * @return
	 */
	public Resolution harvestAjax() {
		String message;
		try {
			message = harvestNow().getRight();
		} catch (Exception ignored) {
			logger.error("error while scheduling ajax harvest", ignored);
			message = "Error occured, more info can be obtained in application logs";
		}
		return new StreamingResolution("text/html", message);
	}

	/**
	 * schedules a harvest for resource.
	 * 
	 * @return view resolution
	 * @throws HarvestException
	 * @throws DAOException 
	 */
	public Resolution harvest() throws HarvestException, DAOException {
		Pair<Boolean, String> message = harvestNow();
		if (message.getLeft()) {
			addWarningMessage(message.getRight());
		} else {
			addSystemMessage(message.getRight());
		}
		
		return new RedirectResolution(this.getClass(), "view").addParameter("uri", uri);
	}
	
	/**
	 * helper method to eliminate code duplication.
	 * 
	 * @return
	 * @throws HarvestException
	 * @throws DAOException
	 */
	private Pair<Boolean, String> harvestNow() throws HarvestException, DAOException  {
		
		String message = null;
		if(isUserLoggedIn()){
			if (!StringUtils.isBlank(uri) && URLUtil.isURL(uri)){

				/* add this url into HARVEST_SOURCE table */
				
				HarvestSourceDAO dao = factory.getDao(HarvestSourceDAO.class);
				HarvestSourceDTO dto = new HarvestSourceDTO();
				dto.setUrl(StringUtils.substringBefore(uri, "#"));
				dto.setEmails("");
				dto.setIntervalMinutes(Integer.valueOf(GeneralConfig.getProperty(
						GeneralConfig.HARVESTER_REFERRALS_INTERVAL,
								String.valueOf(HarvestSourceDTO.DEFAULT_REFERRALS_INTERVAL))));
				dto.setTrackedFile(true);
				dao.addSourceIgnoreDuplicate(dto, getUserName());
				
				/* issue an instant harvest of this url */
				
				InstantHarvester.Resolution resolution = InstantHarvester.harvest(dto.getUrl(), getUserName());
				
				/* give feedback to the user */
				
				if (resolution.equals(InstantHarvester.Resolution.ALREADY_HARVESTING))
					message = "The source is currently being harvested by another user or background harvester!";
				else if (resolution.equals(InstantHarvester.Resolution.UNCOMPLETE))
					message = "The harvest hasn't finished yet, but continues in the background!";
				else if (resolution.equals(InstantHarvester.Resolution.COMPLETE))
					message = "The harvest has been completed!";
				else if (resolution.equals(InstantHarvester.Resolution.SOURCE_UNAVAILABLE))
					message = "The resource was not available!";
				else if (resolution.equals(InstantHarvester.Resolution.NO_STRUCTURED_DATA))
					message = "The resource contained no structured data!";
				else 
					message = "No feedback given from harvest!";
			}
			return new Pair<Boolean,String> (false, message);
		}
		else{
			return new Pair<Boolean, String> (true, getBundle().getString("not.logged.in"));
		}
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution edit() throws DAOException{
		
		return view();
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution addbookmark() throws DAOException{
		if (isUserLoggedIn()){
			DAOFactory.get().getDao(HelperDAO.class).addUserBookmark(getUser(), getUrl());
			addSystemMessage("Succesfully bookmarked this source.");
		} else {
			addSystemMessage("Only logged in users can bookmark sources.");
		}
		return view();
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution removebookmark() throws DAOException{
		if (isUserLoggedIn()){
			DAOFactory.get().getDao(HelperDAO.class).deleteUserBookmark(getUser(), getUrl());
			addSystemMessage("Succesfully removed this source from bookmarks.");
		} else {
			addSystemMessage("Only logged in users can remove bookmarks.");
		}
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
		
		HelperDAO helperDao = factory.getDao(HelperDAO.class);			 
		helperDao.addTriples(subjectDTO);
		helperDao.addResource(propertyUri, getUser().getRegistrationsUri());
		helperDao.addResource(getUser().getRegistrationsUri(), getUser().getRegistrationsUri());
		helperDao.updateUserHistory(getUser(), uri);
		
		return new RedirectResolution(this.getClass(), "edit").addParameter("uri", uri);
	}
	
	/**
	 * 
	 * @return
	 * @throws DAOException
	 */
	public Resolution delete() throws DAOException{
		
		if (rowId!=null && !rowId.isEmpty()){
			
			SubjectDTO subjectDTO = new SubjectDTO(uri, anonymous);

			boolean execute = false;
			for (String s:rowId){
				
				int i = s.indexOf("_");
				if (i<=0 || i==(s.length()-1)){
					throw new IllegalArgumentException("Illegal rowId: " + s);
				}
				
				ObjectDTO object = FactsheetObjectId.parse(s.substring(i+1));
				subjectDTO.addObject(s.substring(0,i), object);
				if (execute==false){
					execute = true;
				}
			}
			
			if (execute==true){
				
				HelperDAO helperDao = factory.getDao(HelperDAO.class);			 
				helperDao.deleteTriples(subjectDTO);
				helperDao.updateUserHistory(getUser(), uri);
			}
		}
		
		return new RedirectResolution(this.getClass(), "edit").addParameter("uri", uri);
	}
	
	@ValidationMethod(on={"save","delete","edit","harvest"})
	public void validateUserKnown(){
		
		if (getUser()==null){
			addWarningMessage("Operation not allowed for anonymous users");
		}
		else if (getContext().getEventName().equals("save") && StringUtils.isBlank(propertyValue)){
			addGlobalValidationError(new SimpleError("Property value must not be blank"));
		}
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
			
			HelperDAO helperDAO = factory.getDao(HelperDAO.class);
			HashMap<String,String> props = helperDAO.getAddibleProperties(getSubjectTypesHashes());
			
			// add some hard-coded properties, HashMap assures there won't be duplicates
			props.put(Predicates.RDF_TYPE, "Type");
			props.put(Predicates.RDFS_LABEL, "Label");
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

	/**
	 * @return the noCriteria
	 */
	public boolean isNoCriteria() {
		return noCriteria;
	}

	/**
	 * @return the uriHash
	 */
	public long getUriHash() {
		return uriHash;
	}

	/**
	 * @param uriHash the uriHash to set
	 */
	public void setUriHash(long uriHash) {
		this.uriHash = uriHash;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getUrl(){
		return uri!=null && URLUtil.isURL(uri) ? uri : null;
	}

	public boolean isUrlFoundInHarvestSource() {
		return urlFoundInHarvestSource;
	}

	public void setUrlFoundInHarvestSource(boolean urlFoundInHarvestSource) {
		this.urlFoundInHarvestSource = urlFoundInHarvestSource;
	}

	public boolean isAdminLoggedIn() {
		return adminLoggedIn;
	}

	public void setAdminLoggedIn(boolean adminLoggedIn) {
		this.adminLoggedIn = adminLoggedIn;
	}

	public boolean isUrlUserBookmark() {
		if (isUserLoggedIn()){
			try {
				urlUserBookmark = factory.getDao(HelperDAO.class).isUrlUserBookmark(getUser(), getUrl());
			} catch (DAOException ex){
				urlUserBookmark = false;
			}
		} else {
			urlUserBookmark = false;
		}
		
		return urlUserBookmark;
	}

	public void setUrlUserBookmark(boolean urlUserBookmark) {
		this.urlUserBookmark = urlUserBookmark;
	}
	
}
