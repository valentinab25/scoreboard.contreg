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
package eionet.cr.harvest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.hp.hpl.jena.rdf.arp.ALiteral;
import com.hp.hpl.jena.rdf.arp.AResource;
import com.hp.hpl.jena.rdf.arp.StatementHandler;

import eionet.cr.common.LabelPredicates;
import eionet.cr.common.Predicates;
import eionet.cr.common.Subjects;
import eionet.cr.config.GeneralConfig;
import eionet.cr.dto.HarvestSourceDTO;
import eionet.cr.dto.ObjectDTO;
import eionet.cr.dto.SubjectDTO;
import eionet.cr.dto.UnfinishedHarvestDTO;
import eionet.cr.harvest.util.HarvestLog;
import eionet.cr.harvest.util.arp.AResourceImpl;
import eionet.cr.util.Hashes;
import eionet.cr.util.UnicodeUtils;
import eionet.cr.util.Util;
import eionet.cr.util.YesNoBoolean;
import eionet.cr.util.sql.ConnectionUtil;
import eionet.cr.util.sql.SQLUtil;

/**
 * 
 * @author Jaanus Heinlaid, e-mail: <a href="mailto:jaanus.heinlaid@tietoenator.com">jaanus.heinlaid@tietoenator.com</a>
 *
 */
public class RDFHandler implements StatementHandler, ErrorHandler{
	
	/** */
	private static final String URN_UUID_PREFIX = "urn:uuid:";
	
	/** */
	private SAXParseException saxError = null;
	private SAXParseException saxWarning = null;
	private boolean saxErrorSet = false;
	private boolean saxWarningSet = false;

	/** */
	private String sourceUrl;
	private long sourceUrlHash;
	private long genTime;
	private Log logger;
	private long sourceLastModified;

	/** */
	private Connection connection;

	/** */
	private PreparedStatement preparedStatementForTriples;
	private PreparedStatement preparedStatementForResources;

	/** */
	private int storedTriplesCount = 0;
	private int distinctSubjectsCount = 0;
	private HashSet<Long> distinctAnonSubjects = new HashSet<Long>(); 
	
	/** */
	private boolean clearPreviousContent = true;
	
	/** */
	private boolean parsingStarted = false;
	
	/** */
	private int tripleCounter = 0;
	private static final int TRIPLE_PROGRESS_INTERVAL = 50000;
	private static final int BULK_INSERT_SIZE = 50000;
	
	/** */
	private static final String EMPTY_STRING = "";
	
	/** */
	private Long currentSubjectHash = null;
	private Long currentPredicateHash = null;
	private HashSet<Long> distinctResources = new HashSet<Long>();
	
	/** */
	private boolean deriveExtraTriples = true;
	
	/** */
	private HashMap<String,ArrayList<ValueLanguagePair>> rdfValues = new HashMap<String,ArrayList<ValueLanguagePair>>();
	
	/** */
	private String uuidNamePrefix;
	
	/** */
	private String instantHarvestUser;
	
	/** */
	private String spoTempTableName = "SPO_TEMP";
	private String resourceTempTableName = "RESOURCE_TEMP";
	
	/** */
	private boolean rdfContentFound = false;
	
	/**
	 * 
	 * @param sourceUrl
	 * @param genTime
	 */
	public RDFHandler(String sourceUrl, long genTime){

		/* argument validations */
		
		if (StringUtils.isBlank(sourceUrl))
			throw new IllegalArgumentException("Source URL must not be null or blank!");
		else if (genTime<=0)
			throw new IllegalArgumentException("Gen-time must be > 0");
		
		/* field assignments */
		
		this.sourceUrl = sourceUrl;
		this.sourceUrlHash = Hashes.spoHash(sourceUrl);
		this.genTime = genTime;
		this.logger = new HarvestLog(sourceUrl, genTime, LogFactory.getLog(this.getClass()));
		this.uuidNamePrefix = createUuidNamePrefix(String.valueOf(this.sourceUrlHash), String.valueOf(this.genTime));
	}
	
	/*
	 *  (non-Javadoc)
	 * @see com.hp.hpl.jena.rdf.arp.StatementHandler#statement(com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource)
	 */
	public void statement(AResource subject, AResource predicate, AResource object){
		
		if (rdfContentFound==false){
			rdfContentFound = true;
		}
		
		statement(subject, predicate, object.isAnonymous() ? object.getAnonymousID() : object.getURI(), EMPTY_STRING, false, object.isAnonymous());
	}

	/*
	 *  (non-Javadoc)
	 * @see com.hp.hpl.jena.rdf.arp.StatementHandler#statement(com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.AResource, com.hp.hpl.jena.rdf.arp.ALiteral)
	 */
	public void statement(AResource subject, AResource predicate, ALiteral object){
		
		if (rdfContentFound==false){
			rdfContentFound = true;
		}
		
		statement(subject, predicate, object.toString(), object.getLang(), true, false);
	}
	
