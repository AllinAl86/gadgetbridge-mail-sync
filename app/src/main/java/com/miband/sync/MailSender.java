package com.miband.sync;

import java.io.File;
import java.util.Properties;
import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public final class MailSender {

    private MailSender() {
    }

    /** 成功返回 null，失败返回原因。 */
    public static String send(String fromEmail, String password, String toEmail, File attachment) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", "*");
            props.put("mail.smtp.connectiontimeout", "20000");
            props.put("mail.smtp.timeout", "60000");
            props.put("mail.smtp.writetimeout", "120000");
            props.put("mail.smtp.socketFactory.fallback", "false");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

            String host;
            if (fromEmail.endsWith("@163.com") || fromEmail.endsWith("@126.com")) {
                host = "smtp.163.com";
            } else if (fromEmail.endsWith("@qq.com") || fromEmail.endsWith("@foxmail.com")) {
                host = "smtp.qq.com";
            } else {
                return "只支持 QQ、Foxmail、163、126 邮箱";
            }
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", "465");
            props.put("mail.smtp.socketFactory.port", "465");

            Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(fromEmail, password);
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromEmail));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject("[手环数据] 每日睡眠与健康自动备份");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText("这是小米手环同步后的 Gadgetbridge 数据库，请查收分析。");

            MimeBodyPart filePart = new MimeBodyPart();
            FileDataSource source = new FileDataSource(attachment);
            filePart.setDataHandler(new DataHandler(source));
            filePart.setFileName("Gadgetbridge.sqlite");

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(filePart);
            message.setContent(multipart);
            Transport.send(message);
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
        }
    }
}
