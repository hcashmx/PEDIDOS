package com.cyssoftware.effyFlow;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class EffyFlowApplication {


	
	public static void main(String[] args) {
		SpringApplication.run(EffyFlowApplication.class, args);
	}
	
	@Value("${spring.datasource.url}")
	private String url;

	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	@PostConstruct
	public void checkEncoding() {
	    String enc = jdbcTemplate.queryForObject("SHOW client_encoding", String.class);
	    org.slf4j.LoggerFactory.getLogger(EffyFlowApplication.class).debug("CLIENT_ENCODING: {}", enc);
	}
	



}
