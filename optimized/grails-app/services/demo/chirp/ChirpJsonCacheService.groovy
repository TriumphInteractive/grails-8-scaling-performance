package demo.chirp

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ChirpJsonCacheService {
    static transactional = false

    ChirpReadService chirpReadService

    @Value('${chirp.cache.enabled:true}')
    boolean enabled = true

    private final Executor loadExecutor = Executors.newVirtualThreadPerTaskExecutor()

    final AsyncLoadingCache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofSeconds(20))
            .refreshAfterWrite(Duration.ofSeconds(10))
            .executor(loadExecutor)
            .buildAsync({ String key, Executor executor ->
                CompletableFuture.supplyAsync({ read(key) }, loadExecutor)
            })

    String timeline(String handle, int max) {
        enabled ? join("timeline:${handle}:${max}".toString()) : chirpReadService.timeline(handle, max)
    }

    String account(String handle) {
        enabled ? join("account:${handle}".toString()) : chirpReadService.account(handle)
    }

    private String join(String key) {
        try {
            return cache.get(key).join()
        } catch (CompletionException ex) {
            Throwable cause = ex.cause
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause
            }
            if (cause instanceof Error) {
                throw (Error) cause
            }
            throw ex
        }
    }

    private String read(String key) {
        if (key.startsWith('account:')) {
            return chirpReadService.account(key.substring('account:'.length()))
        }
        int separator = key.lastIndexOf(':')
        chirpReadService.timeline(key.substring('timeline:'.length(), separator),
                Integer.parseInt(key.substring(separator + 1)))
    }
}
