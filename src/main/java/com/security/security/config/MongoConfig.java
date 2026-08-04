package com.security.security.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class MongoConfig {

    @Bean
    public CommandLineRunner testMongoConnection(MongoTemplate mongoTemplate) {
        return args -> {
            try {
                System.out.println("=========================================");
                System.out.println("🚀 ĐANG KIỂM TRA KẾT NỐI MONGODB...");
                long count = mongoTemplate.getCollectionNames().size();
                System.out.println("✅ KẾT NỐI MONGODB THÀNH CÔNG!");
                System.out.println("📦 Số lượng collections hiện tại: " + count);
                System.out.println("=========================================");
            } catch (Exception e) {
                System.err.println("❌ KẾT NỐI MONGODB THẤT BẠI: " + e.getMessage());
            }
        };
    }
}
