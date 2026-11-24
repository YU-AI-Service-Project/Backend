package dev.wattup.backend.auth;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
class AuthMail {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AuthMail.class);
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Executors.defaultThreadFactory());

    AuthMail(ObjectProvider<JavaMailSender> senders, @Value("${wattup.auth.mail-from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    void requireConfigured() {
        if (from.isBlank() || senders.getIfAvailable() == null) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
    }

    void verification(String email, String code) {
        send(email, "WattUp 이메일 인증", "인증 코드 " + code + " · 10분 이내에 입력해 주세요.");
    }

    void reset(String email, String token) {
        send(email, "WattUp 비밀번호 재설정", "앱에 재설정 토큰을 입력해 주세요. 15분 후 만료됩니다.\n\n" + token);
    }

    private void send(String email, String subject, String body) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            delivery.execute(() -> {
                try {
                    senders.getObject().send(message);
                } catch (MailException e) {
                    LOG.warn("Authentication mail delivery failed");
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("Authentication mail queue is full");
        }
    }

    @PreDestroy
    void stop() {
        delivery.shutdownNow();
    }
}
