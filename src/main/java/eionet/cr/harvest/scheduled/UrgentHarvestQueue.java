package eionet.cr.harvest.scheduled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import eionet.cr.dao.DAOException;
import eionet.cr.dao.DAOFactory;
import eionet.cr.dto.UrgentHarvestQueueItemDTO;
import eionet.cr.harvest.HarvestException;

/**
 * 
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
public class UrgentHarvestQueue{
	
	/** */
	private static Log logger = LogFactory.getLog(UrgentHarvestQueue.class);

	/**
	 * 
	 * @param priority
	 * @throws HarvestException 
	 */
	public static synchronized void addPullHarvest(String url) throws HarvestException{
		
		addPullHarvests(Collections.singletonList(url));
	}
	
	/**
	 * 
	 * @param urls
	 * @param priority
	 * @throws HarvestException
	 */
	public static synchronized void addPullHarvests(List<String> urls) throws HarvestException{
		
		try {
			List<UrgentHarvestQueueItemDTO> dtos = new ArrayList<UrgentHarvestQueueItemDTO>();
			for (Iterator<String> i=urls.iterator(); i.hasNext();){
				UrgentHarvestQueueItemDTO dto = new UrgentHarvestQueueItemDTO();
				dto.setUrl(i.next());
				dtos.add(dto);
			}
			
			DAOFactory.getDAOFactory().getHarvestQueueDAO().addPullHarvests(dtos);
			
			for (Iterator<String> i=urls.iterator(); i.hasNext();){
				logger.debug("Pull harvest added to the urgent queue, url = " + i.next());
			}
		}
		catch (DAOException e) {
			throw new HarvestException(e.toString(), e);
		}
	}

	/**
	 * 
	 * @param pushContent
	 * @param url
	 * @param priority
	 * @throws HarvestException 
	 */
	public static synchronized void addPushHarvest(String pushContent, String url) throws HarvestException{
		
		UrgentHarvestQueueItemDTO dto = new UrgentHarvestQueueItemDTO();
		dto.setUrl(url);
		dto.setPushedContent(pushContent);
		
		try {
			DAOFactory.getDAOFactory().getHarvestQueueDAO().addPushHarvest(dto);
			logger.debug("Push harvest added to the urgent queue, url = " + url);
		}
		catch (DAOException e) {
			throw new HarvestException(e.toString(), e);
		}
	}
	
	/**
	 * 
	 * @param priority
	 * @return
	 * @throws HarvestException 
	 */
	public static synchronized UrgentHarvestQueueItemDTO poll() throws HarvestException{
		
		try {
			return DAOFactory.getDAOFactory().getHarvestQueueDAO().poll();
		}
		catch (DAOException e) {
			throw new HarvestException(e.toString(), e);
		}
	}

	/**
	 * 
	 * @param priority
	 * @return
	 * @throws HarvestException 
	 */
	public static synchronized UrgentHarvestQueueItemDTO peek() throws HarvestException{
		
		try {
			return DAOFactory.getDAOFactory().getHarvestQueueDAO().peek();
		}
		catch (DAOException e) {
			throw new HarvestException(e.toString(), e);
		}
	}
	
	/**
	 * 
	 * @param urgentHarvestQueueItemDTO
	 * @throws HarvestException
	 */
	public static synchronized void deleteQueueItem(UrgentHarvestQueueItemDTO urgentHarvestQueueItemDTO) throws HarvestException{
		
		try {
			DAOFactory.getDAOFactory().getHarvestQueueDAO().deleteQueueItem(urgentHarvestQueueItemDTO);
		}
		catch (DAOException e) {
			throw new HarvestException(e.toString(), e);
		}
	}
}