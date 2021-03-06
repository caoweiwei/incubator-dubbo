/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.router.condition.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.configcenter.ConfigChangeEvent;
import org.apache.dubbo.configcenter.ConfigChangeType;
import org.apache.dubbo.configcenter.ConfigurationListener;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;
import org.apache.dubbo.rpc.cluster.router.condition.ConditionRouter;
import org.apache.dubbo.rpc.cluster.router.condition.config.model.BlackWhiteListRule;
import org.apache.dubbo.rpc.cluster.router.condition.config.model.ConditionRouterRule;
import org.apache.dubbo.rpc.cluster.router.condition.config.model.ConditionRuleParser;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public abstract class AbstractConfigConditionRouter extends AbstractRouter implements ConfigurationListener {
    public static final String NAME = "CONFIG_CONDITION_OUTER";
    public static final int DEFAULT_PRIORITY = 200;
    private static final Logger logger = LoggerFactory.getLogger(AbstractConfigConditionRouter.class);
    private ConditionRouterRule routerRule;
    private List<ConditionRouter> conditionRouters = new ArrayList<>();

    public AbstractConfigConditionRouter(DynamicConfiguration configuration, URL url) {
        super(configuration, url);
        this.force = false;
        this.init();
    }

    @Override
    public synchronized void process(ConfigChangeEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of condition rule, change type is: " + event.getChangeType() + ", raw rule is:\n " + event
                    .getValue());
        }

        if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
            routerRule = null;
            conditionRouters.clear();
        } else {
            try {
                routerRule = ConditionRuleParser.parse(event.getValue());
                generateConditions(routerRule, conditionRouters);
            } catch (Exception e) {
                logger.error("Failed to parse the raw condition rule and it will not take effect, please check if the condition rule matches with the template, the raw rule is:\n " + event
                        .getValue(), e);
            }
        }

        routerChains.forEach(RouterChain::notifyRuleChanged);
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        if (CollectionUtils.isEmpty(invokers) || conditionRouters.size() == 0) {
            return invokers;
        }

        // We will check enabled status inside each router.
        for (Router router : conditionRouters) {
            invokers = router.route(invokers, url, invocation);
        }

        return invokers;
    }

    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY;
    }

    /*@Override
    public boolean isRuntime() {
        return isRuleRuntime();
    }*/

    @Override
    public boolean isEnabled() {
        return isRuleEnabled();
    }

    @Override
    public boolean isForce() {
        return (routerRule != null && routerRule.isForce());
    }

    private boolean isRuleEnabled() {
        return routerRule != null && routerRule.isValid() && routerRule.isEnabled();
    }

    private boolean isRuleRuntime() {
        return routerRule != null && routerRule.isValid() && routerRule.isRuntime();
    }

    private void generateConditions(ConditionRouterRule rule, List<ConditionRouter> routers) {
        if (rule != null && rule.isValid()) {
            routers.clear();
            rule.getConditions().forEach(condition -> {
                // All sub rules have the same force, runtime value.
                ConditionRouter subRouter = new ConditionRouter(condition, rule.isForce());
                subRouter.setEnabled(rule.isEnabled());
                routers.add(subRouter);
            });

            BlackWhiteListRule blackWhiteList = rule.getBlackWhiteList();
            if (blackWhiteList != null && blackWhiteList.isValid()) {
                blackWhiteList.getConditions().forEach(condition -> {
                    // All sub rules have the same force, runtime value.
                    ConditionRouter subRouter = new ConditionRouter(condition, true);
                    subRouter.setEnabled(blackWhiteList.isEnabled());
                    routers.add(subRouter);
                });
            }
        }
    }

    protected abstract void init();
}
