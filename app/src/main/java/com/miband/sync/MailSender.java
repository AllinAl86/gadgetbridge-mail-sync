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

public class MailSender {

    public static boolean sendMailWithAttachment(String fromEmail, String password, String toEmail, File attachment) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            
            // 自动判断常用邮箱 SMTP 服务器
            if (fromEmail.endsWith("@qq.com") || fromEmail.endsWith("@foxmail.com")) {
                props.put("mail.smtp.host", "smtp.qq.com");
                props.put("mail.smtp.port", "465");
            } else if (fromEmail.endsWith("@163.com")) {
                props.put("mail.smtp.host", "smtp.163.com");
                props.put("mail.smtp.port", "465");
            } else {
                props.put("mail.smtp.host", "smtp.qq.com");
                props.put("mail.smtp.port", "465");
            }

            Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(fromEmail, password);
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromEmail));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject("[手环数据] 每日睡眠与健康自动备份");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText("这是小米手环 6 自动同步的 SQLite 数据库文件，请查收分析。");

            MimeBodyPart filePart = new MimeBodyPart();
            FileDataSource source = new FileDataSource(attachment);
            filePart.setDataHandler(new DataHandler(source));
            filePart.setFileName(attachment.getName());

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(filePart);

            message.setContent(multipart);

            Transport.send(message);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
