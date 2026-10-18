package com.trading.executor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sprint 7's execution venue, as its own service (per the reference
 * architecture and the instructor's contract, both of which specify a
 * separate Maven project rooted at executor/) rather than merged into
 * trade-api-service's process. No inbound API: reachable only through the
 * broker (OrderPlacedConsumer) and its own scheduler (MarketDataPoller,
 * hence @EnableScheduling).
 *
 * @MapperScan is explicit rather than relying on MyBatis's auto-detection:
 * that auto-detection scans the package of this class itself
 * (com.trading.executor), not scanBasePackages, so it would never find the
 * mapper interfaces duplicated under com.trading.api.mapper. Once @MapperScan
 * is present at all, MyBatis's autoconfiguration backs off entirely rather
 * than adding to it, so both real mapper packages are named explicitly here
 * -- com.trading.api.mapper (Account/Order/Position/Instrument) and
 * com.trading.executor.poller (WatchedSymbolsMapper). Naming them precisely,
 * rather than the whole com.trading tree, matters: MapperScan registers a
 * MyBatis proxy for every interface it finds, and a broader scan wrongly
 * caught QuoteClient (a plain Spring-injected REST client interface, not a
 * mapper) in com.trading.executor.client.
 */
@SpringBootApplication(scanBasePackages = "com.trading")
@MapperScan({"com.trading.api.mapper", "com.trading.executor.poller"})
@EnableScheduling
public class TradeExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeExecutorApplication.class, args);
    }
}
