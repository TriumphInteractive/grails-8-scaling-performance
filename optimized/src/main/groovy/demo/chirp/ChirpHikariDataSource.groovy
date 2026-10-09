package demo.chirp

import com.zaxxer.hikari.HikariDataSource

class ChirpHikariDataSource extends HikariDataSource {
    ChirpHikariDataSource() {
        int pool = integer('CHIRP_POOL_SIZE', 16)
        setMaximumPoolSize(pool)
        setMinimumIdle(Math.min(pool, integer('CHIRP_MIN_IDLE', 8)))
        setConnectionTimeout(integer('CHIRP_CONNECTION_TIMEOUT', 2000))
    }

    private static int integer(String key, int fallback) {
        String value = System.getProperty(key)
        if (value == null || value.isEmpty()) {
            value = System.getenv(key)
        }
        (value == null || value.isEmpty()) ? fallback : Integer.parseInt(value)
    }
}
