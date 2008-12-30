package eionet.cr.harvest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.hp.hpl.jena.rdf.arp.ALiteral;
import com.hp.hpl.jena.rdf.arp.AResource;
import com.hp.hpl.jena.rdf.arp.StatementHandler;

import eionet.cr.common.CRRuntimeException;
import eionet.cr.util.Hashes;
import eionet.cr.util.UnicodeUtils;
import eionet.cr.util.YesNoBoolean;
import eionet.cr.util.sql.ConnectionUtil;
import eionet.cr.util.sql.SQLUtil;

/**
 * 
 * @author Jaanus Heinlaid, e-mail: <a href="mailto:jaanus.heinlaid@tietoenator.com">jaanus.heinlaid@tietoenator.com</a>
 *
 */
public class NewRDFHandler implements StatementHandler, ErrorHandler{

	/** */
	private static Log logger = LogFactory.getLog(NewRDFHandler.class);
	
	/** */
	private List<SAXParseException> saxErrors = new ArrayList<SAXParseException>();
	private List<SAXParseException> saxWarnings = new ArrayList<SAXParseException>();
	
	/** */
	private long anonIdSeed;
	
	/** */
	private HashSet<String> usedNamespaces = new HashSet();
	
	/** */
	private String sourceUrl;
	private long sourceUrlHash;
	private long genTime;
	
	/** */
	private PreparedStatement preparedStatementForTriples;
	private PreparedStatement preparedStatementForResources;
	
	/** */
	private Connection connection;

	/** */
	private int storedTriplesCount = 0;

	/**
	 * 
	 */
	public NewRDFHandler(String sourceUrl, long genTime){
		
		if (sourceUrl==null || sourceUrl.length()==0 || genTime<=0)
			throw new IllegalArgumentException();
		
		this.sourceUrl = sourceUrl;
		this.sourceUrlHash = Hashes.spoHash(sourceUrl);
		this.genTime = genTime;
		
		// set the hash-seed for anonymous ids
		anonIdSeed = Hashes.spoHash(sourceUrl + String.valueOf(genTime));
	}
	
	/*
	 *  (non-Javadoc)
	 * @see com.hp.hpl.jena.rdf.arp.StatementHandler#statement(com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource)
	 */
	public void statement(AResource subject, AResource predicate, AResource object){
		
		statement(subject, predicate, object.isAnonymous() ? object.getAnonymousID() : object.getURI(), "", false, object.isAnonymous());
	}

	/*
	 *  (non-Javadoc)
	 * @see com.hp.hpl.jena.rdf.arp.StatementHandler#statement(com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.ALiteral)
	 */
	public void statement(AResource subject, AResource predicate, ALiteral object){
		
		statement(subject, predicate, object.toString(), object.getLang(), true, false);
	}

	/**
	 * 
	 * @param subject
	 * @param predicate
	 * @param object
	 * @param objectLang
	 * @param litObject
	 * @param anonObject
	 */
	private void statement(AResource subject, AResource predicate,
							String object, String objectLang, boolean litObject, boolean anonObject){

		if (!predicate.isAnonymous()){ // we ignore statements with anonymous predicates
			
			try{
				parseForUsedNamespaces(predicate);
				
				long subjectHash = spoHash(subject.isAnonymous() ? subject.getAnonymousID() : subject.getURI(), subject.isAnonymous());
				long predicateHash = spoHash(predicate.getURI(), false);
				if (litObject)
					object = UnicodeUtils.replaceEntityReferences(object);
				
				int i = storeTriple(subjectHash, subject.isAnonymous(), predicateHash, object, objectLang, litObject, anonObject);
				if (i>0){
					storeResource(predicate.getURI(), predicateHash);
					if (!subject.isAnonymous()){
						storeResource(subject.getURI(), subjectHash);
					}
				}
			}
			catch (Exception e){
				throw new LoadException(e.toString(), e);
			}
		}
	}

