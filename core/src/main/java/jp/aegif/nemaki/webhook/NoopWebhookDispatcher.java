/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.webhook;

import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.impl.WebhookServiceImpl.WebhookDispatcher;

/**
 * No-operation implementation of WebhookDispatcher.
 * Used for testing and local development environments where actual HTTP
 * delivery is not desired. Logs webhook dispatch attempts without sending.
 */
public class NoopWebhookDispatcher implements WebhookDispatcher {
    
    private static final Log log = LogFactory.getLog(NoopWebhookDispatcher.class);
    
    @Override
    public void dispatch(String url, String payload, Map<String, String> headers, WebhookConfig config) {
        if (log.isDebugEnabled()) {
            log.debug("NoopWebhookDispatcher: Would dispatch to " + url);
            log.debug("NoopWebhookDispatcher: Payload length = " + (payload != null ? payload.length() : 0));
            log.debug("NoopWebhookDispatcher: Headers = " + (headers != null ? headers.keySet() : "none"));
        } else {
            log.info("NoopWebhookDispatcher: Skipped dispatch to " + url + " (noop mode)");
        }
    }
}
