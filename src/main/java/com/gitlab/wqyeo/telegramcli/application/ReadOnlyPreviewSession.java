package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.session.bridge.BridgeCoordinator;
import com.gitlab.wqyeo.telegramcli.session.bridge.BridgeException;

/** Fixture-only frontend seam; neither an operational login API nor a generic SDK gateway. */
public interface ReadOnlyPreviewSession extends AutoCloseable {
    BridgeCoordinator.Snapshot latest();
    BridgeCoordinator.Snapshot tick() throws BridgeException;
    /** Select one internally generated PUBLIC fixture, never caller-supplied credentials. */
    void advanceAuthorization() throws BridgeException;
    boolean moreChats() throws BridgeException;
    /** Request ID; completion requires the matching acceptedSavedRequest in a later snapshot. */
    long savedPage(long beforeMessageId) throws BridgeException;
    /** V2 PUBLIC ordinary-reader operations. Legacy preview implementations refuse by default. */
    default void searchLoaded(String query) throws BridgeException { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    /** Returned request ID is accepted only when reader().acceptedHistoryRequest() matches it. */
    default long selectChat(long chatId) throws BridgeException { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    default boolean olderPage() throws BridgeException { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    default long latestPage() throws BridgeException { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    default void cancelRead(long requestId) throws BridgeException { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    void refresh() throws BridgeException;
    @Override void close() throws BridgeException;
}
