package eionet.cr.search.util;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import eionet.cr.common.Identifiers;
import eionet.cr.util.URIUtil;
import eionet.cr.util.URLUtil;
import eionet.cr.util.Util;

/**
 * 
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
public class SimpleSearchExpression{

	/** */
	private String expression = null;
	private Boolean isUrlSearch = null;
	
	/**
	 * 
	 * @param queryStr
	 */
	public SimpleSearchExpression(String expression){
		
		this.expression = expression;
	}
	
	/**
	 * 
	 * @return
	 */
	public String toLuceneQueryString(){
		
		if (isUriSearch())
			return processQueryForURLSearch(expression);
		else	
			return expression;
	}

	/*
	 * (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		return expression;
	}

	/**
	 * @return the analyzerName
	 */
	public Analyzer getAnalyzer() {
		if (isUriSearch())
			return new KeywordAnalyzer();
		else
			return new StandardAnalyzer();
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean isUriSearch(){

		return URIUtil.isURI(expression.trim());
	}
	
	/**
	 * 
	 * @param query
	 * @return
	 */
	private static String processQueryForURLSearch(String query){
		
		StringBuffer buf = new StringBuffer(Identifiers.DOC_ID);
		buf.append(":").append(Util.escapeForLuceneQuery(query.trim()));
		return buf.toString();
	}
}
