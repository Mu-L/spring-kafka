/*
 * Copyright 2014-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.config;


import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.AfterRollbackProcessor;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.adapter.BatchToRecordAdapter;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.listener.adapter.ReplyHeadersConfigurer;
import org.springframework.kafka.requestreply.ReplyingKafkaOperations;
import org.springframework.kafka.support.JavaUtils;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.converter.BatchMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.util.Assert;

/**
 * Base {@link KafkaListenerContainerFactory} for Spring's base container implementation.
 *
 * @param <C> the {@link AbstractMessageListenerContainer} implementation type.
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Stephane Nicoll
 * @author Gary Russell
 * @author Artem Bilan
 *
 * @see AbstractMessageListenerContainer
 */
public abstract class AbstractKafkaListenerContainerFactory<C extends AbstractMessageListenerContainer<K, V>, K, V>
		implements KafkaListenerContainerFactory<C>, ApplicationEventPublisherAware, ApplicationContextAware {

	protected final LogAccessor logger = new LogAccessor(LogFactory.getLog(getClass())); // NOSONAR protected

	private final ContainerProperties containerProperties = new ContainerProperties((Pattern) null); // NOSONAR

	private @Nullable CommonErrorHandler commonErrorHandler;

	private @Nullable ConsumerFactory<? super K, ? super V> consumerFactory;

	private @Nullable Boolean autoStartup;

	private @Nullable Integer phase;

	private @Nullable RecordMessageConverter recordMessageConverter;

	private @Nullable BatchMessageConverter batchMessageConverter;

	private @Nullable RecordFilterStrategy<? super K, ? super V> recordFilterStrategy;

	private @Nullable Boolean ackDiscarded;

	private @Nullable Boolean batchListener;

	private @Nullable ApplicationEventPublisher applicationEventPublisher;

	private @Nullable KafkaTemplate<?, ?> replyTemplate;

	private @Nullable AfterRollbackProcessor<? super K, ? super V> afterRollbackProcessor;

	private @Nullable ReplyHeadersConfigurer replyHeadersConfigurer;

	private @Nullable Boolean missingTopicsFatal;

	private @Nullable RecordInterceptor<K, V> recordInterceptor;

	private @Nullable BatchInterceptor<K, V> batchInterceptor;

	private @Nullable BatchToRecordAdapter<K, V> batchToRecordAdapter;

	private @Nullable ApplicationContext applicationContext;

	private @Nullable ContainerCustomizer<K, V, C> containerCustomizer;

	private @Nullable String correlationHeaderName;

	private @Nullable Boolean changeConsumerThreadName;

	private @Nullable Function<MessageListenerContainer, String> threadNameSupplier;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * Specify a {@link ConsumerFactory} to use.
	 * @param consumerFactory The consumer factory.
	 */
	public void setConsumerFactory(ConsumerFactory<? super K, ? super V> consumerFactory) {
		this.consumerFactory = consumerFactory;
	}

	public @Nullable ConsumerFactory<? super K, ? super V> getConsumerFactory() {
		return this.consumerFactory;
	}

	/**
	 * Specify an {@code autoStartup boolean} flag.
	 * @param autoStartup true for auto startup.
	 * @see AbstractMessageListenerContainer#setAutoStartup(boolean)
	 */
	public void setAutoStartup(Boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	/**
	 * Specify a {@code phase} to use.
	 * @param phase The phase.
	 * @see AbstractMessageListenerContainer#setPhase(int)
	 */
	public void setPhase(int phase) {
		this.phase = phase;
	}

	/**
	 * Set the message converter to use if dynamic argument type matching is needed for
	 * record listeners.
	 * @param recordMessageConverter the converter.
	 * @since 2.9.6
	 */
	public void setRecordMessageConverter(RecordMessageConverter recordMessageConverter) {
		this.recordMessageConverter = recordMessageConverter;
	}

	/**
	 * Set the message converter to use if dynamic argument type matching is needed for
	 * batch listeners.
	 * @param batchMessageConverter the converter.
	 * @since 2.9.6
	 */
	public void setBatchMessageConverter(BatchMessageConverter batchMessageConverter) {
		this.batchMessageConverter = batchMessageConverter;
	}

	/**
	 * Set the record filter strategy.
	 * @param recordFilterStrategy the strategy.
	 */
	public void setRecordFilterStrategy(RecordFilterStrategy<? super K, ? super V> recordFilterStrategy) {
		this.recordFilterStrategy = recordFilterStrategy;
	}

	/**
	 * Set to true to ack discards when a filter strategy is in use.
	 * @param ackDiscarded the ackDiscarded.
	 */
	public void setAckDiscarded(Boolean ackDiscarded) {
		this.ackDiscarded = ackDiscarded;
	}

	/**
	 * Return true if this endpoint creates a batch listener.
	 * @return true for a batch listener.
	 * @since 1.1
	 */
	public @Nullable Boolean isBatchListener() {
		return this.batchListener;
	}

	/**
	 * Set to true if this endpoint should create a batch listener.
	 * @param batchListener true for a batch listener.
	 * @since 1.1
	 */
	public void setBatchListener(Boolean batchListener) {
		this.batchListener = batchListener;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = applicationEventPublisher;
	}

	/**
	 * Set the {@link KafkaTemplate} to use to send replies.
	 * @param replyTemplate the template.
	 * @since 2.0
	 */
	public void setReplyTemplate(KafkaTemplate<?, ?> replyTemplate) {
		if (replyTemplate instanceof ReplyingKafkaOperations) {
			this.logger.warn(
					"The 'replyTemplate' should not be an implementation of 'ReplyingKafkaOperations'; "
							+ "such implementations are for client-side request/reply operations; here we "
							+ "are simply sending a reply to an incoming request so the reply container will "
							+ "never be used and will consume unnecessary resources.");
		}
		this.replyTemplate = replyTemplate;
	}

	/**
	 * Set the {@link CommonErrorHandler} which can handle errors for both record and
	 * batch listeners.
	 * @param commonErrorHandler the handler.
	 * @since 2.8
	 */
	public void setCommonErrorHandler(CommonErrorHandler commonErrorHandler) {
		this.commonErrorHandler = commonErrorHandler;
	}

	/**
	 * Set a processor to invoke after a transaction rollback; typically will
	 * seek the unprocessed topic/partition to reprocess the records.
	 * The default does so, including the failed record.
	 * @param afterRollbackProcessor the processor.
	 * @since 1.3.5
	 */
	public void setAfterRollbackProcessor(AfterRollbackProcessor<? super K, ? super V> afterRollbackProcessor) {
		this.afterRollbackProcessor = afterRollbackProcessor;
	}

	/**
	 * Set a configurer which will be invoked when creating a reply message.
	 * @param replyHeadersConfigurer the configurer.
	 * @since 2.2
	 */
	public void setReplyHeadersConfigurer(ReplyHeadersConfigurer replyHeadersConfigurer) {
		this.replyHeadersConfigurer = replyHeadersConfigurer;
	}

	/**
	 * Set to false to allow the container to start even if any of the configured topics
	 * are not present on the broker. Does not apply when topic patterns are configured.
	 * Default true;
	 * @param missingTopicsFatal the missingTopicsFatal.
	 * @since 2.3
	 */
	public void setMissingTopicsFatal(boolean missingTopicsFatal) {
		this.missingTopicsFatal = missingTopicsFatal;
	}

	/**
	 * Obtain the properties template for this factory - set properties as needed
	 * and they will be copied to a final properties instance for the endpoint.
	 * @return the properties.
	 */
	public ContainerProperties getContainerProperties() {
		return this.containerProperties;
	}

	/**
	 * Set an interceptor to be called before calling the listener.
	 * Only used with record listeners.
	 * @param recordInterceptor the interceptor.
	 * @since 2.2.7
	 * @see #setBatchInterceptor(BatchInterceptor)
	 */
	public void setRecordInterceptor(RecordInterceptor<K, V> recordInterceptor) {
		this.recordInterceptor = recordInterceptor;
	}

	/**
	 * Set a batch interceptor to be called before and after calling the listener.
	 * Only used with batch listeners.
	 * @param batchInterceptor the interceptor.
	 * @since 2.7
	 * @see #setRecordInterceptor(RecordInterceptor)
	 */
	public void setBatchInterceptor(BatchInterceptor<K, V> batchInterceptor) {
		this.batchInterceptor = batchInterceptor;
	}

	/**
	 * Set a {@link BatchToRecordAdapter}.
	 * @param batchToRecordAdapter the adapter.
	 * @since 2.4.2
	 */
	public void setBatchToRecordAdapter(BatchToRecordAdapter<K, V> batchToRecordAdapter) {
		this.batchToRecordAdapter = batchToRecordAdapter;
	}

	/**
	 * Set a customizer used to further configure a container after it has been created.
	 * @param containerCustomizer the customizer.
	 * @since 2.3.4
	 */
	public void setContainerCustomizer(ContainerCustomizer<K, V, C> containerCustomizer) {
		this.containerCustomizer = containerCustomizer;
	}

	/**
	 * Set a custom header name for the correlation id. Default
	 * {@link org.springframework.kafka.support.KafkaHeaders#CORRELATION_ID}. This header
	 * will be echoed back in any reply message.
	 * @param correlationHeaderName the header name.
	 * @since 3.0
	 */
	public void setCorrelationHeaderName(String correlationHeaderName) {
		this.correlationHeaderName = correlationHeaderName;
	}

	/**
	 * Set to true to instruct the container to change the consumer thread name during
	 * initialization.
	 * @param changeConsumerThreadName true to change.
	 * @since 3.0.1
	 * @see #setThreadNameSupplier(Function)
	 */
	public void setChangeConsumerThreadName(boolean changeConsumerThreadName) {
		this.changeConsumerThreadName = changeConsumerThreadName;
	}

	/**
	 * Set a function used to change the consumer thread name. The default returns the
	 * container {@code listenerId}.
	 * @param threadNameSupplier the function.
	 * @since 3.0.1
	 * @see #setChangeConsumerThreadName(boolean)
	 */
	public void setThreadNameSupplier(Function<MessageListenerContainer, String> threadNameSupplier) {
		Assert.notNull(threadNameSupplier, "'threadNameSupplier' cannot be null");
		this.threadNameSupplier = threadNameSupplier;
	}

	@SuppressWarnings({"unchecked", "NullAway"})
	@Override
	public C createListenerContainer(KafkaListenerEndpoint endpoint) {
		C instance = createContainerInstance(endpoint);
		JavaUtils.INSTANCE
				.acceptIfNotNull(endpoint.getId(), instance::setBeanName)
				.acceptIfNotNull(endpoint.getMainListenerId(), instance::setMainListenerId);
		if (endpoint instanceof AbstractKafkaListenerEndpoint) {
			configureEndpoint((AbstractKafkaListenerEndpoint<K, V>) endpoint);
		}

		if (Boolean.TRUE.equals(endpoint.getBatchListener())) {
			endpoint.setupListenerContainer(instance, this.batchMessageConverter);
		}
		else {
			endpoint.setupListenerContainer(instance, this.recordMessageConverter);
		}
		initializeContainer(instance, endpoint);
		customizeContainer(instance, endpoint);
		return instance;
	}

	@SuppressWarnings("NullAway") // Dataflow analysis limitation
	private void configureEndpoint(AbstractKafkaListenerEndpoint<K, V> aklEndpoint) {
		if (aklEndpoint.getRecordFilterStrategy() == null) {
			JavaUtils.INSTANCE
					.acceptIfNotNull(this.recordFilterStrategy, aklEndpoint::setRecordFilterStrategy);
		}
		JavaUtils.INSTANCE
				.acceptIfNotNull(this.ackDiscarded, aklEndpoint::setAckDiscarded)
				.acceptIfNotNull(this.replyTemplate, aklEndpoint::setReplyTemplate)
				.acceptIfNotNull(this.replyHeadersConfigurer, aklEndpoint::setReplyHeadersConfigurer)
				.acceptIfNotNull(this.batchToRecordAdapter, aklEndpoint::setBatchToRecordAdapter)
				.acceptIfNotNull(this.correlationHeaderName, aklEndpoint::setCorrelationHeaderName);
		if (aklEndpoint.getBatchListener() == null) {
			JavaUtils.INSTANCE
					.acceptIfNotNull(this.batchListener, aklEndpoint::setBatchListener);
		}
	}

	/**
	 * Create an empty container instance.
	 * @param endpoint the endpoint.
	 * @return the new container instance.
	 */
	protected abstract C createContainerInstance(KafkaListenerEndpoint endpoint);

	/**
	 * Further initialize the specified container.
	 * <p>Subclasses can inherit from this method to apply extra
	 * configuration if necessary.
	 * @param instance the container instance to configure.
	 * @param endpoint the endpoint.
	 */
	@SuppressWarnings({"NullAway"})
	protected void initializeContainer(C instance, KafkaListenerEndpoint endpoint) {
		ContainerProperties properties = instance.getContainerProperties();
		BeanUtils.copyProperties(this.containerProperties, properties, "topics", "topicPartitions", "topicPattern",
				"messageListener", "ackCount", "ackTime", "subBatchPerPartition", "kafkaConsumerProperties");
		JavaUtils.INSTANCE
				.acceptIfNotNull(this.afterRollbackProcessor, instance::setAfterRollbackProcessor)
				.acceptIfCondition(this.containerProperties.getAckCount() > 0, this.containerProperties.getAckCount(),
						properties::setAckCount)
				.acceptIfCondition(this.containerProperties.getAckTime() > 0, this.containerProperties.getAckTime(),
						properties::setAckTime)
				.acceptIfNotNull(this.containerProperties.getSubBatchPerPartition(),
						properties::setSubBatchPerPartition)
				.acceptIfNotNull(this.commonErrorHandler, instance::setCommonErrorHandler)
				.acceptIfNotNull(this.missingTopicsFatal, instance.getContainerProperties()::setMissingTopicsFatal)
				.acceptIfNotNull(this.changeConsumerThreadName, instance::setChangeConsumerThreadName)
				.acceptIfNotNull(this.threadNameSupplier, instance::setThreadNameSupplier);
		Boolean autoStart = endpoint.getAutoStartup();
		if (autoStart != null) {
			instance.setAutoStartup(autoStart);
		}
		else if (this.autoStartup != null) {
			instance.setAutoStartup(this.autoStartup);
		}
		instance.setRecordInterceptor(this.recordInterceptor);
		instance.setBatchInterceptor(this.batchInterceptor);
		JavaUtils.INSTANCE
				.acceptIfNotNull(this.phase, instance::setPhase)
				.acceptIfNotNull(this.applicationContext, instance::setApplicationContext)
				.acceptIfNotNull(this.applicationEventPublisher, instance::setApplicationEventPublisher)
				.acceptIfHasText(endpoint.getGroupId(), instance.getContainerProperties()::setGroupId)
				.acceptIfHasText(endpoint.getClientIdPrefix(), instance.getContainerProperties()::setClientId)
				.acceptIfNotNull(endpoint.getConsumerProperties(),
						instance.getContainerProperties()::setKafkaConsumerProperties)
				.acceptIfNotNull(endpoint.getListenerInfo(), instance::setListenerInfo);
	}

	@Override
	public C createContainer(TopicPartitionOffset... topicsAndPartitions) {
		return createContainer(new KafkaListenerEndpointAdapter() {

			@Override
			public TopicPartitionOffset[] getTopicPartitionsToAssign() {
				return Arrays.copyOf(topicsAndPartitions, topicsAndPartitions.length);
			}

		});
	}

	@Override
	public C createContainer(String... topics) {
		return createContainer(new KafkaListenerEndpointAdapter() {

			@Override
			public Collection<String> getTopics() {
				return Arrays.asList(topics);
			}

		});
	}

	@Override
	public C createContainer(Pattern topicPattern) {
		return createContainer(new KafkaListenerEndpointAdapter() {

			@Override
			public Pattern getTopicPattern() {
				return topicPattern;
			}

		});
	}

	protected C createContainer(KafkaListenerEndpoint endpoint) {
		final C container = createContainerInstance(endpoint);
		initializeContainer(container, endpoint);
		customizeContainer(container, endpoint);
		return container;
	}

	@SuppressWarnings("unchecked")
	protected void customizeContainer(C instance, KafkaListenerEndpoint endpoint) {
		if (this.containerCustomizer != null) {
			this.containerCustomizer.configure(instance);
		}
		final ContainerPostProcessor<K, V, C> containerPostProcessor = (ContainerPostProcessor<K, V, C>)
				endpoint.getContainerPostProcessor();
		if (containerPostProcessor != null) {
			containerPostProcessor.postProcess(instance);
		}
	}
}
