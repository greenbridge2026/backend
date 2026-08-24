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
@Document(collection = "call_history")
@CompoundIndexes({
    @CompoundIndex(name = "caller_time_idx", def = "{'callerId': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "receiver_time_idx", def = "{'receiverId': 1, 'timestamp': -1}")
})
public class CallHistory {
    @Id
    private String id;
    @Indexed
    private String callerId;
    private String callerName;
    @Indexed
    private String receiverId;
    private String receiverName;
    private String mediaType; // voice, video
    @Indexed
    private Long timestamp = System.currentTimeMillis();
    private Long duration = 0L; // in seconds
    private String status; // completed, missed, declined
}
