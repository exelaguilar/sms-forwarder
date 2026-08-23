package com.personal.smsforwarder.forwarder

import com.personal.smsforwarder.core.TemplateRenderer
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Plain-text email over SMTP. Auth is used only when a username is set, so a local
 * dev catcher (MailHog / smtp4dev on port 1025) works with no credentials.
 */
class EmailForwarder : Forwarder {

    override suspend fun send(
        request: ForwardRequest,
        config: ForwarderConfig,
        onProgress: (String) -> Unit,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val c = config as ForwarderConfig.Email
            when {
                c.host.isBlank() -> return@withContext Result.failure(ForwarderConfigException("No SMTP host configured"))
                c.to.isBlank() -> return@withContext Result.failure(ForwarderConfigException("No recipient configured"))
                c.from.isBlank() -> return@withContext Result.failure(ForwarderConfigException("No from address configured"))
            }

            runCatching {
                val useAuth = c.username.isNotBlank()
                val props = Properties().apply {
                    put("mail.smtp.host", c.host)
                    put("mail.smtp.port", c.port.toString())
                    put("mail.smtp.auth", useAuth.toString())
                    put("mail.smtp.starttls.enable", c.useStartTls.toString())
                    if (c.useSsl) {
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.socketFactory.port", c.port.toString())
                        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    }
                    put("mail.smtp.connectiontimeout", "15000")
                    put("mail.smtp.timeout", "20000")
                    put("mail.smtp.writetimeout", "20000")
                }

                val session = if (useAuth) {
                    Session.getInstance(props, object : Authenticator() {
                        override fun getPasswordAuthentication() =
                            PasswordAuthentication(c.username, c.password)
                    })
                } else {
                    Session.getInstance(props)
                }

                val mime = MimeMessage(session).apply {
                    setFrom(InternetAddress(c.from))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(c.to, false))
                    subject = TemplateRenderer.render(c.subjectTemplate, request)
                    setText(TemplateRenderer.render(c.bodyTemplate, request), "UTF-8")
                    sentDate = java.util.Date()
                }

                onProgress("connecting to ${c.host}:${c.port}")
                Transport.send(mime)
                "sent to ${c.to} via ${c.host}:${c.port}"
            }
        }
}
