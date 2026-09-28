package com.property.mgmt;

import com.property.mgmt.config.WxProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.property.mgmt.mapper")
@EnableConfigurationProperties(WxProperties.class)
public class PropertyMgmtApplication {
    public static void main(String[] args) {
        SpringApplication.run(PropertyMgmtApplication.class, args);
    }
}
