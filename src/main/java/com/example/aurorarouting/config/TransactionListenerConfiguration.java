package com.example.aurorarouting.config;

import com.example.aurorarouting.mode.WriteTransactionExecutionListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ConfigurableTransactionManager;

@Configuration(proxyBeanMethods = false)
public class TransactionListenerConfiguration {

    @Bean
    static BeanPostProcessor transactionListenerRegistrar(
            WriteTransactionExecutionListener listener) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof ConfigurableTransactionManager manager
                        && !manager.getTransactionExecutionListeners().contains(listener)) {
                    manager.addListener(listener);
                }
                return bean;
            }
        };
    }
}
