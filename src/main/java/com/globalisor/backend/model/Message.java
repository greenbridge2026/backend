package com.globalisor.backend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@Document(collection = "messages")
@CompoundIndexes({
    @CompoundIndex(name = "client_timestamp_idx", def = "{'clientId': 1, 'timestamp': -1}")
})
public class Message {
    @Id
    private String id;
    @Indexed
    private String clientId;
    @Indexed
    private String senderId;
    private String senderName;
    private String senderRole;
    private String text;
    @Indexed
    private Long timestamp = System.currentTimeMillis();
    private Boolean isRead = false;
}
