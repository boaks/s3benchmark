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

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 asynchronous client.
 */
public class S3AsyncClientFacade {
	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(S3AsyncClientFacade.class);

	/**
	 * Default region.
	 */
	public static final String DEFAULT_REGION = "us-east-1";
	/**
	 * Default bucket name.
	 */
	public static final String DEFAULT_S3_BUCKET = "devices";
	/**
	 * Default concurrency for netty-i/o.
	 */
	public static final int DEFAULT_CONCURRENCY = 200;
	/**
	 * Zero as {@link AtomicLong}.
	 */
	private static final AtomicLong ZERO = new AtomicLong();

	/**
	 * S3 asynchronous client.
	 */
	private final S3AsyncClient s3Client;
	/**
	 * Map of last etags.
	 */
	private final ConcurrentMap<String, String> etags = new ConcurrentHashMap<>();
	/**
	 * Bucket name.
	 */
	private final String bucket;
	/**
	 * ACL to use.
	 */
	private final String acl;

	private final Statistic overall;

	private final Statistic current;

	/**
	 * Maximum pending requests.
	 */
	private final AtomicLong maxPending = new AtomicLong();
	/**
	 * Last exception.
	 */
	private volatile Throwable lastException;
	/**
	 * Last error status.
	 */
	private volatile Integer lastErrorStatus;
	/**
	 * Scheduler for postpone completes.
	 */
	private ScheduledExecutorService scheduler;
	/**
	 * Delay job to postpone completes.
	 */
	private Runnable delay = new Runnable() {
		@Override
		public void run() {
			complete();
		}
	};

