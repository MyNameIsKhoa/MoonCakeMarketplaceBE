package mss.mooncake.catalogstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class CatalogStoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogStoreServiceApplication.class, args);
    }

}
