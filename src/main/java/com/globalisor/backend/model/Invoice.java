package com.globalisor.backend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@Document(collection = "invoices")
public class Invoice {
    @Id
    private String id;
    @Indexed
    private String clientId;
    private String amount;
    @Indexed
    private String status;
    private String date;
    private String dueDate;
    private String description;
}
