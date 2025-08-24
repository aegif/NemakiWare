#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import java.math.BigInteger;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;

/**
 * CMIS Service Factory.
 */
public class ${projectPrefix}CmisServiceFactory extends AbstractServiceFactory {

    /** Default maxItems value for getTypeChildren()}. */
    private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger.valueOf(1000);

    /** Default depth value for getTypeDescendants(). */
    private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger.valueOf(-1);

    /**
     * Default maxItems value for getChildren() and other methods returning
     * lists of objects.
     */
    private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger.valueOf(100000);

    /** Default depth value for getDescendants(). */
    private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger.valueOf(10);

    @Override
    public void init(Map<String, String> parameters) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public CmisService getService(CallContext context) {
        // get the user name and password that the CallContextHandler has determined
        // - if the user is null, this is either an anonymous request or the CallContextHandler configuration is wrong
        // - the password may be null depending on the authentication method
        String user = context.getUsername();
        String password = context.getPassword();

        // if the authentication fails, throw a CmisPermissionDeniedException

        // create a new service object
        // (can also be pooled or stored in a ThreadLocal)
        ${projectPrefix}CmisService service = new ${projectPrefix}CmisService();

        // add the conformance CMIS service wrapper
        // (The wrapper catches invalid CMIS requests and sets default values
        // for parameters that have not been provided by the client.)
        ConformanceCmisServiceWrapper wrapperService = 
                new ConformanceCmisServiceWrapper(service, DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES, 
                        DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);

        // hand over the call context to the service object
        wrapperService.setCallContext(context);

        return wrapperService;
    }

}
