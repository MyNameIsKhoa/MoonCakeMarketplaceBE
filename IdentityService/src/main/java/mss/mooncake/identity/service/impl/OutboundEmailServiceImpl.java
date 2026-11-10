package mss.mooncake.identity.service.impl;

import mss.mooncake.identity.service.OutboundEmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutboundEmailServiceImpl implements OutboundEmailService {
    private static final Logger log = LoggerFactory.getLogger(OutboundEmailServiceImpl.class);
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public OutboundEmailServiceImpl(ObjectProvider<JavaMailSender> mailSender, @Value("${MAIL_FROM:${MAIL_USERNAME:}}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String recipient, String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Email delivery is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailException ex) {
            log.warn("Outbound email delivery failed", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is unavailable");
        }
    }
}
