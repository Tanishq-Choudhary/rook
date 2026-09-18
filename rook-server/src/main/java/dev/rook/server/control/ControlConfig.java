package dev.rook.server.control;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.limit.RateLimiter;
import dev.rook.control.store.ApprovalStore;
import dev.rook.control.store.PolicyStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ControlProperties.class)
public class ControlConfig {

    @Bean
    PolicyStore policyStore(JdbcClient jdbc, TransactionTemplate tx) {
        return new PolicyStore(jdbc, tx);
    }

    @Bean
    ApprovalStore approvalStore(JdbcClient jdbc, Clock clock) {
        return new ApprovalStore(jdbc, clock);
    }

    @Bean
    AuditLog auditLog(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        return new AuditLog(jdbc, tx, clock);
    }

    @Bean
    BudgetService budgetService(
            JdbcClient jdbc, TransactionTemplate tx, Clock clock, ControlProperties properties) {
        return new BudgetService(
                jdbc,
                tx,
                clock,
                new BudgetService.Caps(
                        properties.monthlyBudgetMicros(), properties.jobBudgetMicros()));
    }

    @Bean
    ApiKeys apiKeys(JdbcClient jdbc, Clock clock) {
        return new ApiKeys(jdbc, clock);
    }

    @Bean
    ApplicationRunner bootstrapKey(ApiKeys keys, ControlProperties properties) {
        return arguments -> {
            if (properties.bootstrapKey() != null) {
                keys.bootstrap(properties.bootstrapKey());
            }
        };
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeaders(ControlProperties properties) {
        FilterRegistrationBean<SecurityHeadersFilter> bean =
                new FilterRegistrationBean<>(
                        new SecurityHeadersFilter(properties.protectedPrefix()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSize(
            ControlProperties properties, MeterRegistry meters) {
        FilterRegistrationBean<RequestSizeFilter> bean =
                new FilterRegistrationBean<>(
                        new RequestSizeFilter(properties.maxRequestBytes(), meters));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            ApiKeys keys, Clock clock, ControlProperties properties, MeterRegistry meters) {
        RateLimiter perKey =
                new RateLimiter(clock, properties.rateBurst(), properties.ratePerSecond(), 10_000);
        RateLimiter failures = new RateLimiter(clock, properties.failureBurst(), 1.0, 10_000);
        FilterRegistrationBean<ApiKeyFilter> bean =
                new FilterRegistrationBean<>(
                        new ApiKeyFilter(
                                keys, perKey, failures, meters, properties.protectedPrefix()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return bean;
    }
}