	/**
	 * 
	 * @param subject
	 * @param anonSubject
	 * @param predicate
	 * @param anonPredicate
	 * @param object
	 * @param objectLang
	 * @param litObject
	 * @param anonObject
	 * @throws SQLException 
	 */
	private int storeTriple(long subjectHash, boolean anonSubject, long predicateHash,
							String object, String objectLang, boolean litObject, boolean anonObject) throws SQLException{
		
		if (preparedStatementForTriples==null){
			prepareForTriples();
			logger.debug("Storing first triple for " + sourceUrl);
		}
		
		preparedStatementForTriples.setLong  ( 1, subjectHash);
		preparedStatementForTriples.setLong  ( 2, predicateHash);
		preparedStatementForTriples.setString( 3, object);
		preparedStatementForTriples.setLong  ( 4, spoHash(object, anonObject));
		preparedStatementForTriples.setString( 5, YesNoBoolean.format(anonSubject));
		preparedStatementForTriples.setString( 6, YesNoBoolean.format(anonObject));
		preparedStatementForTriples.setString( 7, YesNoBoolean.format(litObject));
		preparedStatementForTriples.setString( 8, objectLang==null ? "" : objectLang);
		
		return preparedStatementForTriples.executeUpdate();
	}

	/**
	 * 
	 * @param uri
	 * @param uriHash
	 * @param type
	 * @throws SQLException 
	 */
	private int storeResource(String uri, long uriHash) throws SQLException{

		if (preparedStatementForResources==null){
			prepareForResources();
			logger.debug("Storing first resource for " + sourceUrl);
		}
		
		preparedStatementForResources.setString(1, uri);
		preparedStatementForResources.setLong(2, uriHash);
		
		return preparedStatementForResources.executeUpdate();
	}
	
	/**
	 * 
	 * @param s
	 * @param isAnonymous
	 * @return
	 */
	private long spoHash(String s, boolean isAnonymous){
		return isAnonymous ? Hashes.spoHash(s, anonIdSeed) : Hashes.spoHash(s);
	}

	/**
	 * 
	 * @param predicate
	 */
	private void parseForUsedNamespaces(AResource predicate){
		
		if (!predicate.isAnonymous()){
			String predicateUri = predicate.getURI();
			int i = predicateUri.lastIndexOf("#");
            if (i<0)
            	i = predicateUri.lastIndexOf("/");
            if (i>0){
                if (predicateUri.charAt(i)=='/')
                	i++;
                usedNamespaces.add(predicateUri.substring(0, i));
            }
		}
	}

