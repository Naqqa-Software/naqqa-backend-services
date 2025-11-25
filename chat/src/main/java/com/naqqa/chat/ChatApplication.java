package com.naqqa.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
		"com.naqqa.chat",
		"com.naqqa.auth"
})
@EntityScan(basePackages = {
		"com.naqqa.chat.entity",
		"com.naqqa.auth.entity"
})
@EnableJpaRepositories(basePackages = {
		"com.naqqa.chat.repository",
		"com.naqqa.auth.repository"
})
public class ChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatApplication.class, args);
	}

}
