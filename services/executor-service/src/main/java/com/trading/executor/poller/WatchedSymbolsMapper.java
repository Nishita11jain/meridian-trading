package com.trading.executor.poller;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WatchedSymbolsMapper {

    /**
     * Every symbol the poller must fetch a quote for: held positions, watchlist
     * entries and active price alerts, de-duplicated. A watchlist or alert on a
     * symbol nobody holds is still polled, so its price updates and its alert can
     * fire (Sprint 10).
     */
    List<String> findDistinctWatchedSymbols();
}