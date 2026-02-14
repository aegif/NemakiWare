package jp.aegif.nemaki.rag.embedding;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.rag.config.RAGConfig;

/**
 * Routes embedding requests to the configured provider (TEI or Bedrock).
 */
@Service
@Primary
public class EmbeddingServiceRouter implements EmbeddingService {

    private static final Log log = LogFactory.getLog(EmbeddingServiceRouter.class);

    private final RAGConfig ragConfig;
    private final TeiEmbeddingService teiEmbeddingService;
    private final BedrockEmbeddingService bedrockEmbeddingService;

    @Autowired
    public EmbeddingServiceRouter(
            RAGConfig ragConfig,
            TeiEmbeddingService teiEmbeddingService,
            BedrockEmbeddingService bedrockEmbeddingService) {
        this.ragConfig = ragConfig;
        this.teiEmbeddingService = teiEmbeddingService;
        this.bedrockEmbeddingService = bedrockEmbeddingService;
    }

    @Override
    public float[] embed(String text, boolean isQuery) throws EmbeddingException {
        return selectProvider().embed(text, isQuery);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) throws EmbeddingException {
        return selectProvider().embedBatch(texts, isQuery);
    }

    @Override
    public boolean isHealthy() {
        return selectProvider().isHealthy();
    }

    @Override
    public int getVectorDimension() {
        return selectProvider().getVectorDimension();
    }

    private EmbeddingService selectProvider() {
        String provider = ragConfig.getEmbeddingProvider();
        if ("bedrock".equalsIgnoreCase(provider)) {
            return bedrockEmbeddingService;
        }
        if (!"tei".equalsIgnoreCase(provider)) {
            log.warn("Unknown rag.embedding.provider: " + provider + ", defaulting to TEI");
        }
        return teiEmbeddingService;
    }
}
