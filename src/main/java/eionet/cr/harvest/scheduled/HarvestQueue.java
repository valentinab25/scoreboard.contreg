package eionet.cr.harvest.scheduled;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
public class HarvestQueue extends ConcurrentLinkedQueue<String>{

	/** */
	private static HarvestQueue urgentQueue;
	private static HarvestQueue normalQueue;
	
	/**
	 * 
	 */
	private HarvestQueue(){
		super();
	}
	
	/**
	 * 
	 * @return
	 */
	public static synchronized HarvestQueue getUrgent(){
		if (urgentQueue==null)
			urgentQueue = new HarvestQueue();
		return urgentQueue;
	}

	/**
	 * 
	 * @return
	 */
	public static synchronized HarvestQueue getNormal(){
		if (normalQueue==null)
			normalQueue = new HarvestQueue();
		return normalQueue;
	}
	
	/**
	 * 
	 * @param args
	 */
	public static void main(String[] args){
		
		HarvestQueue.getNormal().add("kala");
		HarvestQueue.getNormal().add("mees");
		System.out.println(HarvestQueue.getNormal().size());
		System.out.println(HarvestQueue.getNormal().poll());
		System.out.println(HarvestQueue.getNormal().poll());
		System.out.println(HarvestQueue.getNormal().poll());
		System.out.println(HarvestQueue.getNormal().size());
	}
}
