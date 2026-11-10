package mss.mooncake.identity.service;

public interface OutboundEmailService {
    void send(String recipient, String subject, String body);
}
