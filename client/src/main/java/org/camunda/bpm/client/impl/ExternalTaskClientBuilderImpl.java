/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.client.impl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;

import org.camunda.bpm.client.ClientBackoffStrategy;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.ExternalTaskClientBuilder;
import org.camunda.bpm.client.interceptor.ClientRequestInterceptor;
import org.camunda.bpm.client.interceptor.impl.RequestInterceptorHandler;
import org.camunda.bpm.client.spi.DataFormat;
import org.camunda.bpm.client.spi.DataFormatConfigurator;
import org.camunda.bpm.client.spi.DataFormatProvider;
import org.camunda.bpm.client.topic.impl.TopicSubscriptionManager;
import org.camunda.bpm.client.variable.impl.DefaultValueMappers;
import org.camunda.bpm.client.variable.impl.TypedValues;
import org.camunda.bpm.client.variable.impl.ValueMapper;
import org.camunda.bpm.client.variable.impl.ValueMappers;
import org.camunda.bpm.client.variable.impl.mapper.BooleanValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.ByteArrayValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.DateValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.DoubleValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.IntegerValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.JavaObjectMapper;
import org.camunda.bpm.client.variable.impl.mapper.JsonValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.LongValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.NullValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.ShortValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.StringValueMapper;
import org.camunda.bpm.client.variable.impl.mapper.XmlValueMapper;
import org.camunda.bpm.engine.variable.Variables;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Tassilo Weidner
 */
public class ExternalTaskClientBuilderImpl implements ExternalTaskClientBuilder {

  protected static final ExternalTaskClientLogger LOG = ExternalTaskClientLogger.CLIENT_LOGGER;

  protected String baseUrl;
  protected String workerId;
  protected int maxTasks;
  protected Long asyncResponseTimeout;
  protected long lockDuration;

  protected String defaultSerializationFormat = Variables.SerializationDataFormats.JAVA.getName();

  protected String dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  protected ObjectMapper objectMapper;
  protected ValueMappers valueMappers;
  protected TypedValues typedValues;
  protected EngineClient engineClient;
  protected TopicSubscriptionManager topicSubscriptionManager;

  protected List<ClientRequestInterceptor> interceptors;
  protected boolean isAutoFetchingEnabled;
  protected ClientBackoffStrategy backoffStrategy;

  public ExternalTaskClientBuilderImpl() {
    // default values
    this.maxTasks = 10;
    this.asyncResponseTimeout = null;
    this.lockDuration = 20_000;
    this.interceptors = new ArrayList<>();
    this.isAutoFetchingEnabled = true;
    this.backoffStrategy = null;
  }

  public ExternalTaskClientBuilder baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  public ExternalTaskClientBuilder workerId(String workerId) {
    this.workerId = workerId;
    return this;
  }

  public ExternalTaskClientBuilder addInterceptor(ClientRequestInterceptor interceptor) {
    this.interceptors.add(interceptor);
    return this;
  }

  public ExternalTaskClientBuilder maxTasks(int maxTasks) {
    this.maxTasks = maxTasks;
    return this;
  }

  public ExternalTaskClientBuilder asyncResponseTimeout(long asyncResponseTimeout) {
    this.asyncResponseTimeout = asyncResponseTimeout;
    return this;
  }

  public ExternalTaskClientBuilder lockDuration(long lockDuration) {
    this.lockDuration = lockDuration;
    return this;
  }

  public ExternalTaskClientBuilder disableAutoFetching() {
    this.isAutoFetchingEnabled = false;
    return this;
  }

  public ExternalTaskClientBuilder backoffStrategy(ClientBackoffStrategy backoffStrategy) {
    this.backoffStrategy = backoffStrategy;
    return this;
  }

  public ExternalTaskClientBuilder defaultSerializationFormat(String defaultSerializationFormat) {
    this.defaultSerializationFormat = defaultSerializationFormat;
    return this;
  }

  public ExternalTaskClientBuilder dateFormat(String dateFormat) {
    this.dateFormat = dateFormat;
    return this;
  }

  public ExternalTaskClient build() {
    if (maxTasks <= 0) {
      throw LOG.maxTasksNotGreaterThanZeroException();
    }

    if (asyncResponseTimeout != null && asyncResponseTimeout <= 0) {
      throw LOG.asyncResponseTimeoutNotGreaterThanZeroException();
    }

    if (lockDuration <= 0L) {
      throw LOG.lockDurationIsNotGreaterThanZeroException();
    }

    if (baseUrl == null || baseUrl.isEmpty()) {
      throw LOG.baseUrlNullException();
    }

    checkInterceptors();

    initBaseUrl();
    initWorkerId();
    initObjectMapper();
    initVariableMappers();
    initEngineClient();
    initTopicSubscriptionManager();

    return new ExternalTaskClientImpl(topicSubscriptionManager);
  }

  protected void initBaseUrl() {
    baseUrl = sanitizeUrl(baseUrl);
  }

  protected String sanitizeUrl(String url) {
    url = url.trim();
    if (url.endsWith("/")) {
      url = url.replaceAll("/$", "");
      url = sanitizeUrl(url);
    }
    return url;
  }

