#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.AbstractCmisServiceWrapper;

/**
 * CMIS Service Wrapper.
 */
public class ${projectPrefix}CmisServiceWrapper extends AbstractCmisServiceWrapper {

    public ${projectPrefix}CmisServiceWrapper(CmisService service) {
        super(service);
    }
    
    // @Override
    // public void initialize(Object[] params) {
    // }

}
