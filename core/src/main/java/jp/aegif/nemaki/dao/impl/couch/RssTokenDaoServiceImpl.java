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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - RSS token DAO service implementation
 ******************************************************************************/
package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.model.couch.CouchRssToken;
import jp.aegif.nemaki.rss.RssToken;
import jp.aegif.nemaki.rss.RssTokenDaoService;

/**
 * CouchDB implementation of RssTokenDaoService.
 *
 * Uses CloudantClientPool for database operations.
 */
public class RssTokenDaoServiceImpl implements RssTokenDaoService {

    private static final Log log = LogFactory.getLog(RssTokenDaoServiceImpl.class);

    private CloudantClientPool connectorPool;
    private RepositoryInfoMap repositoryInfoMap;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    @Override
    public RssToken create(String repositoryId, RssToken token) {
        if (token == null) {
            return null;
        }

        try {
            CouchRssToken couchToken = new CouchRssToken(token);
            connectorPool.getClient(repositoryId).create(couchToken);

            log.debug("Created RSS token: " + couchToken.getId() + " for user: " + token.getUserId());
            return couchToken.convertToRssToken();
        } catch (Exception e) {
            log.error("Failed to create RSS token: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create RSS token", e);
        }
    }

    @Override
    public RssToken getById(String repositoryId, String tokenId) {
        if (tokenId == null) {
            return null;
        }

        try {
            CouchRssToken couchToken = connectorPool.getClient(repositoryId)
                .get(CouchRssToken.class, tokenId);

            if (couchToken != null) {
                return couchToken.convertToRssToken();
            }
            return null;
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            // Null is "no such token" — the answer disable/delete report as "Token not
            // found", telling the administrator a token that still works is already gone.
            log.error("Failed to get RSS token: " + e.getMessage(), e);
            throw new IllegalStateException("the RSS token '" + tokenId
                    + "' could not be read; this is NOT a finding that it does not exist", e);
        }
    }

    @Override
    public RssToken getByToken(String repositoryId, String tokenValue) {
        if (tokenValue == null) {
            return null;
        }

        // If repositoryId is specified, search that repository only
        if (repositoryId != null && !repositoryId.isEmpty()) {
            return searchTokenInRepository(repositoryId, tokenValue);
        }

        // Fallback: search across all repositories
        for (String repoId : repositoryInfoMap.keys()) {
            RssToken found = searchTokenInRepository(repoId, tokenValue);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private RssToken searchTokenInRepository(String repositoryId, String tokenValue) {
        try {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", tokenValue);
            queryParams.put("include_docs", true);
            queryParams.put("limit", 1);

            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "rssTokensByToken", queryParams);

            if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
                ViewResultRow row = result.getRows().get(0);
                if (row.getDoc() != null) {
                    Map<String, Object> docMap = extractDocMap(row.getDoc());
                    if (docMap != null) {
                        CouchRssToken couchToken = new CouchRssToken(docMap);
                        return couchToken.convertToRssToken();
                    }
                }
            }
        } catch (Exception e) {
            // Null here becomes 401 "Invalid or expired token" — the safe DIRECTION for an
            // access check, but a false statement: the token may be perfectly valid while
            // CouchDB times out. Throwing turns it into a 500 the subscriber retries.
            log.error("Failed to search RSS token in repository " + repositoryId + ": " + e.getMessage(), e);
            throw new IllegalStateException("the RSS token could not be validated against '"
                    + repositoryId + "'; this is NOT a finding that it is invalid", e);
        }
        return null;
    }

    @Override
    public List<RssToken> getByUserId(String repositoryId, String userId) {
        List<RssToken> tokens = new ArrayList<>();

        if (userId == null) {
            return tokens;
        }

        // This listing is what an administrator REVOKES from: a silently short answer means
        // a token that keeps its access after the admin believes every token is gone. Same
        // fail-closed rule as the user/group listings.
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("key", userId);
        queryParams.put("include_docs", true);

        ViewResult result = connectorPool.getClient(repositoryId)
            .queryView("_repo", "rssTokensByUserId", queryParams);

        if (result == null || result.getRows() == null) {
            throw new IllegalStateException("the RSS token view answered without rows for user '"
                    + userId + "'; that is not the same as the user having no tokens");
        }
        for (ViewResultRow row : result.getRows()) {
            if (row.getDoc() == null) {
                throw new IllegalStateException("an RSS token row for user '" + userId
                        + "' carries no document; refusing to answer the token list short");
            }
            Map<String, Object> docMap = extractDocMap(row.getDoc());
            if (docMap == null) {
                throw new IllegalStateException("an RSS token row for user '" + userId
                        + "' could not be read; refusing to answer the token list short");
            }
            CouchRssToken couchToken = new CouchRssToken(docMap);
            tokens.add(couchToken.convertToRssToken());
        }

        return tokens;
    }