  protected void initWorkerId() {
    if (workerId == null) {
      String hostname = checkHostname();
      this.workerId = hostname + UUID.randomUUID();
    }
  }

  protected void checkInterceptors() {
    interceptors.forEach(interceptor -> {
      if (interceptor == null) {
        throw LOG.interceptorNullException();
      }
    });
  }

  protected void initObjectMapper() {
    objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS, false);
    objectMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
  }

  protected void initVariableMappers() {
    valueMappers = new DefaultValueMappers(defaultSerializationFormat);

    valueMappers.addMapper(new NullValueMapper());
    valueMappers.addMapper(new BooleanValueMapper());
    valueMappers.addMapper(new StringValueMapper());
    valueMappers.addMapper(new DateValueMapper(dateFormat));
    valueMappers.addMapper(new ByteArrayValueMapper());

    // number mappers
    valueMappers.addMapper(new IntegerValueMapper());
    valueMappers.addMapper(new LongValueMapper());
    valueMappers.addMapper(new ShortValueMapper());
    valueMappers.addMapper(new DoubleValueMapper());

    // object
    Map<String, DataFormat> dataFormats = lookupDataFormats();
    dataFormats.forEach((key, format) -> {
      valueMappers.addMapper(new JavaObjectMapper(key, format));
    });

    // json/xml
    valueMappers.addMapper(new JsonValueMapper());
    valueMappers.addMapper(new XmlValueMapper());

    typedValues = new TypedValues(valueMappers);
  }

  protected void initEngineClient() {
    RequestInterceptorHandler requestInterceptorHandler = new RequestInterceptorHandler(interceptors);
    RequestExecutor requestExecutor = new RequestExecutor(requestInterceptorHandler, objectMapper);
    engineClient = new EngineClient(workerId, maxTasks, asyncResponseTimeout, baseUrl, requestExecutor, typedValues);
  }

  protected void initTopicSubscriptionManager() {
    topicSubscriptionManager = new TopicSubscriptionManager(engineClient, typedValues, lockDuration);
    topicSubscriptionManager.setBackoffStrategy(getBackoffStrategy());

    if (isAutoFetchingEnabled()) {
      topicSubscriptionManager.start();
    }
  }

  protected Map<String, DataFormat> lookupDataFormats() {
    Map<String, DataFormat> dataFormats = new HashMap<String, DataFormat>();

    lookupCustomDataFormats(dataFormats);
    applyConfigurators(dataFormats);

    return dataFormats;
  }

  protected void lookupCustomDataFormats(Map<String, DataFormat> dataFormats) {
    // use java.util.ServiceLoader to load custom DataFormatProvider instances on the classpath
    ServiceLoader<DataFormatProvider> providerLoader = ServiceLoader.load(DataFormatProvider.class);

    for (DataFormatProvider provider : providerLoader) {
      lookupProvider(dataFormats, provider);
    }
  }

  protected void lookupProvider(Map<String, DataFormat> dataFormats, DataFormatProvider provider) {

    String dataFormatName = provider.getDataFormatName();

    if(!dataFormats.containsKey(dataFormatName)) {
      DataFormat dataFormatInstance = provider.createInstance();
      dataFormats.put(dataFormatName, dataFormatInstance);
    }
    else {
      // throw LOG.multipleProvidersForDataformat(dataFormatName);
      throw new RuntimeException();
    }
  }

  @SuppressWarnings("rawtypes")
  protected void applyConfigurators(Map<String, DataFormat> dataFormats) {
    ServiceLoader<DataFormatConfigurator> configuratorLoader = ServiceLoader.load(DataFormatConfigurator.class);

    for (DataFormatConfigurator configurator : configuratorLoader) {
      applyConfigurator(dataFormats, configurator);
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  protected void applyConfigurator(Map<String, DataFormat> dataFormats, DataFormatConfigurator configurator) {
    for (DataFormat dataFormat : dataFormats.values()) {
      if (configurator.getDataFormatClass().isAssignableFrom(dataFormat.getClass())) {
        configurator.configure(dataFormat);
      }
    }
  }

  public String checkHostname() {
    String hostname;
    try {
      hostname = getHostname();
    } catch (UnknownHostException e) {
      throw LOG.cannotGetHostnameException();
    }

    return hostname;
  }

  public String getHostname() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  protected String getWorkerId() {
    return workerId;
  }

  protected List<ClientRequestInterceptor> getInterceptors() {
    return interceptors;
  }

  protected int getMaxTasks() {
    return maxTasks;
  }

  protected Long getAsyncResponseTimeout() {
    return asyncResponseTimeout;
  }

  protected long getLockDuration() {
    return lockDuration;
  }

  protected boolean isAutoFetchingEnabled() {
    return isAutoFetchingEnabled;
  }

  protected ClientBackoffStrategy getBackoffStrategy() {
    return backoffStrategy;
  }

  public String getDefaultSerializationFormat() {
    return defaultSerializationFormat;
  }

  public String getDateFormat() {
    return dateFormat;
  }

  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  public ValueMappers getValueMappers() {
    return valueMappers;
  }

  public TypedValues getTypedValues() {
    return typedValues;
  }

  public EngineClient getEngineClient() {
    return engineClient;
  }

}
