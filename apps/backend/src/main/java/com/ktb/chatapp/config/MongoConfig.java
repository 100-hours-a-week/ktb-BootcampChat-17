package com.ktb.chatapp.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.concurrent.TimeUnit;
// 테스트 주석입니다

@Configuration
@EnableMongoAuditing
@Slf4j
public class MongoConfig extends AbstractMongoClientConfiguration {
    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;
    @Value("${spring.data.mongodb.database:bootcamp-chat}")
    private String databaseName;

    @Override
    protected String getDatabaseName() {
        return databaseName;
    }


    @Bean
    @Override
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(mongoUri);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)

                //커넥션풀 세팅
                .applyToConnectionPoolSettings(builder ->
                        builder
                                .maxSize(20)
                                .minSize(2)
                                .maxWaitTime(3000, TimeUnit.MILLISECONDS)
                                .maxConnectionLifeTime(60000, TimeUnit.MILLISECONDS)
                                .maxConnectionIdleTime(30000, TimeUnit.MILLISECONDS)
                                .maintenanceFrequency(10000, TimeUnit.MILLISECONDS)
                )


                .applyToSocketSettings(builder ->
                        builder
                                .connectTimeout(5000, TimeUnit.MILLISECONDS)  // 연결: 5초
                                .readTimeout(30000, TimeUnit.MILLISECONDS)      // 읽기: 30초 (AI 응답 대기)
                )

                // 클러스터 세팅
                .applyToClusterSettings(builder ->
                        builder
                                .serverSelectionTimeout(5000, TimeUnit.MILLISECONDS)
                )

                // 서버 모니터링
                .applyToServerSettings(builder ->
                        builder
                                .heartbeatFrequency(15000, TimeUnit.MILLISECONDS)
                                .minHeartbeatFrequency(1000, TimeUnit.MILLISECONDS)
                )


                .writeConcern(WriteConcern.W1)              // 빠른 쓰기
                .readPreference(ReadPreference.primaryPreferred())
                .retryWrites(true)
                .retryReads(true)

                .build();

        log.info("MongoDB Client initialized with connection pool [max: 20, min: 2]");
        return MongoClients.create(settings);
    }

    @Bean
    @Override
    public MappingMongoConverter mappingMongoConverter(
            MongoDatabaseFactory databaseFactory,
            MongoCustomConversions customConversions,
            MongoMappingContext mappingContext) {

        MappingMongoConverter converter = new MappingMongoConverter(
                new DefaultDbRefResolver(databaseFactory),
                mappingContext
        );
        converter.setCustomConversions(customConversions);
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));

        return converter;
    }

}