package eionet.cr.util;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sourceforge.stripes.config.Configuration;
import net.sourceforge.stripes.exception.ExceptionHandler;

/**
 * @author altnyris
 *
 */
public class CrExceptionHandler implements ExceptionHandler {
    /** Doesn't have to do anything... */
    public void init(Configuration configuration) throws Exception { }

    /** Do something a bit more complicated that just going to a view. */
    public void handle(Throwable throwable,
                       HttpServletRequest request,
                       HttpServletResponse response) throws ServletException, IOException {

    	request.setAttribute("exception", throwable);
    	request.getRequestDispatcher("/pages/error.jsp").forward(request, response);
    	
    }
}
