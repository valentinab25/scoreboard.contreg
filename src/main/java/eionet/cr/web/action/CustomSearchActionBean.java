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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.UrlBinding;
import eionet.cr.common.Predicates;
import eionet.cr.dao.HelperDao;
import eionet.cr.dto.SubjectDTO;
import eionet.cr.search.CustomSearch;
import eionet.cr.search.SearchException;
import eionet.cr.util.Util;
import eionet.cr.util.pagination.Pagination;
import eionet.cr.web.util.CustomSearchFilter;
import eionet.cr.web.util.columns.SearchResultColumn;
import eionet.cr.web.util.columns.SubjectLastModifiedColumn;
import eionet.cr.web.util.columns.SubjectPredicateColumn;

/**
 * 
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
@UrlBinding("/customSearch.action")
public class CustomSearchActionBean extends AbstractSearchActionBean<SubjectDTO>{
	
	/** */
	private static final String SELECTED_FILTERS_SESSION_ATTR_NAME = CustomSearchActionBean.class.getName() + ".selectedFilters";
	private static final String RESULT_LIST_SESSION_ATTR_NAME = CustomSearchActionBean.class.getName() + ".resultList";
	private static final String MATCH_COUNT_SESSION_ATTR_NAME = CustomSearchActionBean.class.getName() + ".matchCount";
	private static final String PAGINATION_SESSION_ATTR_NAME = CustomSearchActionBean.class.getName() + ".pagination";
	private static final String LITERAL_SEARCH_ENABLED_FILTERS = CustomSearchActionBean.class.getName() + ".literalSearchEnabledFilters";
	
	/** */
	private static final String SELECTED_VALUE_PREFIX = "value_";
	private static final String SHOW_PICKLIST_VALUE_PREFIX = "showPicklist_";
	private static final String REMOVE_FILTER_VALUE_PREFIX = "removeFilter_";
	
	/** */
	private static final String ASSOCIATED_JSP = "/pages/customSearch.jsp";
	
	/** */
	private static Map<String,CustomSearchFilter> availableFilters;
	private String addedFilter;
	private String picklistFilter;
	private String removedFilter;
	private Collection<String> picklist;
	
	/**
	 * 
	 * @return
	 */
	@DefaultHandler
	public Resolution unspecifiedEvent(){
		if (isShowPicklist())
			populateSelectedFilters();
		else if (isRemoveFilter()){
			populateSelectedFilters();
			
			Map selectedFilters = getSelectedFilters(false);
			if (selectedFilters!=null){
				selectedFilters.remove(getRemovedFilter());
			}
		}
		else
			clearSessionAttributes();
		
		return new ForwardResolution(ASSOCIATED_JSP);
	}

	/**
	 * 
	 */
	private void clearSessionAttributes(){
		HttpSession session = getContext().getRequest().getSession();
		session.removeAttribute(RESULT_LIST_SESSION_ATTR_NAME);
		session.removeAttribute(MATCH_COUNT_SESSION_ATTR_NAME);
		session.removeAttribute(PAGINATION_SESSION_ATTR_NAME);
		session.removeAttribute(SELECTED_FILTERS_SESSION_ATTR_NAME);
		session.removeAttribute(LITERAL_SEARCH_ENABLED_FILTERS);
	}
	
	/**
	 * 
	 * @return
	 * @throws SearchException 
	 */
	public Resolution search() throws SearchException{
		
		populateSelectedFilters();
		
		CustomSearch customSearch = new CustomSearch(buildSearchCriteria());
		customSearch.setPageNumber(getPageN());
		customSearch.setSorting(getSortP(), getSortO());
		customSearch.setLiteralsEnabledPredicates(getLiteralEnabledFilters());
		
		customSearch.execute();
		
		// we put the search result list into session and override getResultList() to retrieve the list from session
		// (look for the override in this class)
		HttpSession session = getContext().getRequest().getSession();
		session.setAttribute(RESULT_LIST_SESSION_ATTR_NAME, customSearch.getResultList());
		
		// we do the same for matchCount and pagination as well
		session.setAttribute(MATCH_COUNT_SESSION_ATTR_NAME, new Integer(customSearch.getTotalMatchCount()));
		session.setAttribute(PAGINATION_SESSION_ATTR_NAME, super.getPagination());
		
		return new ForwardResolution(ASSOCIATED_JSP);
	}

	/*
	 * (non-Javadoc)
	 * @see eionet.cr.web.action.AbstractSearchActionBean#getResultList()
	 */
	public Collection<SubjectDTO> getResultList(){
		return (Collection<SubjectDTO>)getContext().getRequest().getSession().getAttribute(RESULT_LIST_SESSION_ATTR_NAME);
	}

	/*
	 * (non-Javadoc)
	 * @see eionet.cr.web.action.AbstractSearchActionBean#getPagination()
	 */
	public Pagination getPagination(){
		return (Pagination)getContext().getRequest().getSession().getAttribute(PAGINATION_SESSION_ATTR_NAME);
	}

	/*
	 * (non-Javadoc)
	 * @see eionet.cr.web.action.AbstractSearchActionBean#getMatchCount()
	 */
	public int getMatchCount(){
		Integer i = (Integer)getContext().getRequest().getSession().getAttribute(MATCH_COUNT_SESSION_ATTR_NAME);
		return i==null ? super.getMatchCount() : i.intValue();
	}
	
	
	/**
	 * 
	 * @return
	 * @throws SearchException 
	 */
	public Resolution addFilter() throws SearchException{
		
		populateSelectedFilters();
		
		if (addedFilter!=null){
			
			getSelectedFilters().put(addedFilter, "");
			
			String predicateUri = getAvailableFilters().get(addedFilter).getUri();
			
			boolean literalsEnabled = factory.getDao(HelperDao.class).isAllowLiteralSearch(predicateUri);
			if (literalsEnabled)
				getLiteralEnabledFilters().add(predicateUri);
			else
				getLiteralEnabledFilters().remove(predicateUri);
		}
		
		return new ForwardResolution(ASSOCIATED_JSP);
	}
	
	/**
	 * 
	 * @return
	 * @throws SearchException 
	 */
	public Collection<String> getPicklist() throws SearchException{

		if (!isShowPicklist())
			return null;
		else if (!getAvailableFilters().containsKey(getPicklistFilter()))
			return null;
		
		if (picklist == null){
			picklist = factory.getDao(HelperDao.class).getPicklistForPredicate(
					getAvailableFilters().get(getPicklistFilter()).getUri());
		}
		
		return picklist;
	}
	
	/**
	 * @return the selectedFilter
	 */
	public String getAddedFilter() {
		return addedFilter;
	}

	/**
	 * @param selectedFilter the selectedFilter to set
	 */
	public void setAddedFilter(String selectedFilter) {
		this.addedFilter = selectedFilter;
	}

	/**
	 * 
	 * @return
	 */
	public Map<String,String> getSelectedFilters(){
		
		return getSelectedFilters(true);
	}
	
	/**
	 * 
	 * @param create
	 * @return
	 */
	private Map<String,String> getSelectedFilters(boolean create){
		
		HttpSession session = getContext().getRequest().getSession();
		Map<String,String> selectedFilters =
			(Map<String,String>)session.getAttribute(SELECTED_FILTERS_SESSION_ATTR_NAME);
		if (selectedFilters==null && create==true){
			selectedFilters = new LinkedHashMap<String,String>();
			session.setAttribute(SELECTED_FILTERS_SESSION_ATTR_NAME, selectedFilters);
		}
		
		return selectedFilters;
	}
	
	/**
	 * 
	 * @return
	 */
	private HashSet<String> getLiteralEnabledFilters(){
		
		HttpSession session = getContext().getRequest().getSession();
		HashSet<String> set = (HashSet<String>)session.getAttribute(LITERAL_SEARCH_ENABLED_FILTERS);
		if (set==null){
			set = new HashSet<String>();
			session.setAttribute(LITERAL_SEARCH_ENABLED_FILTERS, set);
		}
		
		return set;
	}

	/**
	 * 
	 */
	private void populateSelectedFilters(){
		
		Map<String,String> selected = getSelectedFilters();
		if (!selected.isEmpty()){
			Enumeration paramNames = this.getContext().getRequest().getParameterNames();
			while (paramNames!=null && paramNames.hasMoreElements()){
				String paramName = (String)paramNames.nextElement();
				if (paramName.startsWith(SELECTED_VALUE_PREFIX)){
					String key = paramName.substring(SELECTED_VALUE_PREFIX.length());
					if (key.length()>0 && selected.containsKey(key))
						selected.put(key, getContext().getRequest().getParameter(paramName));
				}
			}
		}		
	}

	/**
	 * @return the availableFilters
	 */
	public Map<String,CustomSearchFilter> getAvailableFilters() {
		
		if (availableFilters==null){
			
			ArrayList<CustomSearchFilter> list = new ArrayList<CustomSearchFilter>();
			
			CustomSearchFilter filter = new CustomSearchFilter();
			filter.setUri(Predicates.RDF_TYPE);
			filter.setTitle("Type");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.RDFS_LABEL);
			filter.setTitle("Title");
			filter.setDescription("");
			filter.setProvideValues(false);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_SUBJECT);
			filter.setTitle("Subject");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_COVERAGE);
			filter.setTitle("Coverage");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.ROD_OBLIGATION_PROPERTY);
			filter.setTitle("Dataflow");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.ROD_LOCALITY_PROPERTY);
			filter.setTitle("Locality");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.ROD_ISSUE_PROPERTY);
			filter.setTitle("Issue");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.ROD_INSTRUMENT_PROPERTY);
			filter.setTitle("Instrument");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.CR_SCHEMA);
			filter.setTitle("XML Schema");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_CREATOR);
			filter.setTitle("Creator");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_DESCRIPTION);
			filter.setTitle("Description");
			filter.setDescription("Abstract description of content");
			filter.setProvideValues(false);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_PUBLISHER);
			filter.setTitle("Publisher");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_CONTRIBUTOR);
			filter.setTitle("Contributor");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_RELATION);
			filter.setTitle("Relation");
			filter.setDescription("Url to a related resource");
			filter.setProvideValues(false);
			list.add(filter);

			filter = new CustomSearchFilter();
			filter.setUri(Predicates.DC_LANGUAGE);
			filter.setTitle("Language");
			filter.setDescription("");
			filter.setProvideValues(true);
			list.add(filter);

			availableFilters = new LinkedHashMap<String,CustomSearchFilter>();
			for (int i=0; i<list.size(); i++)
				availableFilters.put(String.valueOf(i+1), list.get(i));
		}
		
		return availableFilters;
	}

	/**
	 * @return the picklistFilter
	 */
	public String getPicklistFilter() {
		
		if (picklistFilter==null){
			picklistFilter = "";
			Enumeration paramNames = this.getContext().getRequest().getParameterNames();
			while (paramNames!=null && paramNames.hasMoreElements()){
				String paramName = (String)paramNames.nextElement();
				if (paramName.startsWith(SHOW_PICKLIST_VALUE_PREFIX)){
					int i = paramName.indexOf('.')<0 ? paramName.length() : paramName.indexOf('.');
					String key = paramName.substring(SHOW_PICKLIST_VALUE_PREFIX.length(), i);
					if (key.length()>0 && getSelectedFilters().containsKey(key)){
						picklistFilter = key;
						break;
					}
				}
			}

		}
		return picklistFilter;
	}

	/**
	 * @return the removedFilter
	 */
	public String getRemovedFilter() {
		
		if (removedFilter==null){
			removedFilter = "";
			Enumeration paramNames = this.getContext().getRequest().getParameterNames();
			while (paramNames!=null && paramNames.hasMoreElements()){
				String paramName = (String)paramNames.nextElement();
				if (paramName.startsWith(REMOVE_FILTER_VALUE_PREFIX)){
					int i = paramName.indexOf('.')<0 ? paramName.length() : paramName.indexOf('.');
					String key = paramName.substring(REMOVE_FILTER_VALUE_PREFIX.length(), i);
					if (key.length()>0 && getSelectedFilters().containsKey(key)){
						removedFilter = key;
						break;
					}
				}
			}

		}
		return removedFilter;
	}

	/**
	 * 
	 * @return
	 */
	public boolean isShowPicklist(){
		return !Util.isNullOrEmpty(getPicklistFilter());
	}

	/**
	 * 
	 * @return
	 */
	public boolean isRemoveFilter(){
		return !Util.isNullOrEmpty(getRemovedFilter());
	}
	
	/**
	 * 
	 * @return
	 */
	private Map<String,String> buildSearchCriteria(){
		
		Map<String,String> result = new HashMap<String,String>();
		
		Map<String,String> selected = getSelectedFilters();
		for (Iterator<String> keys=selected.keySet().iterator(); keys.hasNext();){
			String key = keys.next();
			String value = selected.get(key);
			if (value!=null && value.trim().length()>0){
				CustomSearchFilter filter = getAvailableFilters().get(key);
				if (filter!=null)
					result.put(filter.getUri(), value.trim());
			}
		}
		
		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see eionet.cr.web.action.AbstractSearchActionBean#getColumns()
	 */
	public List<SearchResultColumn> getColumns(){
		
		ArrayList<SearchResultColumn> list = new ArrayList<SearchResultColumn>();
		
		SubjectPredicateColumn col = new SubjectPredicateColumn();
		col.setPredicateUri(Predicates.RDF_TYPE);
		col.setTitle("Type");
		col.setSortable(true);
		list.add(col);
		
		col = new SubjectPredicateColumn();
		col.setPredicateUri(Predicates.RDFS_LABEL);
		col.setTitle("Title");
		col.setSortable(true);
		list.add(col);

		SubjectLastModifiedColumn col2 = new SubjectLastModifiedColumn();
		col2.setTitle("Date");
		col2.setSortable(true);
		list.add(col2);

		return list;
	}
}
