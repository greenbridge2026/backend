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
@Document(collection = "starred_messages")
@CompoundIndexes({
    @CompoundIndex(name = "user_msg_idx", def = "{'userId': 1, 'messageId': 1}")
})
public class StarredMessage {
    @Id
    private String id;
    @Indexed
    private String userId;
    @Indexed
    private String messageId;
    private Long timestamp = System.currentTimeMillis();

    public StarredMessage(String userId, String messageId) {
        this.userId = userId;
        this.messageId = messageId;
    }
}
