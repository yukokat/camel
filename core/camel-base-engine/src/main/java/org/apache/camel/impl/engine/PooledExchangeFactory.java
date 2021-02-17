/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl.engine;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.camel.*;
import org.apache.camel.spi.ExchangeFactory;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.service.ServiceSupport;

/**
 * Pooled {@link ExchangeFactory} that reuses {@link Exchange} instance from a pool.
 */
@Experimental
public class PooledExchangeFactory extends ServiceSupport
        implements ExchangeFactory, CamelContextAware, StaticService, NonManagedService {

    private final ConcurrentLinkedQueue<Exchange> pool = new ConcurrentLinkedQueue<>();

    private CamelContext camelContext;

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public Exchange create() {
        Exchange exchange = pool.poll();
        if (exchange == null) {
            // create a new exchange as there was no free from the pool
            exchange = new DefaultExchange(camelContext);
        } else {
            // reset exchange before we use it
            ExtendedExchange ee = exchange.adapt(ExtendedExchange.class);
            ee.reset();
        }
        return exchange;
    }

    @Override
    public Exchange create(Endpoint fromEndpoint) {
        Exchange exchange = pool.poll();
        if (exchange == null) {
            // create a new exchange as there was no free from the pool
            exchange = new DefaultExchange(fromEndpoint);
        } else {
            // need to mark this exchange from the given endpoint
            exchange.adapt(ExtendedExchange.class).setFromEndpoint(fromEndpoint);
        }
        return exchange;
    }

    @Override
    public void release(Exchange exchange) {
        pool.offer(exchange);
    }

    @Override
    protected void doStop() throws Exception {
        pool.clear();
    }

}
