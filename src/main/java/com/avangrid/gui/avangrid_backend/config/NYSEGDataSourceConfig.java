package com.avangrid.gui.avangrid_backend.config;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
@ConditionalOnProperty(
        prefix = "datasource.nyseg",
        name = "enabled",
        havingValue = "true"
)
@EnableJpaRepositories(
        basePackages = "com.avangrid.gui.avangrid_backend.infra.nyseg.repository",
        entityManagerFactoryRef = "nysegEntityManagerFactory",
        transactionManagerRef = "nysegTransactionManager"
)
public class NYSEGDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "datasource.nyseg")
    public DataSource nysegDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean nysegEntityManagerFactory(
            EntityManagerFactoryBuilder builder
    ) {
        return builder
                .dataSource(nysegDataSource())
                .packages("com.avangrid.gui.avangrid_backend.infra.nyseg.entity")
                .persistenceUnit("nysegPU")
                .build();
    }

    @Bean
    public PlatformTransactionManager nysegTransactionManager(
            @Qualifier("nysegEntityManagerFactory") EntityManagerFactory emf
    ) {
        return new JpaTransactionManager(emf);
    }
}

