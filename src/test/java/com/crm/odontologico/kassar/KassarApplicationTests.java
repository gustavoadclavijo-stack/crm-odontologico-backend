package com.crm.odontologico.kassar;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-api-key")
class KassarApplicationTests {

	@Autowired
	private ChatModel chatModel;

	@Test
	void contextLoads() {
		assertNotNull(chatModel);
	}

}
