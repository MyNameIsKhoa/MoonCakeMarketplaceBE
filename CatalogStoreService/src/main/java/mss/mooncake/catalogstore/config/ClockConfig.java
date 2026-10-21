package mss.mooncake.catalogstore.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Đồng hồ UTC cho rule phụ thuộc thời gian (promotion hiệu lực/hết hạn); test thay bằng Clock.fixed. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
