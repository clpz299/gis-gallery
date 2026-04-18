package com.example.gisgallery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 *
 * @author clpz299
 */
@SpringBootApplication
@EnableScheduling
public class GisGalleryApplication {

	public static void main(String[] args) {
		SpringApplication.run(GisGalleryApplication.class, args);
	}

}
