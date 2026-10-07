package mss.mooncake.rfqnegotiation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class RfqNegotiationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RfqNegotiationServiceApplication.class, args);
    }

}
