/*
 *   Licensed to the Apache Software Foundation (ASF) under one or more
 *   contributor license agreements.  See the NOTICE file distributed with
 *   this work for additional information regarding copyright ownership.
 *   The ASF licenses this file to You under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.apache.skywalking.apm.plugin.spring.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.lang.reflect.Method;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

public class SingleMessageListenerInterceptor implements InstanceMethodsAroundInterceptor {

    public static final String OPERATE_NAME_PREFIX = "RabbitMQ/";
    public static final String CONSUMER_OPERATE_NAME_SUFFIX = "/Consumer";

    @Override public void beforeMethod(EnhancedInstance instance, Method method, Object[] objects, Class<?>[] classes,
        MethodInterceptResult result) throws Throwable {

        Message amqpMessage = (Message) objects[0];
        Channel channel = (Channel) objects[1];
        MessageProperties properties = amqpMessage.getMessageProperties();
        Connection connection = channel.getConnection();

        String serverUrl = connection.getAddress().toString().replace("/", "") + ":" + connection.getPort();

        ContextCarrier contextCarrier = new ContextCarrier();
        AbstractSpan activeSpan = ContextManager.createEntrySpan(
            OPERATE_NAME_PREFIX + "Topic/" + properties.getReceivedExchange() + "Queue/" +
                properties.getReceivedRoutingKey() + CONSUMER_OPERATE_NAME_SUFFIX, null).start(System.currentTimeMillis());
        Tags.MQ_BROKER.set(activeSpan, serverUrl);
        Tags.MQ_TOPIC.set(activeSpan, properties.getReceivedExchange());
        Tags.MQ_QUEUE.set(activeSpan, properties.getReceivedRoutingKey());
        activeSpan.setComponent(ComponentsDefine.RABBITMQ_CONSUMER);
        activeSpan.setPeer(serverUrl);
        SpanLayer.asMQ(activeSpan);
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            if (properties.getHeaders() != null && properties.getHeaders().get(next.getHeadKey()) != null) {
                next.setHeadValue(properties.getHeaders().get(next.getHeadKey()).toString());
            }
        }
        ContextManager.extract(contextCarrier);
    }

    @Override public Object afterMethod(EnhancedInstance instance, Method method, Object[] objects, Class<?>[] classes,
        Object o) throws Throwable {
        ContextManager.stopSpan();
        return o;
    }

    @Override
    public void handleMethodException(EnhancedInstance instance, Method method, Object[] objects, Class<?>[] classes,
        Throwable throwable) {
        ContextManager.activeSpan().log(throwable);
    }
}