    @Override
    public RssToken update(String repositoryId, RssToken token) {
        if (token == null || token.getId() == null) {
            return null;
        }

        try {
            // Get existing document to get revision
            CouchRssToken existing = connectorPool.getClient(repositoryId)
                .get(CouchRssToken.class, token.getId());

            if (existing == null) {
                log.warn("RSS token not found for update: " + token.getId());
                return null;
            }

            CouchRssToken couchToken = new CouchRssToken(token);
            couchToken.setId(existing.getId());
            couchToken.setRevision(existing.getRevision());

            connectorPool.getClient(repositoryId).update(couchToken);

            log.debug("Updated RSS token: " + couchToken.getId());
            return couchToken.convertToRssToken();
        } catch (NotFoundException e) {
            log.warn("RSS token not found for update: " + token.getId());
            return null;
        } catch (Exception e) {
            log.error("Failed to update RSS token: " + e.getMessage(), e);
            throw new RuntimeException("Failed to update RSS token", e);
        }
    }

    @Override
    public void delete(String repositoryId, String tokenId) {
        if (tokenId == null) {
            return;
        }

        try {
            CouchRssToken existing = connectorPool.getClient(repositoryId)
                .get(CouchRssToken.class, tokenId);

            if (existing != null) {
                connectorPool.getClient(repositoryId).delete(tokenId, existing.getRevision());
                log.debug("Deleted RSS token: " + tokenId);
            }
        } catch (NotFoundException e) {
            log.debug("RSS token not found for deletion: " + tokenId);
        } catch (Exception e) {
            // Swallowing here let the caller report "Token deleted" (and audit it as a
            // success) while the document survived — the revoked token then reloaded into
            // the cache on the next miss and kept its feed access.
            log.error("Failed to delete RSS token: " + e.getMessage(), e);
            throw new IllegalStateException("the RSS token '" + tokenId + "' could not be"
                    + " deleted; reporting success would leave a revoked token alive", e);
        }
    }

    @Override
    public int deleteExpired(String repositoryId) {
        int deletedCount = 0;

        try {
            long now = System.currentTimeMillis();

            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("endkey", now);
            queryParams.put("include_docs", true);
            queryParams.put("limit", 1000);

            ViewResult result = connectorPool.getClient(repositoryId)
                .queryView("_repo", "rssTokensByExpiresAt", queryParams);

            if (result != null && result.getRows() != null) {
                for (ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        Map<String, Object> docMap = extractDocMap(row.getDoc());
                        if (docMap != null) {
                            String id = (String) docMap.get("_id");
                            String rev = (String) docMap.get("_rev");
                            if (id != null && rev != null) {
                                try {
                                    connectorPool.getClient(repositoryId).delete(id, rev);
                                    deletedCount++;
                                } catch (Exception e) {
                                    log.warn("Failed to delete expired RSS token: " + id);
                                }
                            }
                        }
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("Deleted " + deletedCount + " expired RSS tokens in repository " + repositoryId);
            }
        } catch (Exception e) {
            log.error("Failed to delete expired RSS tokens: " + e.getMessage(), e);
        }

        return deletedCount;
    }

    /**
     * Extract document map from Cloudant response.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDocMap(Object docObj) {
        if (docObj instanceof Map) {
            return (Map<String, Object>) docObj;
        } else if (docObj instanceof com.ibm.cloud.cloudant.v1.model.Document) {
            com.ibm.cloud.cloudant.v1.model.Document doc = (com.ibm.cloud.cloudant.v1.model.Document) docObj;
            Map<String, Object> docMap = new HashMap<>(doc.getProperties());
            if (doc.getId() != null) {
                docMap.put("_id", doc.getId());
            }
            if (doc.getRev() != null) {
                docMap.put("_rev", doc.getRev());
            }
            return docMap;
        }
        return null;
    }
}
