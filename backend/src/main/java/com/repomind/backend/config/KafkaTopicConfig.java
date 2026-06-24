package com.repomind.backend.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // Topic 1: API thread drops ingest requests here
    // 3 partitions = 3 workers can process in parallel simultaneously
    @Bean
    public NewTopic repoIngestRequestsTopic() {
        return TopicBuilder
                .name("repo-ingest-requests")
                .partitions(3)
                .replicas(1)
                // Keep messages for 7 days even after consumed
                // Useful for replaying messages if something goes wrong
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .build();
    }

    // Topic 2: Worker drops individual file nodes here for batch DB writing
    @Bean
    public NewTopic repoFileNodesTopic() {
        return TopicBuilder
                .name("repo-filenodes-raw")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(24 * 60 * 60 * 1000L))
                .build();
    }
}
