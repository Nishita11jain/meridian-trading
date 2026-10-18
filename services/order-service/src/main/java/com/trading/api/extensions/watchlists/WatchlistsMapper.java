package com.trading.api.extensions.watchlists;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WatchlistsMapper {

    /** Inserts and sets the generated id on the row. */
    int insertWatchlist(WatchlistRow row);

    List<WatchlistRow> findByAccount(@Param("accountId") Long accountId);

    WatchlistRow findById(@Param("id") Long id);

    List<String> findItemSymbols(@Param("watchlistId") Long watchlistId);

    /** The items with their flags, in the same order as findItemSymbols. */
    List<WatchlistItemRow> findItems(@Param("watchlistId") Long watchlistId);

    int setItemFlag(@Param("watchlistId") Long watchlistId, @Param("symbol") String symbol,
                    @Param("flag") String flag);

    int addItem(@Param("watchlistId") Long watchlistId, @Param("symbol") String symbol);

    int removeItem(@Param("watchlistId") Long watchlistId, @Param("symbol") String symbol);

    boolean itemExists(@Param("watchlistId") Long watchlistId, @Param("symbol") String symbol);

    boolean nameExistsForAccount(@Param("accountId") Long accountId, @Param("name") String name);

    int renameWatchlist(@Param("id") Long id, @Param("name") String name);

    int deleteItems(@Param("watchlistId") Long watchlistId);

    int deleteWatchlist(@Param("id") Long id);

    int setItemPosition(@Param("watchlistId") Long watchlistId, @Param("symbol") String symbol,
                        @Param("position") int position);
}
