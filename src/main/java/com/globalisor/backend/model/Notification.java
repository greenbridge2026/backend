package com.globalisor.backend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "notifications")
@CompoundIndexes({
    @CompoundIndex(name = "notif_client_time_idx", def = "{'clientId': 1, 'timestamp': -1}")
})
public class Notification {
    @Id
    private String id;
    @Indexed
    private String clientId;
    private String title;
    private String message;
    @Indexed
    private String type;
    private String relatedId;
    private String link;
    private String priority = "Info"; // Info, Warning, Critical
    @Indexed
    private Long timestamp = System.currentTimeMillis();
    private List<String> readBy = new ArrayList<>();
}
