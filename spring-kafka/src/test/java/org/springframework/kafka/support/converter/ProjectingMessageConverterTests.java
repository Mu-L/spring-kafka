/*
 * Copyright 2018-present the original author or authors.
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

package org.springframework.kafka.support.converter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.utils.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.web.JsonPath;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.doReturn;

/**
 * @author Oliver Gierke
 * @author Gary Russell
 *
 * @since 2.1.1
 */
@ExtendWith(MockitoExtension.class)
public class ProjectingMessageConverterTests {

	private static final String STRING_PAYLOAD =
			"{ \"username\" : \"SomeUsername\", \"user\" : { \"name\" : \"SomeName\"}}";

	private static final byte[] BYTE_ARRAY_PAYLOAD = STRING_PAYLOAD.getBytes(StandardCharsets.UTF_8);

	private static final Bytes BYTES_PAYLOAD = Bytes.wrap(BYTE_ARRAY_PAYLOAD);

	private final ProjectingMessageConverter converter = new ProjectingMessageConverter();

	@Mock
	private ConsumerRecord<?, ?> record;

	@Test
	public void rejectsNullObjectMapper() {
		assertThatIllegalArgumentException().isThrownBy(() -> new ProjectingMessageConverter(null, null));
	}

	@Test
	public void returnsKafkaNullForNullPayload() {
		doReturn(null).when(this.record).value();

		assertThat(this.converter.extractAndConvertValue(this.record, Object.class)).isEqualTo(KafkaNull.INSTANCE);
	}

	@Test
	public void createsProjectedPayloadForInterface() {
		assertProjectionProxy(STRING_PAYLOAD);
		assertProjectionProxy(BYTE_ARRAY_PAYLOAD);
		assertProjectionProxy(BYTES_PAYLOAD);
	}

	@Test
	public void usesJacksonToCreatePayloadForClass() {
		assertSimpleObject(STRING_PAYLOAD);
		assertSimpleObject(BYTE_ARRAY_PAYLOAD);
		assertSimpleObject(BYTES_PAYLOAD);
	}

	@Test
	public void rejectsInvalidPayload() {
		assertThatExceptionOfType(ConversionException.class)
			.isThrownBy(() -> assertProjectionProxy(new Object()))
			.withMessageContaining(Object.class.getName());
	}

	@Test
	public void writesProjectedPayloadUsingJackson() {
		Map<String, Object> values = new HashMap<>();
		values.put("username", "SomeUsername");
		values.put("name", "SomeName");

		Sample sample = new SpelAwareProxyProjectionFactory().createProjection(Sample.class, values);

		Object payload = this.converter.convertPayload(MessageBuilder.withPayload(sample).build());

		DocumentContext json = com.jayway.jsonpath.JsonPath.parse(payload.toString());

		assertThat(json.read("$.username", String.class)).isEqualTo("SomeUsername");
		assertThat(json.read("$.name", String.class)).isEqualTo("SomeName");
	}

	private void assertProjectionProxy(Object payload) {
		doReturn(payload).when(this.record).value();

		Object value = this.converter.extractAndConvertValue(this.record, Sample.class);

		assertThat(value).isInstanceOf(Sample.class);

		Sample sample = (Sample) value;

		assertThat(sample.getName()).isEqualTo("SomeName");
		assertThat(sample.getUsername()).isEqualTo("SomeUsername");
	}

	private void assertSimpleObject(Object payload) {
		doReturn(payload).when(this.record).value();

		Object value = this.converter.extractAndConvertValue(this.record, AnotherSample.class);

		assertThat(value).isInstanceOf(AnotherSample.class);

		AnotherSample sample = (AnotherSample) value;

		assertThat(sample.user.name).isEqualTo("SomeName");
		assertThat(sample.username).isEqualTo("SomeUsername");
	}

	interface Sample {

		String getUsername();

		@JsonPath("$.user.name")
		String getName();

	}

	public static class AnotherSample {

		public String username;

		public User user;

		public static class User {

			public String name;

		}

	}

}
