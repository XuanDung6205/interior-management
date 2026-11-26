package org.example.eshop.commerce.vector;

import org.example.eshop.commerce.common.CommerceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.SQLException;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorDisabledStartupTest {
    @Test void historicalSchemaAndDisabledStartupNeedNeitherDatabaseNorProvider() {
        var jdbc=mock(JdbcTemplate.class);
        var embeddings=mock(GeminiEmbeddingClient.class);
        var catalog=new VectorCatalog(jdbc);
        var settings=new VectorSettings(false,"",5,60,8);
        var runtime=new VectorRuntime(jdbc,catalog,Clock.systemUTC(),settings,embeddings,mock(PlatformTransactionManager.class));
        new VectorJob(runtime,settings,true).run();
        assertEquals("VECTOR_DISABLED",assertThrows(CommerceException.class,runtime::runBatch).getCode());
        verifyNoInteractions(jdbc,embeddings);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE catalog_product_id IS NOT NULL",Long.class))
                .thenThrow(new BadSqlGrammarException("legacy","safe fixture",new SQLException("legacy schema")));
        var status=runtime.snapshot();
        assertEquals(false,status.get("enabled")); assertEquals(0L,status.get("indexedProducts"));
        verifyNoInteractions(embeddings);
    }
}
