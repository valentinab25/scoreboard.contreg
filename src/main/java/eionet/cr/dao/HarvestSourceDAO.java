package eionet.cr.dao;

import java.util.List;

import eionet.cr.dto.HarvestDTO;
import eionet.cr.dto.HarvestSourceDTO;

/**
 * @author altnyris
 *
 */
public interface HarvestSourceDAO {
	/**
     * @return list of harvesting sources
     * @throws DAOException
     */
    public List<HarvestSourceDTO> getHarvestSources() throws DAOException;

    /**
     * 
     * @param type
     * @return
     * @throws DAOException
     */
    public List<HarvestSourceDTO> getHarvestSourcesByType(String type) throws DAOException;
    
    /**
     * @return harvesting sources
     * @throws DAOException
     * @param int harvestSourceID
     */
    public HarvestSourceDTO getHarvestSourceById(Integer harvestSourceID) throws DAOException;
    
    /**
     * 
     * @param url
     * @return
     * @throws DAOException
     */
    public HarvestSourceDTO getHarvestSourceByUrl(String url) throws DAOException;
    
    /**
     * @throws DAOException
     * @param HarvestSourceDTO source
     */
    public Integer addSource(HarvestSourceDTO source, String user) throws DAOException;

    /**
     * @throws DAOException
     * @param HarvestSourceDTO source
     */
    public Integer addSourceIgnoreDuplicate(HarvestSourceDTO source, String user) throws DAOException;

    /**
     * @throws DAOException
     * @param HarvestSourceDTO source
     */
    public void editSource(HarvestSourceDTO source) throws DAOException;
    
    /**
     * @throws DAOException
     * @param HarvestSourceDTO source
     */
    public void deleteSource(HarvestSourceDTO source) throws DAOException;

    /**
     * 
     * @param sourceId
     * @param numStatements
     * @param numResources
     * @param sourceAvailable 
     * @throws DAOException
     */
    public void updateHarvestFinished(int sourceId, Integer numStatements, Integer numResources, Boolean sourceAvailable) throws DAOException;
}
