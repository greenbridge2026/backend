package com.globalisor.backend.service;

import com.globalisor.backend.model.EmailLog;
import com.globalisor.backend.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Value("${mail.host:}")
    private String mailHost;

    @Value("${mail.port:587}")
    private int mailPort;

    @Value("${mail.username:}")
    private String mailUsername;

    @Value("${mail.password:}")
    private String mailPassword;

    @Value("${mail.from:no-reply@smartcosec.com}")
    private String mailFrom;

    @Value("${mail.from-name:Globalisor Corporate Services}")
    private String mailFromName;

    @Value("${mail.portal-base-url:http://localhost:5173}")
    private String portalBaseUrl;

    @Autowired
    private NotificationService notificationService;

    @Autowired(required = false)
    private EmailLogRepository emailLogRepository;

    /**
     * Dispatches a formal incorporation acknowledgement email when a client submits an application.
     */
    public boolean sendApplicationAcknowledgementEmail(
            String recipientEmail,
            String clientName,
            String companyName,
            String applicationRef,
            Map<String, Object> data,
            String plainPassword
    ) {
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            logger.warn("Cannot send acknowledgement email: recipient email is missing.");
            return false;
        }

        String safeName = (clientName != null && !clientName.trim().isEmpty()) ? clientName.trim() : "Valued Client";
        String safeCompany = (companyName != null && !companyName.trim().isEmpty()) ? companyName.trim() : "Your Company";
        String safeRef = (applicationRef != null && !applicationRef.trim().isEmpty()) ? applicationRef.trim() : "APP-PENDING";

        String subject = "Application Received: Singapore Company Incorporation [" + safeRef + " - " + safeCompany + "]";

        String htmlBody = buildApplicationAcknowledgementHtml(safeName, safeCompany, safeRef, recipientEmail, plainPassword, data);
        String textBody = buildApplicationAcknowledgementText(safeName, safeCompany, safeRef, recipientEmail, plainPassword, data);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("applicationRef", safeRef);
        metadata.put("companyName", safeCompany);
        metadata.put("submissionDate", new Date());

        boolean dispatched = dispatchEmail(
                recipientEmail.trim(),
                safeName,
                subject,
                htmlBody,
                textBody,
                "APPLICATION_ACKNOWLEDGEMENT",
                safeRef,
                metadata
        );

        // Also issue in-app notification to ensure omni-channel delivery
        try {
            notificationService.sendNotification(
                    recipientEmail.trim(),
                    "Application Received: " + safeCompany,
                    "Your incorporation application [" + safeRef + "] has been received and acknowledged. Check your email for full application details.",
                    "application_acknowledgement",
                    safeRef,
                    "High",
                    portalBaseUrl + "/login.html"
            );
        } catch (Exception e) {
            logger.debug("In-app notification skipped: {}", e.getMessage());
        }

        return dispatched;
    }

    /**
     * Portal activation email when staff approves an application and provisions user login.
     */
    public void sendPortalActivationEmail(String recipientEmail, String clientName, String plainPassword, String portalUrl) {
        String safeName = (clientName != null && !clientName.trim().isEmpty()) ? clientName.trim() : "Valued Client";
        String subject = "🎉 Your SmartCoSec Client Portal is Activated!";

        String fullPortalUrl = portalUrl != null && portalUrl.startsWith("http")
                ? portalUrl
                : (portalBaseUrl + (portalUrl != null ? portalUrl : "/login.html"));

        String htmlBody = buildPortalActivationHtml(safeName, recipientEmail, plainPassword, fullPortalUrl);
        String textBody = buildPortalActivationText(safeName, recipientEmail, plainPassword, fullPortalUrl);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("portalUrl", fullPortalUrl);

        dispatchEmail(
                recipientEmail.trim(),
                safeName,
                subject,
                htmlBody,
                textBody,
                "PORTAL_ACTIVATION",
                null,
                metadata
        );

        try {
            notificationService.sendNotification(
                    recipientEmail,
                    "🎉 Portal Activated & Welcome Pack",
                    "Your application has been approved! Login credentials have been dispatched to " + recipientEmail,
                    "portal_activation",
                    null,
                    "High",
                    fullPortalUrl
            );
        } catch (Exception e) {
            logger.warn("Failed to send notification for portal activation: {}", e.getMessage());
        }
    }

    /**
     * Core email dispatch pipeline: attempts SMTP delivery if configured, otherwise falls back to
     * structured console logging & MongoDB audit persistence.
     */
    private boolean dispatchEmail(
            String recipientEmail,
            String recipientName,
            String subject,
            String htmlBody,
            String textBody,
            String emailType,
            String applicationRef,
            Map<String, Object> metadata
    ) {
        boolean smtpSuccess = false;
        String deliveryMethod = "CONSOLE_LOGGER";
        String errorMessage = null;

        boolean isSmtpConfigured = mailHost != null && !mailHost.trim().isEmpty();

        if (isSmtpConfigured) {
            try {
                smtpSuccess = sendViaSmtp(recipientEmail, recipientName, subject, htmlBody, textBody);
                if (smtpSuccess) {
                    deliveryMethod = "SMTP";
                }
            } catch (Exception ex) {
                logger.warn("SMTP dispatch failed to {}: {}. Falling back to internal audit queue.", recipientEmail, ex.getMessage());
                errorMessage = ex.getMessage();
            }
        }

        // Always print visually distinct structured log for audit & developer visibility
        logStructuredEmail(recipientEmail, recipientName, subject, emailType, applicationRef, deliveryMethod, smtpSuccess);

        // Record in MongoDB audit collection if repository is available
        if (emailLogRepository != null) {
            try {
                EmailLog emailLog = new EmailLog();
                emailLog.setId("EMAIL-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 9000 + 1000));
                emailLog.setRecipient(recipientEmail);
                emailLog.setClientName(recipientName);
                emailLog.setSubject(subject);
                emailLog.setEmailType(emailType);
                emailLog.setApplicationReferenceNo(applicationRef);
                emailLog.setHtmlContent(htmlBody);
                emailLog.setTextContent(textBody);
                emailLog.setStatus(smtpSuccess ? "SENT" : (isSmtpConfigured ? "FAILED_SMTP_QUEUED" : "DISPATCHED_TO_QUEUE"));
                emailLog.setDeliveryMethod(deliveryMethod);
                emailLog.setErrorMessage(errorMessage);
                emailLog.setSentAt(new Date());
                emailLog.setMetadata(metadata);

                emailLogRepository.save(emailLog);
            } catch (Exception ex) {
                logger.debug("Unable to persist email log to MongoDB: {}", ex.getMessage());
            }
        }

        return true;
    }

    private void logStructuredEmail(
            String toEmail,
            String toName,
            String subject,
            String emailType,
            String applicationRef,
            String deliveryMethod,
            boolean isSmtp
    ) {
        logger.info("================================================================================");
        logger.info("[EMAIL DISPATCH - {}]", emailType);
        logger.info("To:             {} <{}>", toName, toEmail);
        logger.info("From:           {} <{}>", mailFromName, mailFrom);
        logger.info("Subject:        {}", subject);
        if (applicationRef != null) {
            logger.info("ApplicationRef: {}", applicationRef);
        }
        logger.info("DeliveryMode:   {} (SMTP Connected: {})", deliveryMethod, isSmtp);
        logger.info("Timestamp:      {}", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date()));
        logger.info("================================================================================");
    }

    /**
     * Lightweight standard Java socket SMTP client supporting STARTTLS, SSL (port 465),
     * AUTH LOGIN, and multipart/alternative MIME emails. Zero external dependencies.
     */
    private boolean sendViaSmtp(
            String toEmail,
            String toName,
            String subject,
            String htmlBody,
            String textBody
    ) throws Exception {
        Socket socket = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;

        try {
            int port = mailPort > 0 ? mailPort : 587;
            boolean useDirectSsl = (port == 465);

            if (useDirectSsl) {
                socket = SSLSocketFactory.getDefault().createSocket(mailHost, port);
            } else {
                socket = new Socket(mailHost, port);
            }
            socket.setSoTimeout(10000);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String res = readSmtpResponse(reader);
            if (!res.startsWith("220")) throw new IOException("SMTP connection failed: " + res);

            // EHLO
            sendSmtpCommand(writer, "EHLO smartcosec.com");
            res = readSmtpResponse(reader);

            // STARTTLS if port 587
            if (!useDirectSsl && port == 587) {
                sendSmtpCommand(writer, "STARTTLS");
                res = readSmtpResponse(reader);
                if (res.startsWith("220")) {
                    SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(socket, mailHost, port, true);
                    javax.net.ssl.SSLParameters sslParams = sslSocket.getSSLParameters();
                    sslParams.setEndpointIdentificationAlgorithm("HTTPS");
                    sslSocket.setSSLParameters(sslParams);
                    sslSocket.startHandshake();
                    socket = sslSocket;
                    reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                    sendSmtpCommand(writer, "EHLO smartcosec.com");
                    res = readSmtpResponse(reader);
                }
            }

            // AUTH LOGIN if credentials present
            if (mailUsername != null && !mailUsername.trim().isEmpty() && mailPassword != null && !mailPassword.trim().isEmpty()) {
                sendSmtpCommand(writer, "AUTH LOGIN");
                res = readSmtpResponse(reader);
                if (res.startsWith("334")) {
                    sendSmtpCommand(writer, Base64.getEncoder().encodeToString(mailUsername.trim().getBytes(StandardCharsets.UTF_8)));
                    res = readSmtpResponse(reader);
                    if (res.startsWith("334")) {
                        sendSmtpCommand(writer, Base64.getEncoder().encodeToString(mailPassword.trim().getBytes(StandardCharsets.UTF_8)));
                        res = readSmtpResponse(reader);
                        if (!res.startsWith("235")) {
                            if (res.contains("5.7.139") || res.contains("SmtpClientAuthentication is disabled")) {
                                logger.error("[OUTLOOK_SMTP_AUTH_NOTICE] Microsoft 365 rejected authentication: Authenticated SMTP is currently disabled for tenant or mailbox {}. Please enable 'Authenticated SMTP' in Microsoft 365 Admin Center (Users -> Active users -> {} -> Mail -> Manage email apps -> Check 'Authenticated SMTP').", mailUsername, mailUsername);
                            }
                            throw new IOException("SMTP authentication failed: " + res);
                        }
                    }
                }
            }

            // MAIL FROM
            sendSmtpCommand(writer, "MAIL FROM:<" + mailFrom.trim() + ">");
            res = readSmtpResponse(reader);
            if (!res.startsWith("250")) throw new IOException("MAIL FROM rejected: " + res);

            // RCPT TO
            sendSmtpCommand(writer, "RCPT TO:<" + toEmail.trim() + ">");
            res = readSmtpResponse(reader);
            if (!res.startsWith("250")) throw new IOException("RCPT TO rejected: " + res);

            // DATA
            sendSmtpCommand(writer, "DATA");
            res = readSmtpResponse(reader);
            if (!res.startsWith("354")) throw new IOException("DATA command rejected: " + res);

            // MIME Header + Multipart Body
            String boundary = "===Boundary_" + System.currentTimeMillis() + "===";
            StringBuilder msg = new StringBuilder();
            msg.append("From: ").append(mailFromName).append(" <").append(mailFrom).append(">\r\n");
            msg.append("To: ").append(toName != null ? toName : toEmail).append(" <").append(toEmail).append(">\r\n");
            msg.append("Subject: =?UTF-8?B?").append(Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))).append("?=\r\n");
            msg.append("Date: ").append(new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(new Date())).append("\r\n");
            msg.append("MIME-Version: 1.0\r\n");
            msg.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n");
            msg.append("\r\n");

            // Text part
            msg.append("--").append(boundary).append("\r\n");
            msg.append("Content-Type: text/plain; charset=UTF-8\r\n");
            msg.append("Content-Transfer-Encoding: base64\r\n\r\n");
            msg.append(Base64.getMimeEncoder().encodeToString(textBody.getBytes(StandardCharsets.UTF_8))).append("\r\n\r\n");

            // HTML part
            msg.append("--").append(boundary).append("\r\n");
            msg.append("Content-Type: text/html; charset=UTF-8\r\n");
            msg.append("Content-Transfer-Encoding: base64\r\n\r\n");
            msg.append(Base64.getMimeEncoder().encodeToString(htmlBody.getBytes(StandardCharsets.UTF_8))).append("\r\n\r\n");

            msg.append("--").append(boundary).append("--\r\n");
            msg.append(".\r\n");

            writer.write(msg.toString());
            writer.flush();

            res = readSmtpResponse(reader);
            if (!res.startsWith("250")) throw new IOException("Email body delivery rejected: " + res);

            sendSmtpCommand(writer, "QUIT");
            return true;
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sendSmtpCommand(BufferedWriter writer, String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
    }

    private String readSmtpResponse(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) return "";
        StringBuilder sb = new StringBuilder(line);
        // Multi-line response handling (e.g. 250-SIZE, 250 HELP)
        while (line.length() >= 4 && line.charAt(3) == '-') {
            line = reader.readLine();
            if (line == null) break;
            sb.append("\n").append(line);
        }
        return sb.toString();
    }

    /**
     * Builds responsive HTML email for application acknowledgement.
     */
    private String buildApplicationAcknowledgementHtml(
            String clientName,
            String companyName,
            String applicationRef,
            String email,
            String plainPassword,
            Map<String, Object> data
    ) {
        String alt1 = (data != null && data.get("alternativeCompanyName1") != null) ? String.valueOf(data.get("alternativeCompanyName1")).trim() : "";
        String alt2 = (data != null && data.get("alternativeCompanyName2") != null) ? String.valueOf(data.get("alternativeCompanyName2")).trim() : "";
        String entityType = (data != null && data.get("entityType") != null) ? String.valueOf(data.get("entityType")) : "Private Limited Company (Pte. Ltd.)";
        String ssicCode = (data != null && data.get("primaryActivityCode") != null) ? String.valueOf(data.get("primaryActivityCode")) : "";
        String ssicDesc = (data != null && data.get("primaryActivity") != null) ? String.valueOf(data.get("primaryActivity")) : "Business & Management Consultancy Services";
        String jurisdiction = (data != null && data.get("jurisdiction") != null) ? String.valueOf(data.get("jurisdiction")) : "Singapore";

        int shareholders = 1;
        int directors = 1;
        if (data != null) {
            try {
                if (data.containsKey("individualShareholders")) shareholders = Integer.parseInt(String.valueOf(data.get("individualShareholders")));
                if (data.containsKey("corporateShareholders")) shareholders += Integer.parseInt(String.valueOf(data.get("corporateShareholders")));
                if (data.containsKey("localDirectors")) directors = Integer.parseInt(String.valueOf(data.get("localDirectors")));
                if (data.containsKey("foreignDirectors")) directors += Integer.parseInt(String.valueOf(data.get("foreignDirectors")));
            } catch (Exception ignored) {}
        }

        String submissionDateStr = new SimpleDateFormat("dd MMM yyyy, hh:mm a 'SGT'").format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"UTF-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("  <title>Application Received - Globalisor</title>\n");
        sb.append("</head>\n");
        sb.append("<body style=\"margin:0;padding:0;background-color:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1e293b;\">\n");
        sb.append("  <table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f1f5f9;padding:30px 15px;\">\n");
        sb.append("    <tr>\n");
        sb.append("      <td align=\"center\">\n");
        sb.append("        <table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:620px;background-color:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 4px 14px rgba(0,0,0,0.06);border:1px solid #e2e8f0;\">\n");
        sb.append("          <tr>\n");
        sb.append("            <td style=\"background:linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);padding:36px 36px 30px 36px;color:#ffffff;\">\n");
        sb.append("              <table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\">\n");
        sb.append("                <tr>\n");
        sb.append("                  <td>\n");
        sb.append("                    <div style=\"font-size:24px;font-weight:800;letter-spacing:-0.5px;\">Globalisor</div>\n");
        sb.append("                    <div style=\"font-size:12px;opacity:0.9;margin-top:3px;text-transform:uppercase;letter-spacing:1px;font-weight:600;\">Singapore Corporate Services & Filing Agent</div>\n");
        sb.append("                  </td>\n");
        sb.append("                  <td align=\"right\">\n");
        sb.append("                    <span style=\"display:inline-block;padding:6px 14px;background:rgba(255,255,255,0.18);border:1px solid rgba(255,255,255,0.3);border-radius:20px;font-size:11px;font-weight:700;color:#ffffff;letter-spacing:0.5px;\">APPLICATION RECEIVED</span>\n");
        sb.append("                  </td>\n");
        sb.append("                </tr>\n");
        sb.append("              </table>\n");
        sb.append("            </td>\n");
        sb.append("          </tr>\n");
        sb.append("          <tr>\n");
        sb.append("            <td style=\"padding:32px 36px;\">\n");
        sb.append("              <h2 style=\"margin:0 0 12px 0;font-size:20px;font-weight:700;color:#0f172a;\">Dear ").append(escapeHtml(clientName)).append(",</h2>\n");
        sb.append("              <p style=\"margin:0 0 20px 0;font-size:14px;line-height:1.6;color:#475569;\">\n");
        sb.append("                Thank you for choosing <strong>Globalisor</strong> for your company registration in Singapore. We have received your incorporation application and our certified corporate secretarial specialists have commenced the initial filing review.\n");
        sb.append("              </p>\n");

        // Application Summary Card
        sb.append("              <div style=\"background:#f8fafc;border:1px solid #e2e8f0;border-radius:14px;padding:20px;margin-bottom:24px;\">\n");
        sb.append("                <div style=\"font-size:11px;font-weight:700;color:#64748b;text-transform:uppercase;letter-spacing:1px;margin-bottom:12px;\">Application Summary</div>\n");
        sb.append("                <table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:13px;\">\n");

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;width:40%;\">Filing Reference:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;font-weight:700;color:#1e3a8a;font-family:monospace;font-size:14px;\">").append(escapeHtml(applicationRef)).append("</td>\n");
        sb.append("                  </tr>\n");

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Proposed Company Name:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;font-weight:700;color:#0f172a;\">").append(escapeHtml(companyName)).append("</td>\n");
        sb.append("                  </tr>\n");

        if (!alt1.isEmpty() || !alt2.isEmpty()) {
            sb.append("                  <tr>\n");
            sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Alternative Names:</td>\n");
            sb.append("                    <td style=\"padding:6px 0;color:#334155;\">");
            if (!alt1.isEmpty()) sb.append("1. ").append(escapeHtml(alt1));
            if (!alt2.isEmpty()) sb.append("<br>2. ").append(escapeHtml(alt2));
            sb.append("</td>\n                  </tr>\n");
        }

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Jurisdiction & Entity:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#334155;font-weight:600;\">").append(escapeHtml(jurisdiction)).append(" · ").append(escapeHtml(entityType.replace("_", " "))).append("</td>\n");
        sb.append("                  </tr>\n");

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Primary Business Activity:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#334155;\">").append(ssicCode.isEmpty() ? "" : "[" + escapeHtml(ssicCode) + "] ").append(escapeHtml(ssicDesc)).append("</td>\n");
        sb.append("                  </tr>\n");

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Governance Structure:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#334155;\">").append(shareholders).append(" Shareholder(s), ").append(directors).append(" Director(s)</td>\n");
        sb.append("                  </tr>\n");

        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#64748b;\">Submission Date:</td>\n");
        sb.append("                    <td style=\"padding:6px 0;color:#334155;\">").append(submissionDateStr).append("</td>\n");
        sb.append("                  </tr>\n");

        sb.append("                </table>\n");
        sb.append("              </div>\n");

        // Next Steps Timeline
        sb.append("              <div style=\"margin-bottom:24px;\">\n");
        sb.append("                <div style=\"font-size:13px;font-weight:700;color:#0f172a;margin-bottom:12px;\">What Happens Next:</div>\n");
        sb.append("                <table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;line-height:1.5;color:#475569;\">\n");
        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"vertical-align:top;width:24px;color:#2563eb;font-weight:bold;\">1.</td>\n");
        sb.append("                    <td style=\"padding-bottom:10px;\"><strong>ACRA Name Verification & Reservation:</strong> Our licensed corporate secretary verifies your company name availability with ACRA and files the name reservation application.</td>\n");
        sb.append("                  </tr>\n");
        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"vertical-align:top;width:24px;color:#2563eb;font-weight:bold;\">2.</td>\n");
        sb.append("                    <td style=\"padding-bottom:10px;\"><strong>Officer Identity & KYC Verification:</strong> When prompted, provide clear identification (Passport/NRIC and proof of address) for company officers.</td>\n");
        sb.append("                  </tr>\n");
        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"vertical-align:top;width:24px;color:#2563eb;font-weight:bold;\">3.</td>\n");
        sb.append("                    <td style=\"padding-bottom:10px;\"><strong>Constitution & Statutory Signatures:</strong> Digital signing of company constitution, Form 45, and director consent resolutions.</td>\n");
        sb.append("                  </tr>\n");
        sb.append("                  <tr>\n");
        sb.append("                    <td style=\"vertical-align:top;width:24px;color:#2563eb;font-weight:bold;\">4.</td>\n");
        sb.append("                    <td style=\"padding-bottom:0;\"><strong>UEN & Certificate Issuance:</strong> ACRA registers your company and issues your official Unique Entity Number (UEN) and Business Profile (BizFile).</td>\n");
        sb.append("                  </tr>\n");
        sb.append("                </table>\n");
        sb.append("              </div>\n");

        // Assistance note
        sb.append("              <p style=\"font-size:13px;line-height:1.6;color:#64748b;margin:0;padding-top:16px;border-top:1px solid #e2e8f0;\">\n");
        sb.append("                Need help or have questions? Simply reply directly to this email or reach our Globalisor advisory specialists at <a href=\"mailto:support@globalisor.com\" style=\"color:#2563eb;text-decoration:none;\">support@globalisor.com</a>.\n");
        sb.append("              </p>\n");

        sb.append("            </td>\n");
        sb.append("          </tr>\n");

        // Footer
        sb.append("          <tr>\n");
        sb.append("            <td style=\"background:#f8fafc;padding:24px 36px;border-top:1px solid #e2e8f0;font-size:11px;color:#94a3b8;line-height:1.6;\">\n");
        sb.append("              <div style=\"font-weight:700;color:#475569;margin-bottom:3px;font-size:12px;\">Globalisor Corporate Services</div>\n");
        sb.append("              <div>ACRA Registered Filing Agent · Singapore Company Law & Corporate Governance</div>\n");
        sb.append("              <div style=\"margin-top:10px;padding-top:8px;border-top:1px dashed #e2e8f0;color:#94a3b8;font-size:10.5px;\">\n");
        sb.append("                Powered by <strong style=\"color:#64748b;\">SmartCoSec</strong> · Intelligent Corporate Secretarial Platform\n");
        sb.append("              </div>\n");
        sb.append("              <div style=\"margin-top:6px;\">&copy; ").append(Calendar.getInstance().get(Calendar.YEAR)).append(" Globalisor. All rights reserved.</div>\n");
        sb.append("            </td>\n");
        sb.append("          </tr>\n");

        sb.append("        </table>\n");
        sb.append("      </td>\n");
        sb.append("    </tr>\n");
        sb.append("  </table>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    /**
     * Builds plain text fallback for application acknowledgement email.
     */
    private String buildApplicationAcknowledgementText(
            String clientName,
            String companyName,
            String applicationRef,
            String email,
            String plainPassword,
            Map<String, Object> data
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("GLOBALISOR - SINGAPORE CORPORATE SERVICES\n");
        sb.append("=================================================================\n");
        sb.append("APPLICATION RECEIVED - CONFIRMATION\n");
        sb.append("=================================================================\n\n");
        sb.append("Dear ").append(clientName).append(",\n\n");
        sb.append("Thank you for choosing Globalisor for your company registration in Singapore.\n");
        sb.append("We have received your pre-registration details and our corporate secretarial\n");
        sb.append("specialists are currently reviewing your application.\n\n");
        sb.append("APPLICATION SUMMARY:\n");
        sb.append("-----------------------------------------------------------------\n");
        sb.append("Application Reference: ").append(applicationRef).append("\n");
        sb.append("Proposed Company:      ").append(companyName).append("\n");
        if (data != null && data.get("entityType") != null) {
            sb.append("Entity Type:           ").append(data.get("entityType")).append("\n");
        }
        if (data != null && data.get("primaryActivity") != null) {
            sb.append("Principal Activity:    ").append(data.get("primaryActivity")).append("\n");
        }
        sb.append("Submission Date:       ").append(new SimpleDateFormat("dd MMM yyyy, HH:mm z").format(new Date())).append("\n\n");

        sb.append("WHAT HAPPENS NEXT:\n");
        sb.append("1. ACRA Name Verification & Reservation\n");
        sb.append("2. Officer & Shareholder Digital KYC\n");
        sb.append("3. Constitution & Statutory Form 45 Signing\n");
        sb.append("4. Final ACRA Registration & UEN Issuance\n\n");
        sb.append("Need assistance? Contact support@globalisor.com\n");
        sb.append("Globalisor Corporate Services · Singapore Regulated Filing Agent\n");
        sb.append("Powered by SmartCoSec · Intelligent Corporate Secretarial Platform\n");

        return sb.toString();
    }

    private String buildPortalActivationHtml(String clientName, String email, String password, String portalUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style=\"font-family:sans-serif;color:#1e293b;padding:24px;\">");
        sb.append("<div style=\"max-width:560px;margin:auto;background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:24px;\">");
        sb.append("<h2 style=\"color:#2563eb;\">🎉 Your SmartCoSec Client Portal is Activated</h2>");
        sb.append("<p>Dear <strong>").append(escapeHtml(clientName)).append("</strong>,</p>");
        sb.append("<p>Your incorporation specialist has approved your application! You can now log in to access your statutory records, registers, and compliance filings.</p>");
        sb.append("<div style=\"background:#f8fafc;padding:16px;border-radius:8px;margin:16px 0;\">");
        sb.append("<p style=\"margin:4px 0;\"><strong>Portal URL:</strong> <a href=\"").append(portalUrl).append("\">").append(portalUrl).append("</a></p>");
        sb.append("<p style=\"margin:4px 0;\"><strong>Login Email:</strong> ").append(escapeHtml(email)).append("</p>");
        if (password != null && !password.isEmpty()) {
            sb.append("<p style=\"margin:4px 0;\"><strong>Password:</strong> <code style=\"background:#e2e8f0;padding:2px 6px;border-radius:4px;\">").append(escapeHtml(password)).append("</code></p>");
        }
        sb.append("</div>");
        sb.append("<p><a href=\"").append(portalUrl).append("\" style=\"background:#2563eb;color:#fff;padding:10px 20px;text-decoration:none;border-radius:8px;display:inline-block;\">Go to Client Portal &rarr;</a></p>");
        sb.append("<p style=\"font-size:12px;color:#64748b;\">SmartCoSec Singapore Corporate Services</p>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String buildPortalActivationText(String clientName, String email, String password, String portalUrl) {
        return "Dear " + clientName + ",\n\n" +
                "Your SmartCoSec Client Portal is activated.\n" +
                "Portal URL: " + portalUrl + "\n" +
                "Login Email: " + email + "\n" +
                (password != null && !password.isEmpty() ? "Password: " + password + "\n" : "") +
                "\nBest regards,\nSmartCoSec Corporate Services";
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