	/**
	 * 
	 * @param triple
	 */
	public void addSourceMetadata(SubjectDTO subjectDTO){
		
		if (subjectDTO!=null && subjectDTO.getPredicateCount()>0){
			
			AResource subject = new AResourceImpl(subjectDTO.getUri());
			for (String predicateUri:subjectDTO.getPredicates().keySet()){
				
				Collection<ObjectDTO> objects = subjectDTO.getObjects(predicateUri);
				if (objects!=null && !objects.isEmpty()){
					
					AResource predicate = new AResourceImpl(predicateUri);
					for (ObjectDTO object:objects){
						
						statement(subject, predicate, object.toString(), object.getLanguage(), object.isLiteral(), object.isAnonymous());
					}
				}
			}
		}

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

		tripleCounter++;
		
		try{
			// if this is the first statement, perform certain "startup" actions
			if (parsingStarted==false){
				onParsingStarted();
				parsingStarted = true;
			}
			
			// ignore statements with anonymous predicates
			if (predicate.isAnonymous()){
				return;
			}

			// ignore literal objects with length==0
			if (litObject && object.length()==0){
				return;
			}
			
			// set up subject URI, and subject and predicate hashes
			boolean anonSubject = subject.isAnonymous();
			String subjectUri = anonSubject ? generateUUID(subject.getAnonymousID()) : subject.getURI();
			long subjectHash = Hashes.spoHash(subjectUri);
			long predicateHash = Hashes.spoHash(predicate.getURI());
			
			// replace entity references in the object if it's a literal
			if (litObject){
				object = UnicodeUtils.replaceEntityReferences(object);
			}
			
			// replace object with its UUID, if it's an anonymous resource
			if (anonObject && !litObject){
				object = generateUUID(object);
			}

			// we remember rdfValues			
			if (anonSubject && predicate.getURI().equals(Predicates.RDF_VALUE)){
				ArrayList<ValueLanguagePair> subjectRdfValues = rdfValues.get(subject.getAnonymousID());
				if (subjectRdfValues==null){
					subjectRdfValues = new ArrayList<ValueLanguagePair>();
					rdfValues.put(subjectUri, subjectRdfValues);
				}
				subjectRdfValues.add(new ValueLanguagePair(object, objectLang));
			}

			// add the triple to the SQL insert batch
			addTriple(subjectHash, anonSubject, predicateHash, object, objectLang, litObject, anonObject);
			
			// if the object represents an anonymous subject, lookup the rdf:value(s) of the latter and insert it as derived
			if (anonObject && !litObject){
				ArrayList<ValueLanguagePair> objectRdfValues = rdfValues.get(object);
				if (objectRdfValues!=null){
					for (ValueLanguagePair pair:objectRdfValues){
						addTriple(subjectHash, anonSubject, predicateHash, pair.getValue(), pair.getLanguage(),
								true, false, Hashes.spoHash(object));
					}
				}
			}

			// if predicate different from previous one
			if (currentPredicateHash==null || predicateHash!=currentPredicateHash.longValue()){
				currentPredicateHash = new Long(predicateHash);
				if (!distinctResources.contains(predicateHash)){
					addResource(predicate.getURI(), predicateHash);
					distinctResources.add(predicateHash);
				}
			}
			
			// if subject different from previous one, add it into RESOURCES
			if (currentSubjectHash==null || subjectHash!=currentSubjectHash.longValue()){
				currentSubjectHash = new Long(subjectHash);
				if (!distinctResources.contains(subjectHash)){
					addResource(subjectUri, subjectHash);
					distinctResources.add(subjectHash);
					distinctSubjectsCount++;
				}
			}
			
			// if at BULK_INSERT_SIZE, execute the batch
			if (tripleCounter % BULK_INSERT_SIZE == 0){
				executeBatch();
			}
		}
		catch (Exception e){
			throw new LoadException(e.toString(), e);
		}
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private void onParsingStarted() throws SQLException{
		
		// make sure SPO_TEMP and RESOURCE_TEMP are empty, because we do only one scheduled harvest at a time
		// and so any possible leftovers from previous scheduled harvest must be deleted)
		if (!isInstantHarvest()){
			clearTemporaries();
		}
		
		// create unfinished harvest flag for the current harvest
		raiseUnfinishedHarvestFlag();
		
		// if instant harvest, then create temporary SPO_TEMP and RESOURCE_TEMP tables
		if (isInstantHarvest()){
			String tempTableSuffix = "_" + instantHarvestUser + "_" + Hashes.md5(sourceUrl + genTime);
			spoTempTableName = spoTempTableName + tempTableSuffix;
			resourceTempTableName = resourceTempTableName + tempTableSuffix;
			createTemporaryTempTables();
		}
		
		// prepare statements
		prepareStatementForTriples();
		prepareStatementForResources();

		// store the hash of the source itself
		addResource(sourceUrl, sourceUrlHash);
		
		// let the debugger know that we have got our first triple
		logger.debug("Got first triple");
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private void createTemporaryTempTables() throws SQLException{
		
		String sql1 = "create temporary table " + spoTempTableName + " like SPO_TEMP";
		String sql2 = "create temporary table " + resourceTempTableName + " like RESOURCE_TEMP";
		Statement stmt = null;
		try{
			stmt = getConnection().createStatement();
			stmt.executeUpdate(sql1);
			stmt.executeUpdate(sql2);
		}
		finally{
			SQLUtil.close(stmt);
		}
	}
	
	/**
	 * 
	 * @return
	 */
	private boolean isInstantHarvest(){
		return instantHarvestUser!=null;
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private void raiseUnfinishedHarvestFlag() throws SQLException{
		
		StringBuffer buf = new StringBuffer();
		buf.append("insert into UNFINISHED_HARVEST (SOURCE, GEN_TIME) values (").
		append(sourceUrlHash).append(", ").append(genTime).append(")");
		
		SQLUtil.executeUpdate(buf.toString(), getConnection());
	}
	
	/**
	 * 
	 * @throws SQLException
	 */
	private void deleteUnfinishedHarvestFlag() throws SQLException{
		RDFHandler.deleteUnfinishedHarvestFlag(this.sourceUrlHash, this.genTime, this.getConnection());
	}

	/**
	 * 
	 * @throws SQLException
	 */
	private static void deleteUnfinishedHarvestFlag(long sourceUrlHash, long genTime, Connection conn) throws SQLException{
		
		StringBuffer buf = new StringBuffer();
		buf.append("delete from UNFINISHED_HARVEST where SOURCE=").append(sourceUrlHash).append(" and GEN_TIME=").append(genTime);
		
		SQLUtil.executeUpdate(buf.toString(), conn);
	}

	/**
	 * 
	 * @param subjectHash
	 * @param anonSubject
	 * @param predicateHash
	 * @param object
	 * @param objectLang
	 * @param litObject
	 * @param anonObject
	 * @throws SQLException
	 */
	private void addTriple(long subjectHash, boolean anonSubject, long predicateHash,
			String object, String objectLang, boolean litObject, boolean anonObject) throws SQLException{
		
		addTriple(subjectHash, anonSubject, predicateHash, object, objectLang, litObject, anonObject, 0);
	}

	/**
	 * 
	 * @param subjectHash
	 * @param anonSubject
	 * @param predicateHash
	 * @param object
	 * @param objectLang
	 * @param litObject
	 * @param anonObject
	 * @param objSourceObject
	 * @throws SQLException
	 */
	private void addTriple(long subjectHash, boolean anonSubject, long predicateHash,
			String object, String objectLang, boolean litObject, boolean anonObject, long objSourceObject) throws SQLException{
		
		preparedStatementForTriples.setLong  ( 1, subjectHash);
		preparedStatementForTriples.setLong  ( 2, predicateHash);
		preparedStatementForTriples.setString( 3, object);
		preparedStatementForTriples.setLong  ( 4, Hashes.spoHash(object));
		preparedStatementForTriples.setObject( 5, Util.toDouble(object));
		preparedStatementForTriples.setString( 6, YesNoBoolean.format(anonSubject));
		preparedStatementForTriples.setString( 7, YesNoBoolean.format(anonObject));
		preparedStatementForTriples.setString( 8, YesNoBoolean.format(litObject));
		preparedStatementForTriples.setString( 9, objectLang==null ? "" : objectLang);		
		preparedStatementForTriples.setLong  (10, objSourceObject==0 ? 0 : sourceUrlHash);
		preparedStatementForTriples.setLong  (11, objSourceObject==0 ? 0 : genTime);
		preparedStatementForTriples.setLong  (12, objSourceObject);
		
		preparedStatementForTriples.addBatch();
	}
	
	/**
	 * Generates a name-based (i.e. version-3) UUID from the given name, that is unique across all harvests.
	 * 
	 * @param name
	 * @return
	 */
	private String generateUUID(String name){
		
		String uuid = UUID.nameUUIDFromBytes(new StringBuilder(this.uuidNamePrefix).append(name).toString().getBytes()).toString();
		return new StringBuilder(URN_UUID_PREFIX).append(uuid).toString();
	}

	/**
	 * 
	 * @param uuidNamePrefix
	 * @param name
	 * @return
	 */
	public static String generateUUID(String uuidNamePrefix, String name){
		
		String uuid = UUID.nameUUIDFromBytes(new StringBuilder(uuidNamePrefix).append(name).toString().getBytes()).toString();
		return new StringBuilder(URN_UUID_PREFIX).append(uuid).toString();
	}
	
	/**
	 * 
	 * @param sourceHash
	 * @param genTime
	 * @return
	 */
	public static String createUuidNamePrefix(String sourceHash, String genTime){
		
		return new StringBuilder(sourceHash).append(":").append(genTime).append(":").toString();
	}

	/**
	 * 
	 * @param uri
	 * @param uriHash
	 * @throws SQLException 
	 */
	private void addResource(String uri, long uriHash) throws SQLException{

		preparedStatementForResources.setString(1, uri);
		preparedStatementForResources.setLong(2, uriHash);
		
		preparedStatementForResources.addBatch();
	}

	/**
	 * @throws SQLException 
	 * 
	 */
	private void executeBatch() throws SQLException{
		
		preparedStatementForTriples.executeBatch();
		preparedStatementForTriples.clearParameters();
		System.gc();
		preparedStatementForResources.executeBatch();
		preparedStatementForResources.clearParameters();
		System.gc();
		
		if (tripleCounter % TRIPLE_PROGRESS_INTERVAL == 0){
			logger.debug("Progress: " + String.valueOf(tripleCounter) + " triples processed");
		}
	}

	/**
	 * 
	 * @throws SQLException
	 */
	protected void endOfFile() throws SQLException{
		
		// free memory allocated by distinctResources immediately
		distinctResources = null;
		
		// if there are any un-executed records left in the batch, execute them 
		if (tripleCounter % BULK_INSERT_SIZE != 0){
			executeBatch();
		}
		
		logger.debug("End of file, total of " + getTripleCounter() + " triples found in source");
	}

	/**
	 * 
	 * @throws SQLException
	 */
	private void prepareStatementForTriples() throws SQLException{
		
		String s = "insert into " + spoTempTableName + 
				" (SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE," +
				" ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, OBJ_SOURCE_OBJECT)" +
				" VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        preparedStatementForTriples = getConnection().prepareStatement(s);
	}

	/**
	 * 
	 * @throws SQLException 
	 */
	private void prepareStatementForResources() throws SQLException{

        preparedStatementForResources = getConnection().prepareStatement(
        		"insert ignore into " + resourceTempTableName + " (URI, URI_HASH) VALUES (?, ?)");
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

		commitTriples();
		commitResources();
		
		if (deriveExtraTriples){
			deriveParentProperties();
			deriveParentClasses();
			deriveLabels();
		}

		extractNewHarvestSources();
		
		try{
			clearTemporaries();
		}
		catch (Exception e){}
		
		deleteUnfinishedHarvestFlag();
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private void commitTriples() throws SQLException{

		/* copy triples from SPO_TEMP into SPO */

		logger.debug("Copying triples from " + spoTempTableName + " into SPO");
		
		StringBuffer buf = new StringBuffer();
		buf.append("insert ignore into SPO (").
		append("SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, OBJ_SOURCE_OBJECT, SOURCE, GEN_TIME").
		append(") select SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, OBJ_SOURCE_OBJECT, ").
		append(sourceUrlHash).append(", ").append(genTime).append(" from ").append(spoTempTableName);

		storedTriplesCount = SQLUtil.executeUpdate(buf.toString(), getConnection());
		logger.debug(storedTriplesCount + " triples inserted into SPO, " + distinctSubjectsCount + " distinct subjects identified");
		
		/* clear previous content if required (it is not, for example, required when doing a push-harvest) */
		
		if (clearPreviousContent){
			
			logger.debug("Deleting SPO rows of previous harvests");
			
			buf = new StringBuffer("delete from SPO where SOURCE=");
			buf.append(sourceUrlHash).append(" and GEN_TIME<").append(genTime);			
			SQLUtil.executeUpdate(buf.toString(), getConnection());
			
			buf = new StringBuffer("delete from SPO where OBJ_DERIV_SOURCE=");
			buf.append(sourceUrlHash).append(" and OBJ_DERIV_SOURCE_GEN_TIME<").append(genTime);			
			SQLUtil.executeUpdate(buf.toString(), getConnection());
		}
	}

	/**
	 * 
	 * @return
	 * @throws SQLException
	 */
	private void commitResources() throws SQLException{
		
		logger.debug("Copying resources from " + resourceTempTableName + " into RESOURCE");
		
		StringBuffer buf = new StringBuffer();
		buf.append("insert into RESOURCE (URI, URI_HASH, FIRSTSEEN_SOURCE, FIRSTSEEN_TIME, LASTMODIFIED_TIME) ").
		append("select URI, URI_HASH, ").append(sourceUrlHash).append(", ").append(genTime).append(", ").append(sourceLastModified).
		append(" from ").append(resourceTempTableName).append(" on duplicate key update RESOURCE.LASTMODIFIED_TIME=").append(sourceLastModified);
		
		SQLUtil.executeUpdate(buf.toString(), getConnection());
		logger.debug("Resources inserted into RESOURCE");
	}
	
	/**
	 * @throws SQLException 
	 * 
	 */
	private void deriveLabels() throws SQLException{
		
		logger.debug("Deriving labels");
		
		/* Derive labels FOR freshly harvested source. */
		
		StringBuffer buf = new StringBuffer().
		append("insert ignore into SPO ").
		append("(SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, OBJ_SOURCE_OBJECT, SOURCE, GEN_TIME) ").
		append("select distinct SPO_FRESH.SUBJECT, SPO_FRESH.PREDICATE, SPO_LABEL.OBJECT, SPO_LABEL.OBJECT_HASH, ").
		append("SPO_LABEL.OBJECT_DOUBLE, SPO_FRESH.ANON_SUBJ, ").
		append("'N' as ANON_OBJ, 'Y' as LIT_OBJ, SPO_LABEL.OBJ_LANG, ").
		append("SPO_LABEL.SOURCE as OBJ_DERIV_SOURCE, SPO_LABEL.GEN_TIME as OBJ_DERIV_SOURCE_GEN_TIME, ").
		append("SPO_FRESH.OBJECT_HASH as OBJ_SOURCE_OBJECT, SPO_FRESH.SOURCE, SPO_FRESH.GEN_TIME ").
		append("from SPO as SPO_FRESH, SPO as SPO_LABEL ").
		append("where SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.ANON_OBJ='N' and SPO_FRESH.LIT_OBJ='N' and SPO_FRESH.OBJECT_HASH=SPO_LABEL.SUBJECT and ").
		append("SPO_LABEL.LIT_OBJ='Y' and SPO_LABEL.ANON_OBJ='N' and ").
		append("SPO_LABEL.PREDICATE in (").
		append(LabelPredicates.getCommaSeparatedHashes()).
		append(")");
		
		int i = SQLUtil.executeUpdate(buf.toString(), getConnection());

		/* Derive labels FROM freshly harvested source. */
		
		buf = new StringBuffer().
		append("insert ignore into SPO (SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, OBJ_SOURCE_OBJECT, SOURCE, GEN_TIME) ").
		append("select distinct SPO_ALL.SUBJECT, SPO_ALL.PREDICATE, SPO_FRESH.OBJECT, SPO_FRESH.OBJECT_HASH, ").
		append("SPO_FRESH.OBJECT_DOUBLE, SPO_ALL.ANON_SUBJ, ").
		append("'N' as ANON_OBJ, 'Y' as LIT_OBJ, SPO_FRESH.OBJ_LANG, ").
		append("SPO_FRESH.SOURCE as OBJ_DERIV_SOURCE, SPO_FRESH.GEN_TIME as OBJ_DERIV_SOURCE_GEN_TIME, ").
		append("SPO_ALL.OBJECT_HASH as OBJ_SOURCE_OBJECT, SPO_ALL.SOURCE, SPO_ALL.GEN_TIME ").
		append("from SPO as SPO_ALL, SPO as SPO_FRESH ").
		append("where SPO_ALL.LIT_OBJ='N' and SPO_ALL.OBJECT_HASH=SPO_FRESH.SUBJECT and ").
		append("SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.LIT_OBJ='Y' and SPO_FRESH.ANON_OBJ='N' and ").
		append("SPO_FRESH.PREDICATE in (").
		append(LabelPredicates.getCommaSeparatedHashes()).
		append(")");
		
		int j = SQLUtil.executeUpdate(buf.toString(), getConnection());
		
		logger.debug(i + " labels derived FOR and " + j + " labels derived FROM the current harvest");
	}

	/**
	 * 
	 * @throws SQLException
	 */
	private void deriveParentProperties() throws SQLException{
		
		logger.debug("Deriving parent-properties");
		
		/* Derive parent-properties FOR freshly harvested source. */
		
		StringBuffer buf = new StringBuffer().
		append("insert ignore into SPO ").
		append("(SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, SOURCE, GEN_TIME) ").
		append("select distinct SPO_FRESH.SUBJECT, SUBPROP.OBJECT_HASH as PARENT_PREDICATE, ").
		append("SPO_FRESH.OBJECT, SPO_FRESH.OBJECT_HASH, SPO_FRESH.OBJECT_DOUBLE, SPO_FRESH.ANON_SUBJ, SPO_FRESH.ANON_OBJ, ").
		append("SPO_FRESH.LIT_OBJ, SPO_FRESH.OBJ_LANG, ").
		append("SUBPROP.SOURCE as DERIV_SOURCE, SUBPROP.GEN_TIME as DERIV_SOURCE_GEN_TIME, SPO_FRESH.SOURCE, SPO_FRESH.GEN_TIME ").
		append("from SPO as SPO_FRESH, SPO as SUBPROP ").
		append("where SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.PREDICATE=SUBPROP.SUBJECT").
		append(" and SUBPROP.PREDICATE=").append(Hashes.spoHash(Predicates.RDFS_SUBPROPERTY_OF)). 
		append(" and SUBPROP.LIT_OBJ='N' and SUBPROP.ANON_OBJ='N'");
		
		int i = SQLUtil.executeUpdate(buf.toString(), getConnection());

		/* Derive parent-properties FROM freshly harvested source. */
		
		buf = new StringBuffer().
		append("insert ignore into SPO (SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, SOURCE, GEN_TIME) ").		
		append("select distinct SPO.SUBJECT, SPO_FRESH.OBJECT_HASH as PARENT_PRED, SPO.OBJECT, SPO.OBJECT_HASH, SPO.OBJECT_DOUBLE, ").
		append("SPO.ANON_SUBJ, SPO.ANON_OBJ, SPO.LIT_OBJ, SPO.OBJ_LANG, ").
		append("SPO_FRESH.SOURCE as DERIV_SOURCE, SPO_FRESH.GEN_TIME as DERIV_SOURCE_GEN_TIME, SPO.SOURCE, SPO.GEN_TIME ").
		append("from SPO, SPO as SPO_FRESH").
		append(" where SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.PREDICATE=").append(Hashes.spoHash(Predicates.RDFS_SUBPROPERTY_OF)). 
		append(" and SPO_FRESH.LIT_OBJ='N' and SPO_FRESH.ANON_OBJ='N' and SPO_FRESH.SUBJECT=SPO.PREDICATE");
		
		int j = SQLUtil.executeUpdate(buf.toString(), getConnection());
		
		logger.debug(i + " parent-properties derived FOR and " + j + " parent-properties derived FROM the current harvest");
	}

	/**
	 * 
	 * @throws SQLException
	 */
	private void deriveParentClasses() throws SQLException{
		
		logger.debug("Deriving parent-classes");
		
		/* Derive parent-classes FOR freshly harvested source. */
		
		StringBuffer buf = new StringBuffer().
		append("insert ignore into SPO ").
		append("(SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, SOURCE, GEN_TIME) ").
		append("select distinct SPO_FRESH.SUBJECT, SPO_FRESH.PREDICATE, SUBCLASS.OBJECT, ").
		append("SUBCLASS.OBJECT_HASH, SUBCLASS.OBJECT_DOUBLE, SPO_FRESH.ANON_SUBJ, 'N' as ANON_OBJ, 'N' as LIT_OBJ, SUBCLASS.OBJ_LANG, ").
		append("SUBCLASS.SOURCE as DERIV_SOURCE, SUBCLASS.GEN_TIME as DERIV_SOURCE_GEN_TIME, ").
		append("SPO_FRESH.SOURCE, SPO_FRESH.GEN_TIME ").
		append("from SPO as SPO_FRESH, SPO as SUBCLASS").
		append(" where SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.PREDICATE=").append(Hashes.spoHash(Predicates.RDF_TYPE)).
		append(" and SPO_FRESH.OBJECT_HASH=SUBCLASS.SUBJECT").
		append(" and SUBCLASS.PREDICATE=").append(Hashes.spoHash(Predicates.RDFS_SUBCLASS_OF)). 
		append(" and SUBCLASS.LIT_OBJ='N' and SUBCLASS.ANON_OBJ='N'");
			
		int i = SQLUtil.executeUpdate(buf.toString(), getConnection());

		/* Derive parent-classes FROM freshly harvested source. */
		
		buf = new StringBuffer().
		append("insert ignore into SPO (SUBJECT, PREDICATE, OBJECT, OBJECT_HASH, OBJECT_DOUBLE, ANON_SUBJ, ANON_OBJ, LIT_OBJ, OBJ_LANG, ").
		append("OBJ_DERIV_SOURCE, OBJ_DERIV_SOURCE_GEN_TIME, SOURCE, GEN_TIME) ").
		append("select distinct SPO.SUBJECT, SPO.PREDICATE, SPO_FRESH.OBJECT, SPO_FRESH.OBJECT_HASH, SPO_FRESH.OBJECT_DOUBLE, ").
		append("SPO.ANON_SUBJ, 'N' as ANON_OBJ, 'N' as LIT_OBJ, SPO_FRESH.OBJ_LANG, ").
		append("SPO_FRESH.SOURCE as DERIV_SOURCE, SPO_FRESH.GEN_TIME as DERIV_SOURCE_GEN_TIME, ").
		append("SPO.SOURCE, SPO.GEN_TIME ").
		append("from SPO, SPO as SPO_FRESH").
		append(" where SPO.PREDICATE=").append(Hashes.spoHash(Predicates.RDF_TYPE)).
		append(" and SPO.OBJECT_HASH=SPO_FRESH.SUBJECT").
		append(" and SPO_FRESH.SOURCE=").append(sourceUrlHash).
		append(" and SPO_FRESH.GEN_TIME=").append(genTime).
		append(" and SPO_FRESH.PREDICATE=").append(Hashes.spoHash(Predicates.RDFS_SUBCLASS_OF)). 
		append(" and SPO_FRESH.LIT_OBJ='N' and SPO_FRESH.ANON_OBJ='N'");
		
		int j = SQLUtil.executeUpdate(buf.toString(), getConnection());
		
		logger.debug(i + " parent-classes derived FOR and " + j + " parent-classes derived FROM the current harvest");
	}

	/**
	 * @throws SQLException 
	 * 
	 */
	private void extractNewHarvestSources() throws SQLException{

		logger.debug("Extracting tracked files");
		
		// harvest interval for tracked files		
		Integer interval = Integer.valueOf(
				GeneralConfig.getProperty(
						GeneralConfig.HARVESTER_REFERRALS_INTERVAL,
						String.valueOf(HarvestSourceDTO.DEFAULT_REFERRALS_INTERVAL)));

		StringBuffer buf = new StringBuffer().
		append("insert ignore into HARVEST_SOURCE (URL, TRACKED_FILE, TIME_CREATED, INTERVAL_MINUTES, SOURCE, GEN_TIME) ").		
		append("select URI, 'Y', now(),").append(interval).append(",").append(sourceUrlHash).append(", ").append(genTime).
		append(" from SPO,RESOURCE where SPO.SOURCE=").append(sourceUrlHash).append(" and SPO.GEN_TIME=").append(genTime).
		append(" and SPO.PREDICATE=").append(Hashes.spoHash(Predicates.RDF_TYPE)).
		append(" and SPO.ANON_OBJ='N' and SPO.OBJECT_HASH=").append(Hashes.spoHash(Subjects.CR_FILE)).
		append(" and SPO.SUBJECT=RESOURCE.URI_HASH");
		
		int i = SQLUtil.executeUpdate(buf.toString(), getConnection());		
		logger.debug(i + " tracked files extracted and inserted as NEW harvest sources");
	}
	
	/**
	 * 
	 * @throws SQLException
	 */
	private void clearTemporaries() throws SQLException{
		
		logger.debug("Cleaning " + spoTempTableName + " and " + resourceTempTableName);
		RDFHandler.clearTemporaries(getConnection(), spoTempTableName, resourceTempTableName);
	}

	/**
	 * @throws SQLException 
	 * @throws SQLException 
	 * 
	 */
	private static void clearTemporaries(Connection conn) throws SQLException{
		clearTemporaries(conn, "SPO_TEMP", "RESOURCE_TEMP");
	}

	/**
	 * 
	 * @param conn
	 * @param spoTemp
	 * @param resourceTemp
	 * @throws SQLException
	 */
	private static void clearTemporaries(Connection conn, String spoTemp, String resourceTemp) throws SQLException{
		
		SQLUtil.executeUpdate("delete from " + spoTemp, conn);
		SQLUtil.executeUpdate("delete from " + resourceTemp, conn);
	}

	/**
	 * 
	 * @throws SQLException
	 */
	public static void rollbackUnfinishedHarvests() throws SQLException{

		ResultSet rs = null;
		Statement stmt = null;
		Connection conn = null;
		ArrayList<UnfinishedHarvestDTO> list = new ArrayList<UnfinishedHarvestDTO>();
		try{
			conn = ConnectionUtil.getConnection();
			stmt = conn.createStatement();
			rs = stmt.executeQuery("select * from UNFINISHED_HARVEST");
			while (rs!=null && rs.next()){
				list.add(UnfinishedHarvestDTO.create(rs.getLong("SOURCE"), rs.getLong("GEN_TIME")));
			}

			if (!list.isEmpty()){
				
				LogFactory.getLog(RDFHandler.class).debug("Deleting leftovers from unfinished harvests");
				
				for (UnfinishedHarvestDTO unfinishedHarvestDTO:list){
					
					// if the source is not actually being currently harvested, only then roll it back
					if (!CurrentHarvests.contains(unfinishedHarvestDTO.getSource())){
						RDFHandler.rollback(unfinishedHarvestDTO.getSource(), unfinishedHarvestDTO.getGenTime(), conn);
					}
				}
			}
		}
		finally{
			SQLUtil.close(rs);
			SQLUtil.close(stmt);
			SQLUtil.close(conn);
		}
	}
	
	/**
	 * 
	 * @throws SQLException
	 */
	protected void rollback() throws SQLException{

		logger.debug("Doing harvest rollback");
		RDFHandler.rollback(this.sourceUrlHash, this.genTime, this.getConnection());
	}

	/**
	 * 
	 * @param rollbackScope
	 * @throws SQLException
	 */
	private static void rollback(long sourceUrlHash, long genTime, Connection conn) throws SQLException{

		// delete rows of given harvest from SPO
		StringBuffer buf = new StringBuffer("delete from SPO where (SOURCE=");
		buf.append(sourceUrlHash).append(" and GEN_TIME=").append(genTime).
		append(") or (OBJ_DERIV_SOURCE=").append(sourceUrlHash).
		append(" and OBJ_DERIV_SOURCE_GEN_TIME=").append(genTime).append(")");
		
		SQLUtil.executeUpdate(buf.toString(), conn);

		// delete rows of current harvest from RESOURCE
		buf = new StringBuffer("delete from RESOURCE where FIRSTSEEN_SOURCE=");
		buf.append(sourceUrlHash).append(" and FIRSTSEEN_TIME=").append(genTime);
		SQLUtil.executeUpdate(buf.toString(), conn);
		
		// delete new extracted harvest sources
		buf = new StringBuffer("delete from HARVEST_SOURCE where SOURCE=").append(sourceUrlHash).
		append(" and GEN_TIME=").append(genTime);
		
		// 
		RDFHandler.deleteUnfinishedHarvestFlag(sourceUrlHash, genTime, conn);

		// delete all rows from SPO_TEMP and RESOURCE_TEMP
		try{
			RDFHandler.clearTemporaries(conn);
		}
		catch (Exception e){}
	}

    /*
     * (non-Javadoc)
     * @see org.xml.sax.ErrorHandler#error(org.xml.sax.SAXParseException)
     */
	public void error(SAXParseException e) throws SAXException {

		if (!saxErrorSet){
			logger.warn("SAX error encountered: " + e.toString(), e);			
			saxError = new SAXParseException(new String(e.getMessage()==null ? "" : e.getMessage()),
											 new String(e.getPublicId()==null ? "" : e.getPublicId()),
											 new String(e.getSystemId()==null ? "" : e.getSystemId()),
											 e.getLineNumber(),
											 e.getColumnNumber());
			saxErrorSet = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.xml.sax.ErrorHandler#warning(org.xml.sax.SAXParseException)
	 */
	public void warning(SAXParseException e) throws SAXException {
		
		if (!saxWarningSet){
			logger.warn("SAX warning encountered: " + e.toString(), e);
			saxWarning = new SAXParseException(new String(e.getMessage()==null ? "" : e.getMessage()),
					 new String(e.getPublicId()==null ? "" : e.getPublicId()),
					 new String(e.getSystemId()==null ? "" : e.getSystemId()),
					 e.getLineNumber(),
					 e.getColumnNumber());
			saxWarningSet = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.xml.sax.ErrorHandler#fatalError(org.xml.sax.SAXParseException)
	 */
	public void fatalError(SAXParseException e) throws SAXException {
		throw new LoadException(e.toString(), e);
	}

	/**
	 * @return the saxError
	 */
	public SAXParseException getSaxError() {
		return saxError;
	}

	/**
	 * @return the saxWarning
	 */
	public SAXParseException getSaxWarning() {
		return saxWarning;
	}

	/**
	 * @return the storedTriplesCount
	 */
	public int getStoredTriplesCount() {
		return storedTriplesCount;
	}

	/**
	 * @param clearPreviousContent the clearPreviousContent to set
	 */
	public void setClearPreviousContent(boolean clearPreviousContent) {
		this.clearPreviousContent = clearPreviousContent;
	}

	/**
	 * @return the distinctSubjectsCount
	 */
	public int getDistinctSubjectsCount() {
		return distinctSubjectsCount;
	}

	/**
	 * @return the tripleCounter
	 */
	public int getTripleCounter() {
		return tripleCounter;
	}

	/**
	 * @param deriveExtraTriples the deriveExtraTriples to set
	 */
	public void setDeriveExtraTriples(boolean deriveExtraTriples) {
		this.deriveExtraTriples = deriveExtraTriples;
	}
	
	/**
	 * 
	 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
	 *
	 */
	private class ValueLanguagePair{
		
		/** */
		private String value;
		private String language;
		
		/**
		 * 
		 * @param value
		 * @param language
		 */
		private ValueLanguagePair(String value, String language){
			this.value = value;
			this.language = language;
		}
		
		/**
		 * @return the value
		 */
		private String getValue() {
			return value;
		}
		/**
		 * @return the language
		 */
		private String getLanguage() {
			return language;
		}
	}

	/**
	 * @param sourceLastModified the sourceLastModified to set
	 */
	public void setSourceLastModified(long sourceLastModified) {
		this.sourceLastModified = sourceLastModified;
	}

	/**
	 * @param instantHarvestUser the instantHarvestUser to set
	 */
	public void setInstantHarvestUser(String instantHarvestUser) {
		this.instantHarvestUser = instantHarvestUser;
	}

	/**
	 * @return the rdfContentFound
	 */
	public boolean isRdfContentFound() {
		return rdfContentFound;
	}
}