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
    void refresh() throws BridgeException;
    @Override void close() throws BridgeException;
}
