package com.globalisor.backend.repository;

import com.globalisor.backend.model.EmailLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailLogRepository extends MongoRepository<EmailLog, String> {
    List<EmailLog> findByRecipientOrderBySentAtDesc(String recipient);
    List<EmailLog> findByApplicationReferenceNoOrderBySentAtDesc(String applicationReferenceNo);
    List<EmailLog> findTop50ByOrderBySentAtDesc();
}