	/**
	 * Create a new instance.
	 * 
	 * Use {@link Builder} to create instance.
	 * 
	 * @param concurrency concurrency for netty i/o
	 * @param endpoint    s3 endpoint
	 * @param region      region of the bucket
	 * @param bucket      name of the bucket
	 * @param acl         ACL to be used for the PUT object
	 * @param keyId       access-key id.
	 * @param keySecret   access -secret
	 */
	private S3AsyncClientFacade(int concurrency, URI endpoint, String region, String bucket, String acl, String keyId,
			String keySecret) {
		S3AsyncClientBuilder builder = S3AsyncClient.builder();
		builder.region(Region.of(region));
		if (endpoint != null) {
			builder.endpointOverride(endpoint);
		}
		if (keyId != null && keySecret != null) {
			AwsBasicCredentials credentials = AwsBasicCredentials.create(keyId, keySecret);
			builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
		}
		builder.httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(concurrency));
		this.s3Client = builder.build();
		this.bucket = bucket;
		this.acl = acl;
		long now = System.nanoTime();
		this.overall = new Statistic(now);
		this.current = new Statistic(now);
		this.scheduler = Executors.newScheduledThreadPool(5);
		setMaxPending(concurrency * 2);
	}

	/**
	 * Report completion of request.
	 */
	private void complete() {
		synchronized (overall) {
			current.completed.incrementAndGet();
			overall.notifyAll();
		}
	}

	private void postpone() {
		current.reduceRateCounter.incrementAndGet();
		scheduler.schedule(delay, 2000, TimeUnit.MILLISECONDS);
	}

	/**
	 * Close the client.
	 */
	public void close() {
		scheduler.shutdownNow();
	}

	/**
	 * Pending request.
	 * 
	 * @return number of pending request.
	 * @see #sent
	 * @see #completed
	 */
	public long pending() {
		synchronized (overall) {
			long pending = overall.sent.get() - overall.completed.get();
			return pending + current.sent.get() - current.completed.get();
		}
	}

	/**
	 * Get last exception.
	 * 
	 * @return last exception. {@code null}, if no error occurred.
	 */
	public Throwable getLastException() {
		return lastException;
	}

	/**
	 * Get last http error status.
	 * 
	 * @return last http error status. {@code null}, if no error occurred.
	 */
	public Integer getLastErrorStatus() {
		return lastErrorStatus;
	}

	/**
	 * Wait until no request is pending.
	 * 
	 * @param time maximum time to wait
	 * @param unit unit of the time
	 * @return {@code true}, if no pending requests are left, {@code false}, if
	 *         pending requests are left within the timeout.
	 * @see #waitPending(AtomicLong, long, TimeUnit)
	 */
	public boolean waitReady(long time, TimeUnit unit) {
		return waitPending(ZERO, time, unit);
	}

	/**
	 * Wait until pending request are below a threshold.
	 * 
	 * @param pending maximum number of pending requests. If {@link #maxPending} is
	 *                less than the provided value, it is adjusted to
	 *                {@link #maxPending}.
	 * @param time    maximum time to wait
	 * @param unit    unit of the time
	 * @return {@code true}, if the pending requests are less then the provided
	 *         maximum, {@code false}, if more requests are pending
	 */
	public boolean waitPending(AtomicLong pending, long time, TimeUnit unit) {
		boolean ready = pending() <= pending.get();
		if (!ready) {
			if (pending != maxPending && pending != ZERO) {
				long maxPending = this.maxPending.get();
				if (pending.get() > maxPending) {
					pending.set(maxPending);
				}
			}
			final long end = unit.toNanos(time) + System.nanoTime();
			synchronized (overall) {
				long current = pending();
				ready = current <= pending.get();
				while (!ready) {
					dumpStatistic(10, TimeUnit.SECONDS);
					long millis = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
					if (millis > 0) {
						try {
							LOGGER.trace("wait {}", millis);
							overall.wait(millis);
						} catch (InterruptedException e) {
						}
						current = pending();
						ready = current <= pending.get();
					} else {
						// timeout
						ready = pending() <= pending.get();
						break;
					}
				}
			}
		}
		return ready;
	}

	/**
	 * Sets the initial maximum pending requests.
	 * 
	 * On receiving overload responses (503), that maximum pending requests is
	 * lowered in order to reduce the request rate.
	 * 
	 * @param max maximum pending requests
	 * @return the current maximum pending request.
	 * @see #waitPending(AtomicLong, long, TimeUnit)
	 */
	public AtomicLong setMaxPending(long max) {
		maxPending.set(max);
		return maxPending;
	}

	/**
	 * Dump statistic at provided interval.
	 * 
	 * @param interval interval time
	 * @param unit     time unit of the interval time
	 */
	public void dumpStatistic(long interval, TimeUnit unit) {
		long now = System.nanoTime();
		if ((now - current.start - unit.toNanos(interval)) > 0) {
			dumpStatistic(true);
		}
	}

	/**
	 * Dump statistic.
	 */
	public void dumpStatistic(boolean transfer) {
		long now = System.nanoTime();
		long time, overallTime;
		long count, sent, failures, rr;
		long overallCount, overallFailures;
		synchronized (overall) {
			time = TimeUnit.NANOSECONDS.toMillis(now - current.start);
			overallTime = TimeUnit.NANOSECONDS.toMillis(now - overall.start);
			if (transfer) {
				count = current.completed.getAndSet(0);
				overallCount = overall.completed.addAndGet(count);
				rr = current.reduceRateCounter.getAndSet(0);
				overall.reduceRateCounter.addAndGet(rr);
				sent = current.sent.getAndSet(0);
				overall.sent.addAndGet(sent);
				failures = current.failures.getAndSet(0);
				overallFailures = overall.failures.addAndGet(failures);
				current.start = now;
			} else {
				count = current.completed.get();
				overallCount = overall.completed.get() + count;
				rr = current.reduceRateCounter.get();
				sent = current.sent.get();
				failures = current.failures.get();
				overallFailures = overall.failures.get() + failures;
			}
		}

		if (rr > 0) {
			if (transfer && sent > 0) {
				long max = maxPending.get();
				long newMax = max * (100 - ((rr * 3 * 100) / sent)) / 100;
				LOGGER.debug("Max. pending {} => {} ({}%)", max, newMax, (rr * 100) / sent);
				maxPending.compareAndSet(max, newMax);
			}
			LOGGER.info("{} {}/{} requests/s, overall: {} requests, {} failures, {} rr, {} max. pending",
					TimeUnit.MILLISECONDS.toSeconds(overallTime), ((count * 1000) / time),
					((overallCount * 1000) / overallTime), overallCount, overallFailures, rr, maxPending.get());
		} else {
			LOGGER.info("{} {}/{} requests/s, overall: {} requests, {} failures",
					TimeUnit.MILLISECONDS.toSeconds(overallTime), ((count * 1000) / time),
					((overallCount * 1000) / time), overallCount, overallFailures);
		}
	}

	/**
	 * Start PUT request.
	 * 
	 * @param key     key for the object
	 * @param payload payload for the object
	 */
	public void put(String key, String payload) {
		try {
			byte[] data = payload.getBytes();
			PutObjectRequest.Builder putBuilder = PutObjectRequest.builder().bucket(bucket).key(key);
			putBuilder.contentLength((long) data.length);
			putBuilder.contentType("text/plain; charset=utf-8");
			if (acl != null) {
				putBuilder.acl(acl);
			}
			AsyncRequestBody body = AsyncRequestBody.fromBytes(data);
			current.sent.incrementAndGet();
			final long now = System.nanoTime();
			CompletableFuture<PutObjectResponse> future = s3Client.putObject(putBuilder.build(), body);
			future.whenComplete((putResponse, exception) -> {
				long timeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now);
				SdkHttpResponse httpErrorResponse = null;
				if (exception != null) {
					Throwable cause = exception;
					if (exception instanceof CompletionException) {
						cause = exception.getCause();
					}
					if (cause instanceof S3Exception) {
						AwsErrorDetails details = ((S3Exception) cause).awsErrorDetails();
						httpErrorResponse = details.sdkHttpResponse();
					}
					if (httpErrorResponse == null) {
						lastException = cause;
						LOGGER.warn(">S3: ({}ms)", timeMillis, exception);
						current.failures.incrementAndGet();
					}
				} else if (putResponse != null) {
					SdkHttpResponse httpResponse = putResponse.sdkHttpResponse();
					if (httpResponse == null || httpResponse.isSuccessful()) {
						LOGGER.debug(">S3: ({}ms) {}", timeMillis,
								httpResponse == null ? "-" : httpResponse.statusCode());
						String eTag = putResponse.eTag();
						if (eTag != null) {
							etags.put(key, eTag);
						}
					} else {
						httpErrorResponse = httpResponse;
					}
				} else {
					LOGGER.debug(">S3: ({}ms) no response nor error!", timeMillis);
				}
				if (httpErrorResponse != null) {
					if (httpErrorResponse.statusCode() == 503) {
						// delay complete()
						postpone();
						return;
					}
					LOGGER.warn(">S3: ({}ms) {} - {}!", timeMillis, httpErrorResponse.statusCode(),
							httpErrorResponse.statusText());
					current.failures.incrementAndGet();
					lastErrorStatus = httpErrorResponse.statusCode();
				}
				complete();
			});
		} catch (S3Exception e) {
			LOGGER.warn("S3:", e);
		} catch (SdkException e) {
			LOGGER.warn("S3:", e);
		}
	}

	/**
	 * Start GET request.
	 * 
	 * @param key key for the object
	 */
	public void get(String key) {
		try {
			GetObjectRequest.Builder getBuilder = GetObjectRequest.builder().bucket(bucket).key(key);
			final String eTag = etags.get(key);
			if (eTag != null) {
				getBuilder.ifNoneMatch(eTag);
			}
			current.sent.incrementAndGet();
			final long now = System.nanoTime();
			CompletableFuture<ResponseBytes<GetObjectResponse>> future = s3Client.getObject(getBuilder.build(),
					AsyncResponseTransformer.toBytes());
			future.whenComplete((getResponse, exception) -> {
				long timeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now);
				SdkHttpResponse httpErrorResponse = null;
				if (exception != null) {
					Throwable cause = exception;
					if (exception instanceof CompletionException) {
						cause = exception.getCause();
					}
					if (cause instanceof S3Exception) {
						AwsErrorDetails details = ((S3Exception) cause).awsErrorDetails();
						httpErrorResponse = details.sdkHttpResponse();
					}
					if (httpErrorResponse == null) {
						lastException = cause;
						LOGGER.warn(">S3: ({}ms)", timeMillis, exception);
						current.failures.incrementAndGet();
					}
				} else if (getResponse != null) {
					SdkHttpResponse httpResponse = getResponse.response().sdkHttpResponse();
					if (httpResponse == null || httpResponse.isSuccessful()) {
						LOGGER.debug(">S3: ({}ms) {}", timeMillis,
								httpResponse == null ? "-" : httpResponse.statusCode());
						String eTag2 = getResponse.response().eTag();
						if (eTag2 != null && !eTag2.equals(eTag)) {
							etags.put(key, eTag2);
							LOGGER.debug(">S3: ({}ms) eTag {}/{}", timeMillis, key, eTag2);
						}
					} else {
						httpErrorResponse = httpResponse;
					}
				} else {
					LOGGER.debug(">S3: ({}ms) no response nor error!", timeMillis);
				}
				if (httpErrorResponse != null) {
					if (httpErrorResponse.statusCode() == 503) {
						// delay complete()
						postpone();
						return;
					} else if (httpErrorResponse.statusCode() == 304) {
						LOGGER.debug(">S3: ({}ms) not modified", timeMillis);
					} else {
						LOGGER.warn(">S3: ({}ms) {} - {}!", timeMillis, httpErrorResponse.statusCode(),
								httpErrorResponse.statusText());
						current.failures.incrementAndGet();
						lastErrorStatus = httpErrorResponse.statusCode();
					}
				}
				complete();
			});
		} catch (S3Exception e) {
			LOGGER.warn("S3:", e);
		} catch (SdkException e) {
			LOGGER.warn("S3:", e);
		}
	}

	/**
	 * Get builder for client.
	 * 
	 * @return builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for client.
	 */
	public static class Builder {

		private URI endpoint;
		private String region = DEFAULT_REGION;
		private String bucket = DEFAULT_S3_BUCKET;
		private String acl;
		private String keyId;
		private String keySecret;
		private int concurrency = DEFAULT_CONCURRENCY;

		/**
		 * Set the endpoint as URI.
		 * 
		 * @param endpoint the endpoint
		 * @return builder to chain commands
		 */
		public Builder endpoint(URI endpoint) {
			this.endpoint = endpoint;
			return this;
		}

		/**
		 * Set the endpoint as string.
		 * 
		 * @param endpoint the endpoint
		 * @return builder to chain commands
		 */
		public Builder endpoint(String endpoint) {
			this.endpoint = URI.create(endpoint);
			return this;
		}

		/**
		 * Set the region.
		 * 
		 * @param region the region
		 * @return builder to chain commands
		 */
		public Builder region(String region) {
			if (region == null) {
				this.region = DEFAULT_REGION;
			} else {
				this.region = region;
			}
			return this;
		}

		/**
		 * Set the bucket.
		 * 
		 * @param bucket the bucket
		 * @return builder to chain commands
		 */
		public Builder bucket(String bucket) {
			if (bucket == null) {
				this.bucket = DEFAULT_S3_BUCKET;
			} else {
				this.bucket = bucket;
			}
			return this;
		}

		/**
		 * Set the ACL.
		 * 
		 * @param acl the ACL
		 * @return builder to chain commands
		 */
		public Builder acl(String acl) {
			this.acl = acl;
			return this;
		}

		/**
		 * Set the access key.
		 * 
		 * @param bucket the access key
		 * @return builder to chain commands
		 */
		public Builder keyId(String keyId) {
			this.keyId = keyId;
			return this;
		}

		/**
		 * Set the secret key.
		 * 
		 * @param bucket the secret key
		 * @return builder to chain commands
		 */
		public Builder keySecret(String keySecret) {
			this.keySecret = keySecret;
			return this;
		}

		/**
		 * Set the concurrency for netty i/o.
		 * 
		 * @param concurrency the concurrency for netty i/o
		 * @return builder to chain commands
		 */
		public Builder concurrency(int concurrency) {
			this.concurrency = concurrency;
			return this;
		}

		/**
		 * Build the client with the already provided arguments.
		 * 
		 * @return create client
		 */
		public S3AsyncClientFacade build() {
			return new S3AsyncClientFacade(concurrency, endpoint, region, bucket, acl, keyId, keySecret);
		}
	}

	private static class Statistic {
		/**
		 * Start nano time.
		 */
		private volatile long start;
		/**
		 * Number of sent requests.
		 */
		private final AtomicLong sent = new AtomicLong();
		/**
		 * Number of completed requests.
		 */
		private final AtomicLong completed = new AtomicLong();
		/**
		 * Number of failed requests.
		 */
		private final AtomicLong failures = new AtomicLong();
		/**
		 * Number of "503 reduce rate" response for requests.
		 */
		private final AtomicLong reduceRateCounter = new AtomicLong();

		private Statistic(long time) {
			this.start = time;
		}
	}
}
