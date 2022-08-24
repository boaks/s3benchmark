/*******************************************************************************
 * Copyright (c) 2022 Achim Kraus, cloudcoap.net.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 ******************************************************************************/
package io.cloudcoap.s3benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * S3 benchmark.
 */
public class S3Benchmark {
	private static final Logger LOGGER = LoggerFactory.getLogger(S3Benchmark.class);

	enum Method {
		GET, PUT
	}

	@Command(name = "S3Benchmark", version = "(c) 2022, Achim Kraus, cloudcoap.net")
	public static class Config {
		@Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
		public boolean helpRequested;

		@Option(names = "--s3-access-key", required = true, description = "s3 access key.")
		public String accessKey;

		@Option(names = "--s3-secret", required = true, description = "s3 secret access key.")
		public String secret;

		@Option(names = "--s3-endpoint", required = false, description = "s3 endoint URI. e.g.: https://sos-de-fra-1.exo.io for ExoScale in DE-FRA1.")
		public String endpoint;

		@Option(names = "--s3-region", required = false, description = "s3 region. Only AWS regions are supported. Default: 'us-east-1'. (For other providers, try, if the default works).")
		public String region;

		@Option(names = "--s3-bucket", required = false, description = "s3 bucket. Default: devices")
		public String bucket;

		@Option(names = "--s3-acl", required = false, description = "s3 canned acl. e.g. public-read")
		public String acl;

		@Option(names = "--s3-concurrency", defaultValue = "200", required = false, description = "s3 concurrency. Default ${DEFAULT-VALUE}")
		public int concurrency;

		@Option(names = { "-k",
				"--keys" }, defaultValue = "200", required = false, description = "Number of keys. Default ${DEFAULT-VALUE}")
		public int keys;

		@Option(names = { "-r",
				"--requests" }, defaultValue = "100000", required = false, description = "Number of keys. Default ${DEFAULT-VALUE}")
		public int requests;

		@Option(names = { "-m",
				"--method" }, defaultValue = "PUT", required = false, description = "Method to test. GET or PUT. Default ${DEFAULT-VALUE}")
		public Method method;

		@Option(names = { "-p",
				"--payload" }, required = false, description = "Payload. Applies format(payload, request-number).")
		public String payload;

		@Option(names = { "-pl", "--payload-length" }, required = false, description = "Payload length.")
		public Integer payloadLength;

		private String additionalPayload;
	}

	private static final Config config = new Config();

	public static void main(String[] args) {
		CommandLine cmd = new CommandLine(config);
		try {
			ParseResult result = cmd.parseArgs(args);
			if (result.isVersionHelpRequested()) {
				System.out.println("\n" + cmd.getCommandName());
				cmd.printVersionHelp(System.out);
				System.out.println();
			}
			if (result.isUsageHelpRequested()) {
				cmd.usage(System.out);
				return;
			}
		} catch (ParameterException ex) {
			System.err.println(ex.getMessage());
			System.err.println();
			cmd.usage(System.err);
			System.exit(-1);
		}
		final S3AsyncClientFacade client = createS3Client(config);
		Runtime.getRuntime().addShutdownHook(new Thread("SHUTDOWN") {

			@Override
			public void run() {
				LOGGER.info("Shutdown ......");
				client.waitReady(2000, TimeUnit.MILLISECONDS);
				long pending = client.pending();
				if (pending > 0) {
					LOGGER.info("{} pending request left!", pending);
				}
				client.dumpStatistic(true);
				client.close();
				LOGGER.info("Terminated.");
			}
		});
		if (config.payloadLength != null) {
			config.additionalPayload = additionalPayload(config.payloadLength);
		}
		int requests = 0;
		sendRequest(config, client, requests);
		if (config.endpoint != null) {
			LOGGER.info("S3 Benchmark started! {} - {} - {}", config.method, config.bucket, config.endpoint);
		} else if (config.region != null) {
			LOGGER.info("S3 Benchmark started! {} - {} - {}", config.method, config.bucket, config.region);
		} else {
			LOGGER.info("S3 Benchmark started! {} - {}", config.method, config.bucket);
		}
		if (client.waitReady(3000, TimeUnit.MILLISECONDS) && client.getLastException() == null
				&& client.getLastErrorStatus() == null) {
			AtomicLong pending = client.setMaxPending(config.concurrency * 2);
			for (requests = 1; requests < config.requests; ++requests) {
				sendRequest(config, client, requests);
				client.waitPending(pending, 10, TimeUnit.SECONDS);
				client.dumpStatistic(10, TimeUnit.SECONDS);
			}
			LOGGER.info("Shutdown ...");
			client.waitReady(10000, TimeUnit.MILLISECONDS);
		}
		client.close();
	}

	/**
	 * Send request.
	 * 
	 * @param config   CLI configuration
	 * @param client   client facade
	 * @param requests current number of request.
	 */
	private static void sendRequest(Config config, S3AsyncClientFacade client, int requests) {
		int id = requests % config.keys;
		String key = String.format("benchmark/client%05d", id);
		switch (config.method) {
		case GET:
			client.get(key);
			break;
		case PUT:
			String payload = config.payload;
			if (payload == null) {
				payload = "Hello, S3, %05d!";
			}
			payload = String.format(payload, requests);
			if (config.payloadLength != null) {
				payload += " " + config.additionalPayload.substring(payload.length() + 1);
			}
			client.put(key, payload);
			break;
		}
	}

	private static String additionalPayload(int length) {
		if (length > 0) {
			StringBuilder text = new StringBuilder(length);
			for (int index = 0; index < length; ++index) {
				text.append((char) ('a' + (index % 26)));
			}
			return text.toString();
		} else {
			return "";
		}
	}

	/**
	 * Create a S3 asynchronous
	 * 
	 * @param config CLI configuration
	 * @return create client
	 */
	private static S3AsyncClientFacade createS3Client(Config config) {

		S3AsyncClientFacade.Builder builder = S3AsyncClientFacade.builder();
		if (config.endpoint != null) {
			builder.endpoint(config.endpoint);
		}
		builder.concurrency(config.concurrency);
		builder.bucket(config.bucket);
		builder.acl(config.acl);
		builder.region(config.region);
		builder.keyId(config.accessKey);
		builder.keySecret(config.secret);
		return builder.build();
	}
}
