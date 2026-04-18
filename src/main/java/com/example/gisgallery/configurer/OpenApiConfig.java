package com.example.gisgallery.configurer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author clpz299
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gisGalleryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("GIS Gallery API")
                .description("GIS Gallery 后端接口文档")
                .version("v1")
                .contact(new Contact().name("GIS Gallery").email("dev@gis-gallery.local"))
                .license(new License().name("Apache 2.0")));
    }
}

