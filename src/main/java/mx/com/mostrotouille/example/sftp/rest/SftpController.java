package mx.com.mostrotouille.example.sftp.rest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.file.FileNameGenerator;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.outbound.SftpMessageHandler;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jcraft.jsch.ChannelSftp.LsEntry;

import lombok.extern.slf4j.Slf4j;
import mx.com.mostrotouille.example.sftp.dto.File;
import mx.com.mostrotouille.example.sftp.dto.Response;

@RestController
@RequestMapping("/sftp")
@Slf4j
public class SftpController {
	@MessagingGateway
	public interface UploadGateway {
		@Gateway(requestChannel = "toSftpChannel")
		public void upload(java.io.File file);
	}

	@Value("${sftp.host}")
	private String host;

	@Value("${sftp.password}")
	private String password;

	@Value("${sftp.port}")
	private int port;

	@Value("${sftp.remote.path}")
	private String remotePath;

	@Value("${local.temporal.path}")
	private String temporalPath;

	@Autowired
	private UploadGateway uploadGateway;

	@Value("${sftp.user}")
	private String user;

	@Bean
	@ServiceActivator(inputChannel = "toSftpChannel")
	public MessageHandler handler() {
		final SftpMessageHandler sftpMessageHandler = new SftpMessageHandler(sessionFactory());
		sftpMessageHandler.setRemoteDirectoryExpression(new LiteralExpression(remotePath));
		sftpMessageHandler.setTemporaryFileSuffix(".tmp");
		sftpMessageHandler.setFileNameGenerator(new FileNameGenerator() {
			@Override
			public String generateFileName(Message<?> message) {
				if (message.getPayload() instanceof java.io.File) {
					return ((java.io.File) message.getPayload()).getName();
				} else {
					throw new IllegalArgumentException("File expected as payload.");
				}
			}
		});

		return sftpMessageHandler;
	}

	@PostMapping(value = "/send", consumes = "application/json", produces = "application/json")
	public ResponseEntity<Response> send(@RequestBody File file) {
		java.io.File temporalFile = null;

		try {
			temporalFile = new java.io.File(new java.io.File(temporalPath), file.getFilename());

			FileUtils.writeByteArrayToFile(temporalFile, Base64.getDecoder().decode(file.getContent()));

			uploadGateway.upload(temporalFile);

			final Response response = Response.builder().status(HttpStatus.OK).timestamp(LocalDateTime.now()).build();

			return new ResponseEntity<>(response, response.getStatus());
		} catch (Exception ex) {
			final String errorMessage = "Send file by SFTP failed.";

			log.error(errorMessage, ex);

			final Response response = Response.builder().status(HttpStatus.BAD_REQUEST).timestamp(LocalDateTime.now())
					.message(errorMessage).errors(Arrays.asList(ex.getMessage())).build();

			return new ResponseEntity<>(response, response.getStatus());
		} finally {
			if (temporalFile != null && temporalFile.exists()) {
				try {
					temporalFile.delete();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Bean
	public SessionFactory<LsEntry> sessionFactory() {
		final DefaultSftpSessionFactory sftpSessionFactory = new DefaultSftpSessionFactory(true);
		sftpSessionFactory.setHost(host);
		sftpSessionFactory.setPort(port);
		sftpSessionFactory.setUser(user);
		sftpSessionFactory.setPassword(password);
		sftpSessionFactory.setAllowUnknownKeys(true);

		return new CachingSessionFactory<LsEntry>(sftpSessionFactory);
	}
}