	/**
	 * Does "delete from SPO_TEMP" and then prepares this.preparedStatementForTriples.
	 * 
	 * @throws SQLException
	 */
	private void prepareForTriples() throws SQLException{
		
		logger.debug("Clearing SPO_TEMP");
		
		// make sure SPO_TEMP is empty, let exception be thrown if this does not succeed
		// (because we do only one harvest at a time, so possible leftovers from previous harvest must be deleted)
		SQLUtil.executeUpdate("delete from SPO_TEMP", getConnection());
		
		// prepare this.preparedStatementForTriples
		StringBuffer buf = new StringBuffer();
        buf.append("insert into SPO_TEMP (SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, ").
        append("ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        preparedStatementForTriples = getConnection().prepareStatement(buf.toString());
	}

	/**
	 * First does "delete from RESOURCE_TEMP" and then prepares this.preparedStatementForResources.
	 * 
	 * @throws SQLException 
	 */
	private void prepareForResources() throws SQLException{

		logger.debug("Clearing RESOURCE_TEMP");
		
		// make sure RESOURCE_TEMP is empty, let exception be thrown if this does not succeed
		// (because we do only one harvest at a time, so possible leftovers from previous harvest must be deleted)
		SQLUtil.executeUpdate("delete from RESOURCE_TEMP", getConnection());

		// prepare this.preparedStatementForResources
        preparedStatementForResources = getConnection().prepareStatement("insert ignore into RESOURCE_TEMP (URI, URI_HASH) VALUES (?, ?)");
	}

	/**
	 * @return the connection
	 * @throws SQLException 
	 */
	private Connection getConnection() throws SQLException {
		
		if (connection==null){
			connection = ConnectionUtil.getConnection();
		}
		return connection;
	}
	
	/**
	 * 
	 */
	protected void closeResources(){
		
		SQLUtil.close(preparedStatementForTriples);
		SQLUtil.close(preparedStatementForResources);
		SQLUtil.close(connection);
	}

	/**
	 * @throws SQLException 
	 * 
	 */
	protected void commit() throws SQLException{
		
		int i = commitTriples();
		if (i>0){
			commitResources();
			storedTriplesCount = storedTriplesCount + i;
		}
		
		clearTemporaries();
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private int commitTriples() throws SQLException{

		/* copy triples from SPO_TEMP into SPO */

		logger.debug("Copying triples from SPO_TEMP into SPO, sourceUrl: " + sourceUrl);
		
		StringBuffer buf = new StringBuffer();
		buf.append("insert high_priority into SPO (").
		append("SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, SOURCE, GEN_TIME").
		append(") select distinct SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append(sourceUrlHash).append(", ").append(genTime).append(" from SPO_TEMP");
		
		int committedTriplesCount = SQLUtil.executeUpdate(buf.toString(), getConnection());
		
		/* delete SPO records from previous harvests of this source */
		
		logger.debug("Deleting SPO rows of previous harvests of " + sourceUrl);
		
		buf = new StringBuffer("delete from SPO where SOURCE=");
		buf.append(sourceUrlHash).append(" and GEN_TIME<").append(genTime);
		
		SQLUtil.executeUpdate(buf.toString(), getConnection());
		
		return committedTriplesCount;
	}

	/**
	 * 
	 * @return
	 * @throws SQLException
	 */
	private int commitResources() throws SQLException{
		
		logger.debug("Copying resources from RESOURCE_TEMP into RESOURCE, sourceUrl: " + sourceUrl);
		
		StringBuffer buf = new StringBuffer();
		buf.append("insert high_priority ignore into RESOURCE (URI, URI_HASH, FIRSTSEEN_SOURCE, FIRSTSEEN_TIME) ").
		append("select URI, URI_HASH, ").append(sourceUrlHash).append(", ").append(genTime).append(" from RESOURCE_TEMP");
		
		return SQLUtil.executeUpdate(buf.toString(), getConnection());
	}

	/**
	 * @throws SQLException 
	 * 
	 */
	private void clearTemporaries() throws SQLException{
		
		logger.debug("Cleaning the temporary tables after harvesting " + sourceUrl);
		
		try{
			SQLUtil.executeUpdate("delete from SPO_TEMP", getConnection());
		}
		catch (Exception e){}
		try{
			SQLUtil.executeUpdate("delete from RESOURCE_TEMP", getConnection());
		}
		catch (Exception e){}
	}

	/**
	 * @throws SQLException 
	 * 
	 */
	protected void rollback() throws SQLException{

		// delete rows of current harvest from SPO
		StringBuffer buf = new StringBuffer("delete from SPO where SOURCE=");
		buf.append(sourceUrlHash).append(" and GEN_TIME=").append(genTime);
		SQLUtil.executeUpdate(buf.toString(), getConnection());

		// delete rows of current harvest from RESOURCE_TEMP
		buf = new StringBuffer("delete from RESOURCE_TEMP where FIRSTSEEN_SOURCE=");
		buf.append(sourceUrlHash).append(" and FIRSTSEEN_TIME=").append(genTime);
		SQLUtil.executeUpdate(buf.toString(), getConnection());

		// delete all rows from SPO_TEMP and RESOURCE_TEMP
		clearTemporaries();
	}

    /*
     * (non-Javadoc)
     * @see org.xml.sax.ErrorHandler#error(org.xml.sax.SAXParseException)
     */
	public void error(SAXParseException e) throws SAXException {
		saxErrors.add(e);
		logger.error("SAX error encountered: " + e.toString(), e);
	}

	/*
	 * (non-Javadoc)
	 * @see org.xml.sax.ErrorHandler#fatalError(org.xml.sax.SAXParseException)
	 */
	public void fatalError(SAXParseException e) throws SAXException {
		throw new LoadException(e.toString(), e);
	}

	/*
	 * (non-Javadoc)
	 * @see org.xml.sax.ErrorHandler#warning(org.xml.sax.SAXParseException)
	 */
	public void warning(SAXParseException e) throws SAXException {
		saxWarnings.add(e);
		logger.warn("SAX warning encountered: " + e.toString(), e);
	}

	/**
	 * @return the saxErrors
	 */
	public List<SAXParseException> getSaxErrors() {
		return saxErrors;
	}

	/**
	 * @return the saxWarnings
	 */
	public List<SAXParseException> getSaxWarnings() {
		return saxWarnings;
	}

	/**
	 * @return the storedTriplesCount
	 */
	public int getStoredTriplesCount() {
		return storedTriplesCount;
	}
}