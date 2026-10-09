package demo.chirp

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import spock.lang.Specification

class LoadtestConfigurationSpec extends Specification {
    void 'loadtest profile applies to the loadtest Grails environment and does not set a statement timeout'() {
        given:
        def config = new YamlPropertySourceLoader().load('loadtest',
                new FileSystemResource('grails-app/conf/application-loadtest.yml'))[0]

        expect:
        config.getProperty('server.port') == 8081
        config.getProperty('spring.config.activate.on-profile') == 'loadtest'
        config.getProperty('dataSource.type') == 'demo.chirp.ChirpHikariDataSource'
        config.getProperty('dataSource.url') == '${CHIRP_JDBC_URL:jdbc:postgresql://localhost:5438/chirp_optimized?tcpKeepAlive=true}'
        !config.getProperty('dataSource.url').toString().contains('statement_timeout')
        config.getProperty('dataSource.properties.maximumPoolSize') == 16
        config.getProperty('dataSource.properties.minimumIdle') == 8
        config.getProperty('dataSource.properties.connectionTimeout') == 2000
        ['development', 'test', 'production', 'loadtest'].every { String environment ->
            config.getProperty("environments.${environment}.dataSource.dbCreate") == 'none' &&
                    config.getProperty("environments.${environment}.dataSource.type") == 'demo.chirp.ChirpHikariDataSource' &&
                    config.getProperty("environments.${environment}.dataSource.properties.maximumPoolSize") == 16
        }
        config.getProperty('hibernate.osiv.enabled') == false
        config.getProperty('hibernate.query.in_clause_parameter_padding') == true
        config.getProperty('hibernate.default_batch_fetch_size') == 16
        config.getProperty('hibernate.cache.use_second_level_cache') == false
        config.getProperty('hibernate.cache.use_query_cache') == false
    }

    void 'Hikari pool size is set in the constructor because YAML properties do not bind'() {
        given:
        String previous = System.getProperty('CHIRP_POOL_SIZE')
        System.setProperty('CHIRP_POOL_SIZE', '12')
        ChirpHikariDataSource dataSource = new ChirpHikariDataSource()

        expect:
        dataSource.maximumPoolSize == 12
        dataSource.minimumIdle == 8
        dataSource.connectionTimeout == 2000

        cleanup:
        dataSource.close()
        if (previous == null) {
            System.clearProperty('CHIRP_POOL_SIZE')
        } else {
            System.setProperty('CHIRP_POOL_SIZE', previous)
        }
    }
}
