package eionet.cr.index;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

import eionet.cr.common.Identifiers;
import eionet.cr.config.GeneralConfig;
import eionet.cr.harvest.util.RDFResource;
import eionet.cr.harvest.util.RDFResourceProperty;
import eionet.cr.util.FirstSeenTimestamp;
import eionet.cr.util.URIUtil;

/**
 * 
 * @author heinljab
 *
 */
public class Indexer {
	
	/** */
	private static final boolean AUTO_COMMIT = true;
	private static final boolean NO_AUTO_COMMIT = false;
	public static final String ALL_CONTENT_FIELD = Identifiers.ALL_LITERAL_CONTENT;
	
	/** */
	private static Log logger = LogFactory.getLog(Indexer.class);
	
	/** */
	private IndexWriter indexWriter = null;
	
	/** */
	private int countDocumentsIndexed = 0;
	
	/** */
	private String firstSeenTimestamp = null;
	
	/*
	 * (non-Javadoc)
	 * @see eionet.cr.index.Indexer#indexRDFResource(eionet.cr.harvest.util.RDFResource)
	 */
	public void indexRDFResource(RDFResource resource) throws IndexException{
		
		if (resource==null || resource.getId()==null || resource.getId().trim().length()==0)
			return;
		
		if (indexWriter==null){
			initIndexWriter();
			try{
				logger.debug("Clearing index for source: " + resource.getSourceId());
				indexWriter.deleteDocuments(new Term(Identifiers.SOURCE_ID, resource.getSourceId()));
			}
			catch (Exception e){
				throw new IndexException(e.toString(), e);
			}
		}
		
		if (countDocumentsIndexed==0)
			logger.debug("Indexing resources, source URL = " + resource.getSourceId());
		
		Document document = new Document();		
		document.add(constructField(Identifiers.DOC_ID, resource.getId()));
		document.add(constructField(Identifiers.SOURCE_ID, resource.getSourceId()));

		StringBuffer contentBuf = new StringBuffer(); // for collecting all literal values
		
		List<RDFResourceProperty> properties = resource.getProperties();
		for (int i=0; properties!=null && i<properties.size(); i++){
			
			RDFResourceProperty property = properties.get(i);
			String fieldValue = property.getValue().trim();
			if (fieldValue.length()>0){
				
				String fieldName = property.getId();
				document.add(constructField(fieldName, fieldValue));
				
				if (property.isLiteral())
					contentBuf.append(fieldValue).append(" ");
				
				// if this is a non-literal attribute, find decoded labels for it, and add them to the document
				if (!property.isLiteral() && property.isValueURL()){
					String[] decodedLabels = EncodingSchemes.getLabels(property.getValue());
					for (int j=0; decodedLabels!=null && j<decodedLabels.length; j++)
						document.add(constructField(fieldName, decodedLabels[j]));
				}
			}
		}
		
		// create the field that contains all literal content, add it to the document
		String trimmedContentBuf = contentBuf.toString().trim();
		if (trimmedContentBuf.length()>0)
			document.add(constructField(Indexer.ALL_CONTENT_FIELD, trimmedContentBuf));
		
		// if the resource is an encoding scheme, set the corresponding field
		boolean isEncScheme = resource.isEncodingScheme();
		if (isEncScheme)
			document.add(constructField(Identifiers.IS_ENCODING_SCHEME, Boolean.TRUE.toString()));
		
		// set the time the document was first seen
		document.add(constructField(Identifiers.FIRST_SEEN_TIMESTAMP,
				resource.getFirstSeenTimestamp()==null ? getFirstSeenTimestamp() : resource.getFirstSeenTimestamp()));
		
		// finally, update the document in index
		try {
			indexWriter.updateDocument(new Term(Identifiers.DOC_ID, resource.getId()), document);
		}
		catch (Exception e) {
			throw new IndexException(e.toString(), e);
		}
		countDocumentsIndexed++;
		
		// if this document is an encoding scheme, add it into EncodingSchemes
		if (isEncScheme)
			EncodingSchemes.update(resource.getId(), document.getValues(Identifiers.RDFS_LABEL));
		
		// if this document represents a resource that is a sub-property of another one, add it to SubProperties
		if (resource.isSubPropertyOf())
			SubProperties.addSubProperty(document.getValues(Identifiers.RDFS_SUB_PROPERTY_OF), resource.getId());
	}

	/**
	 * @throws IOException 
	 * @throws LockObtainFailedException 
	 * @throws CorruptIndexException 
	 * 
	 */
	private void initIndexWriter() throws IndexException{
		
		logger.debug("Initializing index writer");
		
		try{
			String indexLocation = GeneralConfig.getProperty(GeneralConfig.LUCENE_INDEX_LOCATION);
			indexWriter = new IndexWriter(FSDirectory.getDirectory(indexLocation), NO_AUTO_COMMIT, getAnalyzer());
		}
		catch (Exception e){
			throw new IndexException(e.toString(), e);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see eionet.cr.index.Indexer#close()
	 */
	public void close() throws CorruptIndexException, IOException{
		
		logger.debug("Closing index writer");
		
		if (indexWriter!=null){
			indexWriter.flush();
			indexWriter.optimize();
			indexWriter.close();
		}
	}

	/**
	 * @throws IOException 
	 * 
	 */
	public void abort() throws IOException {

		logger.debug("Aborting index writer");
		
		if (indexWriter!=null){
			indexWriter.abort();
		}
	}
	
	/**
	 * 
	 * @return
	 */
	private String getFirstSeenTimestamp(){
		
		if (firstSeenTimestamp==null){
			FirstSeenTimestamp timestamp = new FirstSeenTimestamp();
			firstSeenTimestamp = timestamp.toString();
		}
		return firstSeenTimestamp;
	}
	
	/**
	 * 
	 * @return
	 */
	public static Analyzer getAnalyzer(){
		return new StandardAnalyzer();
	}
	
	/**
	 * 
	 * @param fieldName
	 * @return
	 */
	public static boolean isAnalyzedField(String fieldName){
		
		if (fieldName.equals(Identifiers.DOC_ID))
			return false;
		else if (fieldName.equals(Identifiers.SOURCE_ID))
			return false;
		else if (fieldName.equals(Identifiers.FIRST_SEEN_TIMESTAMP))
			return false;
		else
			return true;
	}

	/**
	 * 
	 * @param value
	 * @return
	 */
	public static boolean isAnalyzedValue(String value){
		return URIUtil.isURI(value)==false;
	}
	
	/**
	 * 
	 * @param fieldName
	 * @return
	 */
	public static boolean isStoredField(String fieldName){
		return !fieldName.equals(ALL_CONTENT_FIELD);
	}

	/**
	 * 
	 * @param fieldName
	 * @return
	 */
	public static Field constructField(String fieldName, String fieldValue){
		
		return new Field(fieldName,
			fieldValue,
			isStoredField(fieldName) ? Field.Store.YES : Field.Store.NO,
			!isAnalyzedField(fieldName) || !isAnalyzedValue(fieldValue) ? Field.Index.UN_TOKENIZED : Field.Index.TOKENIZED);
	}
}
