package mss.mooncake.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

@Service
public class OutboundEmailService {
    private static final Logger log = LoggerFactory.getLogger(OutboundEmailService.class);
    private final JavaMailSender mailSender;
    private final String from;

    public OutboundEmailService(JavaMailSender mailSender, @Value("${MAIL_FROM:${MAIL_USERNAME:}}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            log.warn("Outbound email delivery failed", ex);
        }
    }
}
