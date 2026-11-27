package com.globalisor.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "email_logs")
public class EmailLog {
    @Id
    private String id;

    @Indexed
    private String recipient;

    private String clientName;

    private String subject;

    @Indexed
    private String emailType;

    @Indexed
    private String applicationReferenceNo;

    private String htmlContent;

    private String textContent;

    private String status; // "SENT", "DELIVERED", "LOGGED", "FAILED"

    private String deliveryMethod; // "SMTP" or "CONSOLE_LOGGER"

    private String errorMessage;

    @Indexed
    private Date sentAt = new Date();

    private Map<String, Object> metadata;
}
