package org.example.eshop.commerce.catalog;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogCacheTest {
    public record Value(String name) {}
    @Test void redisFailureFallsBackToDatabaseWithoutSwallowingDatabaseFailures() {
        var redis=mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        var cache=new CatalogCache(redis,new ObjectMapper(),true,"unit-test");
        assertEquals("database",cache.read("key",Value.class,()->new Value("database")).name());
        assertThrows(IllegalArgumentException.class,()->cache.read("other",Value.class,()->{throw new IllegalArgumentException("DB error");}));
    }
    @Test void readsCachedDtoWithoutLoadingAndInvalidatesOnlyAfterCommit() {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String prefix="catalog:v1:"+java.util.UUID.nameUUIDFromBytes("unit-test".getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
        when(values.get(prefix+"generation")).thenReturn("1");
        when(values.get(argThat(k->k instanceof String text && text.startsWith(prefix+"1:")))).thenReturn("{\"name\":\"cached\"}");
        var cache=new CatalogCache(redis,new ObjectMapper(),true,"unit-test");
        assertEquals("cached",cache.read("key",Value.class,()->{throw new AssertionError("DB loader called on cache hit");}).name());
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(); verify(values,never()).set(eq(prefix+"generation"),anyString());
            for(var callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            verify(values).set(eq(prefix+"generation"),anyString());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void allowsOnlyAssetPathsOrHttpImages() {
        CatalogAdminService.imageUrl("/images/catalog/table.jpg");
        CatalogAdminService.imageUrl("https://example.com/table.jpg");
        for(String url:new String[]{"//evil.test/x","data:image/png,test","javascript:alert(1)","/images/../secret","https://user:pass@example.com/x"})
            assertThrows(RuntimeException.class,()->CatalogAdminService.imageUrl(url));
    }
}
